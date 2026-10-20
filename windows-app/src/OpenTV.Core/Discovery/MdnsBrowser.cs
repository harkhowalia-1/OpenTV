using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace OpenTV.Core.Discovery;

/// <summary>
/// Finds OtvOS TVs on the local network: DNS-SD over multicast DNS for <c>_tvremote._tcp</c>, the service the
/// TV's launcher advertises (TXT <c>name</c>, <c>id</c>, <c>model</c>; port 8800).
///
/// Two ways in, so it works next to Windows' own mDNS responder (and macOS's, for the protocol test):
/// a one-shot query from an ephemeral port on every network interface, which responders answer by unicast
/// straight back to that port (RFC 6762 §6.7), and, when the system lets it share port 5353, a listener on the
/// multicast group that also hears the TV's announcements when it comes online.
/// </summary>
public sealed class MdnsBrowser : IDisposable
{
    public const string ServiceType = "_tvremote._tcp.local";
    private static readonly IPEndPoint Group = new(IPAddress.Parse("224.0.0.251"), 5353);

    private readonly ConcurrentDictionary<string, Instance> instances = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, IPAddress> hosts = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<UdpClient> senders = new();
    private UdpClient? listener;
    private CancellationTokenSource? cts;
    private readonly SemaphoreSlim queryNow = new(0);

    /// <summary>A TV was found, or one that was found before changed address. Raised on a pool thread.</summary>
    public event Action<TvDevice>? Found;

    private sealed class Instance
    {
        public string? Target;
        public ushort Port;
        public IReadOnlyDictionary<string, string>? Txt;
        public IPAddress? Source;
        public TvDevice? Emitted;
    }

    public void Start()
    {
        if (cts is not null) return;
        cts = new CancellationTokenSource();
        var ct = cts.Token;
        OpenSockets(ct);
        _ = Task.Run(() => QueryLoopAsync(ct));
    }

    /// <summary>Asks again now (the remote was just opened, or the network changed).</summary>
    public void Refresh()
    {
        if (cts is null) return;
        queryNow.Release();
    }

    public void Stop()
    {
        cts?.Cancel();
        cts?.Dispose();
        cts = null;
        lock (senders)
        {
            foreach (var s in senders) s.Dispose();
            senders.Clear();
        }
        listener?.Dispose();
        listener = null;
    }

    public void Dispose() => Stop();

    /// <summary>Browses for <paramref name="duration"/> and returns every TV that answered.</summary>
    public static async Task<List<TvDevice>> BrowseAsync(TimeSpan duration, CancellationToken ct = default)
    {
        var found = new ConcurrentDictionary<string, TvDevice>();
        using var b = new MdnsBrowser();
        b.Found += tv => found[tv.TvId ?? tv.Host] = tv;
        b.Start();
        try
        {
            await Task.Delay(duration, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }
        return found.Values.ToList();
    }

    public static IEnumerable<(NetworkInterface nic, IPAddress address)> Ipv4Interfaces()
    {
        NetworkInterface[] nics;
        try
        {
            nics = NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException)
        {
            yield break;
        }
        foreach (var nic in nics)
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            if (!nic.SupportsMulticast) continue;
            IPInterfaceProperties props;
            try
            {
                props = nic.GetIPProperties();
            }
            catch (NetworkInformationException)
            {
                continue;
            }
            foreach (var a in props.UnicastAddresses)
            {
                if (a.Address.AddressFamily == AddressFamily.InterNetwork) yield return (nic, a.Address);
            }
        }
    }

