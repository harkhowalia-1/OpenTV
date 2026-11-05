using OpenTV.Core.Discovery;

namespace OpenTV.Core;

public enum RemotePhase
{
    /// <summary>No TV chosen yet.</summary>
    Searching,
    /// <summary>TV chosen, waiting for the 4-digit code.</summary>
    Pairing,
    Connected,
    /// <summary>TV chosen and paired but not answering (asleep, off, other network).</summary>
    Offline,
}

/// <summary>
/// Finds TVs (mDNS, and a USB-connected one), keeps the chosen TV connected over the live channel, and sends
/// buttons. Reconnects by itself after the TV sleeps or the app relaunches. The same logic as the iPhone and
/// Mac apps' RemoteModel.
///
/// Call it from one thread (the UI thread); everything it reports comes back through <see cref="Changed"/>
/// on that thread's <see cref="SynchronizationContext"/>.
/// </summary>
public sealed class RemoteModel : IDisposable
{
    private readonly TvStore store;
    private readonly SynchronizationContext ctx;
    private readonly MdnsBrowser browser = new();
    private readonly List<TvDevice> found = new();
    private TvDevice? usbTv;
    private bool browsing;

    public ControlChannel Channel { get; } = new();

    public RemoteModel(TvStore store, SynchronizationContext? context = null)
    {
        this.store = store;
        ctx = context ?? SynchronizationContext.Current ?? new SynchronizationContext();
        Channel.Context = ctx;
        Tv = store.Tv;
        if (Tv is not null) Phase = store.Token(Tv) is null ? RemotePhase.Pairing : RemotePhase.Offline;
        Channel.StateChanged += ChannelState;
        Channel.NowPlayingChanged += np => { NowPlaying = np; Notify(); };
        Channel.Welcomed += Welcomed;
        browser.Found += tv => ctx.Post(_ => FoundTv(tv), null);
    }

    /// <summary>Anything shown in the remote changed.</summary>
    public event Action? Changed;

    public IReadOnlyList<TvDevice> Found => found;
    public TvDevice? Tv { get; private set; }
    public RemotePhase Phase { get; private set; } = RemotePhase.Searching;
    public NowPlaying? NowPlaying { get; private set; }

    /// <summary>The TV has HDMI or other inputs (not a Chromecast): the remote shows an Inputs button.</summary>
    public bool HasInputs { get; private set; }
    public string? Error { get; set; }
    public TvStore Store => store;

    public bool IsPaired => Phase is RemotePhase.Connected or RemotePhase.Offline;

    /// <summary>A TV reached over USB, preferred over Wi-Fi while plugged in.</summary>
    public TvDevice? UsbTv
    {
        get => usbTv;
        set
        {
            if (usbTv == value) return;
            usbTv = value;
            Reconnect();
            _ = RefreshAsync();
            Notify();
        }
    }

    /// <summary>The TV as currently reachable: over USB when plugged in, otherwise over the network.</summary>
    public TvDevice? Route
    {
        get
        {
            if (Tv is not { } t) return null;
            if (usbTv is { } u && (u.TvId is null || t.TvId is null || u.TvId == t.TvId))
            {
                return t with
                {
                    HomeHost = t.Host, Host = u.Host, Port = u.Port, ControlPort = u.ControlPort,
                    DisplayPort = u.DisplayPort, Usb = true,
                };
            }
            return t;
        }
    }

    public string? Token => Route is { } r ? store.Token(r) : null;

    private void Notify() => Changed?.Invoke();

    // ---- Lifecycle -------------------------------------------------------------------------------

    /// <summary>Call at launch and whenever the remote is opened: browses and checks the TV.</summary>
    public void Activate()
    {
        if (!browsing)
        {
            browsing = true;
            browser.Start();
        }
        else
        {
            browser.Refresh();
        }
        _ = RefreshAsync();
    }

    public void Dispose()
    {
        browser.Dispose();
        Channel.Dispose();
    }

    private void Reconnect()
    {
        if (Route is not { } route || store.Token(route) is not { } token)
        {
            Channel.Disconnect();
            return;
        }
        Channel.Disconnect();
        Channel.Connect(route, token);
    }

    private async Task RefreshAsync()
    {
        if (Route is not { } route)
        {
            Phase = RemotePhase.Searching;
            Notify();
            return;
        }
        try
        {
            var hello = await new RemoteClient(route, store.Token(route)).HelloAsync().ConfigureAwait(true);
            HasInputs = hello.Inputs;
            if (Tv is { } t && (hello.Name != t.Name || (hello.Id is not null && hello.Id != t.TvId)))
            {
                var token = store.Token(t);
                var updated = t with { Name = hello.Name, TvId = hello.Id ?? t.TvId };
                Update(updated);
                if (token is not null && store.Token(updated) is null) store.SetToken(token, updated);
            }
            if (hello.Paired)
            {
                if (!Channel.IsReady) Reconnect();
            }
            else
            {
                Phase = RemotePhase.Pairing;
                Channel.Disconnect();
            }
        }
        catch (RemoteException)
        {
            // Not answering now: the channel keeps retrying and flips to connected when the TV is back.
            if (store.Token(route) is null)
            {
                Phase = RemotePhase.Pairing;
            }
            else
            {
                Phase = RemotePhase.Offline;
                Reconnect();
            }
        }
        Notify();
    }

    private void ChannelState(ChannelState s)
    {
        switch (s)
        {
            case Core.ChannelState.Ready:
                Phase = RemotePhase.Connected;
                break;
            case Core.ChannelState.Denied:
                if (Tv is { } tv) store.SetToken(null, tv);
                Phase = RemotePhase.Pairing;
                break;
            default:
                if (Phase == RemotePhase.Connected) Phase = RemotePhase.Offline;
                break;
        }
        Notify();
    }

    private void Welcomed(string name, string? id)
    {
        if (Tv is not { } t) return;
        if (t.Name != name || (id is not null && t.TvId != id))
        {
            var token = store.Token(t);
            var updated = t with { Name = name.Length > 0 ? name : t.Name, TvId = id ?? t.TvId };
            Update(updated);
            if (token is not null) store.SetToken(token, updated);
        }
    }

    // ---- Choosing and pairing --------------------------------------------------------------------

    public void Choose(TvDevice tv)
    {
        Update(tv);
        NowPlaying = null;
        HasInputs = false;
        Phase = store.Token(tv) is null ? RemotePhase.Pairing : RemotePhase.Offline;
        Reconnect();
        Notify();
        _ = RefreshAsync();
    }

    public void Forget()
    {
        if (Tv is { } tv) store.SetToken(null, tv);
        Channel.Disconnect();
        store.Tv = null;
        Tv = null;
        NowPlaying = null;
        HasInputs = false;
        Phase = RemotePhase.Searching;
        Notify();
        browser.Refresh();
    }

    /// <summary>Adds a TV by its address, for networks that block multicast.</summary>
    public void Add(string address)
    {
        if (TvDevice.FromAddress(address) is { } tv) Choose(tv);
    }

    public async Task ShowCodeAsync()
    {
        if (Route is not { } route) return;
        try
        {
            await new RemoteClient(route, null).StartPairingAsync().ConfigureAwait(true);
        }
        catch (RemoteException e)
        {
            Error = e.Message;
            Notify();
        }
    }

    public async Task<bool> PairAsync(string code)
    {
        if (Route is not { } route || Tv is not { } tv) return false;
        try
        {
            var client = new RemoteClient(route, null);
            var token = await client.FinishPairingAsync(code.Trim()).ConfigureAwait(true);
            store.SetToken(token, route);
            // Keep the token under the TV's ID as well as the route it was made on.
            try
            {
                var hello = await new RemoteClient(route, token).HelloAsync().ConfigureAwait(true);
                if (hello.Id is not null)
                {
                    tv = tv with { TvId = hello.Id, Name = hello.Name };
                    Update(tv);
                }
            }
            catch (RemoteException)
            {
            }
            store.SetToken(token, tv);
            Phase = RemotePhase.Offline;
            Reconnect();
            Notify();
            return true;
        }
        catch (RemoteException e)
        {
            Error = e.Message;
            Notify();
            return false;
        }
    }

    private void Update(TvDevice tv)
    {
        Tv = tv;
        store.Tv = tv;
    }

    // ---- Buttons ---------------------------------------------------------------------------------

    public void Press(RemoteKey key)
    {
        if (Channel.IsReady)
        {
            Channel.Key(key);
            if (key == RemoteKey.PlayPause && NowPlaying is { } np)
            {
                NowPlaying = np with { Playing = !np.Playing };
                Notify();
            }
            return;
        }
        // Not connected yet (TV waking up): fall back to a one-off request.
        if (Route is not { } route) return;
        _ = PressOnceAsync(route, key);
    }

    private async Task PressOnceAsync(TvDevice route, RemoteKey key)
    {
        try
        {
            await new RemoteClient(route, store.Token(route)).PressAsync(key).ConfigureAwait(true);
        }
        catch (RemoteException e)
        {
            Phase = e.Kind == RemoteErrorKind.NotPaired ? RemotePhase.Pairing : RemotePhase.Offline;
            Notify();
        }
    }

    /// <summary>Replaces the TV's focused text field with <paramref name="text"/>.</summary>
    public void SetText(string text)
    {
        if (Channel.IsReady)
        {
            Channel.SetText(text);
            return;
        }
        if (Route is { } route) _ = IgnoreErrors(new RemoteClient(route, store.Token(route)).TextAsync(text));
    }

    /// <summary>"Send to TV" for a link; returns what opened it on the TV, or null.</summary>
    public async Task<string?> SendAsync(string url, double? time = null)
    {
        if (Route is not { } route) return null;
        try
        {
            return await new RemoteClient(route, store.Token(route)).OpenAsync(url, time).ConfigureAwait(true);
        }
        catch (RemoteException e)
        {
            Error = e.Message;
            return null;
        }
    }

    private static async Task IgnoreErrors(Task t)
    {
        try
        {
            await t.ConfigureAwait(false);
        }
        catch (RemoteException)
        {
        }
    }

    // ---- Discovery -------------------------------------------------------------------------------

    private void FoundTv(TvDevice tv)
    {
        found.RemoveAll(f => f.Host == tv.Host || (f.TvId is not null && f.TvId == tv.TvId));
        found.Add(tv);
        // The TV's address can change (DHCP); follow it by its ID (or its name for TVs without one).
        if (Tv is { } current && current.Host != tv.Host &&
            ((tv.TvId is not null && tv.TvId == current.TvId) || (current.TvId is null && current.Name == tv.Name)))
        {
            var token = store.Token(current);
            var moved = current with { Host = tv.Host, Port = tv.Port, TvId = tv.TvId ?? current.TvId, Model = tv.Model ?? current.Model };
            Update(moved);
            if (token is not null) store.SetToken(token, moved);
            Reconnect();
        }
        // First run with one TV on the network: pick it.
        if (Tv is null && found.Count == 1) Choose(tv);
        Notify();
    }
}