    private void OpenSockets(CancellationToken ct)
    {
        foreach (var (_, address) in Ipv4Interfaces())
        {
            try
            {
                var u = new UdpClient(new IPEndPoint(address, 0));
                u.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, address.GetAddressBytes());
                u.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
                lock (senders) senders.Add(u);
                _ = Task.Run(() => ReceiveLoopAsync(u, ct));
            }
            catch (SocketException)
            {
            }
        }
        try
        {
            var l = new UdpClient(AddressFamily.InterNetwork);
            l.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            l.Client.Bind(new IPEndPoint(IPAddress.Any, 5353));
            foreach (var (_, address) in Ipv4Interfaces())
            {
                try
                {
                    l.JoinMulticastGroup(Group.Address, address);
                }
                catch (SocketException)
                {
                }
            }
            listener = l;
            _ = Task.Run(() => ReceiveLoopAsync(l, ct));
        }
        catch (SocketException)
        {
            // Port 5353 isn't shareable here; the unicast replies are enough.
        }
    }

    private async Task QueryLoopAsync(CancellationToken ct)
    {
        var delay = TimeSpan.FromSeconds(1);
        while (!ct.IsCancellationRequested)
        {
            SendQueries();
            try
            {
                // Ask again quickly at first (packets get lost on Wi-Fi), then settle to once a minute.
                await queryNow.WaitAsync(delay, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            delay = TimeSpan.FromSeconds(Math.Min(delay.TotalSeconds * 2, 60));
        }
    }

    private void SendQueries()
    {
        var unicast = DnsMessage.PtrQuery(ServiceType, unicastResponse: true);
        lock (senders)
        {
            foreach (var u in senders)
            {
                try
                {
                    u.Send(unicast, unicast.Length, Group);
                }
                catch (Exception e) when (e is SocketException or ObjectDisposedException)
                {
                }
            }
        }
    }

    private async Task ReceiveLoopAsync(UdpClient u, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult r;
            try
            {
                r = await u.ReceiveAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                // ICMP port unreachable and similar: keep listening.
                continue;
            }
            Handle(r.Buffer, r.RemoteEndPoint.Address);
        }
    }

    /// <summary>Feeds one received packet (public for the protocol test's recorded answers).</summary>
    public void Handle(byte[] packet, IPAddress source)
    {
        var records = DnsMessage.ParseResponse(packet);
        if (records.Count == 0) return;
        var touched = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var r in records)
        {
            switch (r.Type)
            {
                case DnsMessage.TypeA when r.Address is not null:
                    hosts[r.Name] = r.Address;
                    foreach (var (k, i) in instances)
                    {
                        if (string.Equals(i.Target, r.Name, StringComparison.OrdinalIgnoreCase)) touched.Add(k);
                    }
                    break;
                case DnsMessage.TypePtr when r.Target is not null && r.Name.Equals(ServiceType, StringComparison.OrdinalIgnoreCase):
                    if (r.Ttl == 0) break;   // goodbye
                    Get(r.Target, source);
                    touched.Add(r.Target);
                    break;
                case DnsMessage.TypeSrv when IsOurs(r.Name):
                    var s = Get(r.Name, source);
                    s.Target = r.Target;
                    s.Port = r.Port;
                    touched.Add(r.Name);
                    break;
                case DnsMessage.TypeTxt when IsOurs(r.Name):
                    Get(r.Name, source).Txt = r.Txt;
                    touched.Add(r.Name);
                    break;
            }
        }
        foreach (var name in touched)
        {
            if (instances.TryGetValue(name, out var i)) Emit(name, i);
        }
    }

    private static bool IsOurs(string name) => name.EndsWith("." + ServiceType, StringComparison.OrdinalIgnoreCase);

    private Instance Get(string name, IPAddress source)
    {
        var i = instances.GetOrAdd(name, _ => new Instance());
        if (source.AddressFamily == AddressFamily.InterNetwork) i.Source = source;
        return i;
    }

    private void Emit(string instanceName, Instance i)
    {
        if (i.Port == 0) return;
        IPAddress? address = null;
        if (i.Target is not null) hosts.TryGetValue(i.Target, out address);
        address ??= i.Source;
        if (address is null) return;
        string? Txt(string k) => i.Txt is not null && i.Txt.TryGetValue(k, out var v) && v.Length > 0 ? v : null;
        var label = instanceName.EndsWith("." + ServiceType, StringComparison.OrdinalIgnoreCase)
            ? instanceName[..^(ServiceType.Length + 1)]
            : instanceName;
        var tv = new TvDevice
        {
            Name = Txt("name") ?? label,
            Host = address.ToString(),
            Port = i.Port,
            TvId = Txt("id"),
            Model = Txt("model"),
        };
        if (tv == i.Emitted) return;
        i.Emitted = tv;
        Found?.Invoke(tv);
    }
}
