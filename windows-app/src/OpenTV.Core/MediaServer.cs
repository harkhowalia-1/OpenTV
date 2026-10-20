using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace OpenTV.Core;

/// <summary>
/// "Send to TV" for a file on this PC: a tiny HTTP server that serves only the files it has been handed,
/// each under an unguessable path, with byte ranges so the TV's player can seek. Built on a plain TCP
/// listener rather than HttpListener, which on Windows needs administrator rights (URL ACLs) for anything
/// other than localhost.
/// </summary>
public sealed class MediaServer : IDisposable
{
    private readonly ConcurrentDictionary<string, string> files = new();
    private TcpListener? listener;
    private CancellationTokenSource? cts;

    public int Port { get; private set; }

    /// <summary>What the TV's player can open (the TV picks its player by the path's extension).</summary>
    public static readonly IReadOnlyDictionary<string, string> Types = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        [".mp4"] = "video/mp4", [".m4v"] = "video/mp4", [".mov"] = "video/quicktime", [".webm"] = "video/webm",
        [".mkv"] = "video/x-matroska", [".ts"] = "video/mp2t", [".mp3"] = "audio/mpeg", [".m4a"] = "audio/mp4",
        [".aac"] = "audio/aac", [".flac"] = "audio/flac",
    };

    public static bool CanPlayOnTv(string path) => Types.ContainsKey(Path.GetExtension(path));

    public void Start(int port = 0)
    {
        if (listener is not null) return;
        var l = new TcpListener(IPAddress.Any, port);
        l.Start();
        listener = l;
        Port = ((IPEndPoint)l.LocalEndpoint).Port;
        cts = new CancellationTokenSource();
        var ct = cts.Token;
        _ = Task.Run(() => AcceptLoopAsync(l, ct));
    }

    public void Stop()
    {
        cts?.Cancel();
        listener?.Stop();
        listener = null;
        cts?.Dispose();
        cts = null;
    }

    public void Dispose() => Stop();

    /// <summary>Makes <paramref name="path"/> reachable; returns the URL path ("/m/&lt;key&gt;/name.mp4").</summary>
    public string Share(string path)
    {
        var key = Convert.ToHexString(RandomNumberGenerator.GetBytes(12)).ToLowerInvariant();
        files[key] = path;
        return $"/m/{key}/{Uri.EscapeDataString(Path.GetFileName(path))}";
    }

    /// <summary>The full URL for the TV: this PC's address on the network the TV is on.</summary>
    public string UrlFor(string sharedPath, string tvHost)
    {
        var ip = LocalAddressToward(tvHost) ?? "127.0.0.1";
        return $"http://{ip}:{Port}{sharedPath}";
    }

    /// <summary>The local IPv4 address the system would use to reach <paramref name="host"/>.</summary>
    public static string? LocalAddressToward(string host)
    {
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            s.Connect(host, 9);   // no packet is sent: this only picks the route
            return (s.LocalEndPoint as IPEndPoint)?.Address.ToString();
        }
        catch (SocketException)
        {
            return null;
        }
    }

    private async Task AcceptLoopAsync(TcpListener l, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient c;
            try
            {
                c = await l.AcceptTcpClientAsync(ct).ConfigureAwait(false);
            }
            catch (Exception)
            {
                return;
            }
            _ = Task.Run(() => ServeAsync(c, ct));
        }
    }

    private async Task ServeAsync(TcpClient client, CancellationToken ct)
    {
        using var c = client;
        c.NoDelay = true;
        var stream = c.GetStream();
        try
        {
            // Keep-alive: the TV's player makes several range requests on one connection.
            while (!ct.IsCancellationRequested)
            {
                var head = await ReadHeadAsync(stream, ct).ConfigureAwait(false);
                if (head is null) return;
                if (!await RespondAsync(stream, head, ct).ConfigureAwait(false)) return;
            }
        }
        catch (Exception)
        {
            // Client went away mid-file (the TV seeks by dropping the connection).
        }
    }

    private static async Task<string?> ReadHeadAsync(NetworkStream s, CancellationToken ct)
    {
        var buf = new byte[8192];
        var n = 0;
        while (n < buf.Length)
        {
            var r = await s.ReadAsync(buf.AsMemory(n, 1), ct).ConfigureAwait(false);
            if (r == 0) return null;
            n++;
            if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n')
            {
                return Encoding.ASCII.GetString(buf, 0, n);
            }
        }
        return null;
    }

    /// <summary>Answers one request; returns whether the connection can be used again.</summary>
    private async Task<bool> RespondAsync(NetworkStream s, string head, CancellationToken ct)
    {
        var lines = head.Split("\r\n");
        var parts = lines[0].Split(' ');
        if (parts.Length < 2) return false;
        var method = parts[0];
        var target = parts[1].Split('?')[0];
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            var i = line.IndexOf(':');
            if (i > 0) headers[line[..i].Trim()] = line[(i + 1)..].Trim();
        }
        var keepAlive = !headers.TryGetValue("Connection", out var conn) || !conn.Equals("close", StringComparison.OrdinalIgnoreCase);

        var seg = target.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (method is not ("GET" or "HEAD") || seg.Length < 2 || seg[0] != "m" || !files.TryGetValue(seg[1], out var path) || !File.Exists(path))
        {
            await WriteHeadAsync(s, "404 Not Found", "text/plain", 0, null, keepAlive, ct).ConfigureAwait(false);
            return keepAlive;
        }

        await using var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 16, useAsync: true);
        var size = f.Length;
        long start = 0, end = size - 1;
        var partial = false;
        if (headers.TryGetValue("Range", out var range) && TryParseRange(range, size, out start, out end))
        {
            partial = true;
        }
        else if (headers.ContainsKey("Range"))
        {
            await WriteHeadAsync(s, "416 Range Not Satisfiable", "text/plain", 0, $"Content-Range: bytes */{size}\r\n", keepAlive, ct).ConfigureAwait(false);
            return keepAlive;
        }
        var type = Types.TryGetValue(Path.GetExtension(path), out var t) ? t : "application/octet-stream";
        var length = size == 0 ? 0 : end - start + 1;
        var extra = "Accept-Ranges: bytes\r\n" + (partial ? $"Content-Range: bytes {start}-{end}/{size}\r\n" : "");
        await WriteHeadAsync(s, partial ? "206 Partial Content" : "200 OK", type, length, extra, keepAlive, ct).ConfigureAwait(false);
        if (method == "HEAD" || length == 0) return keepAlive;

        f.Seek(start, SeekOrigin.Begin);
        var buf = new byte[1 << 16];
        var left = length;
        while (left > 0)
        {
            var r = await f.ReadAsync(buf.AsMemory(0, (int)Math.Min(buf.Length, left)), ct).ConfigureAwait(false);
            if (r <= 0) return false;
            await s.WriteAsync(buf.AsMemory(0, r), ct).ConfigureAwait(false);
            left -= r;
        }
        return keepAlive;
    }

    /// <summary>"bytes=0-", "bytes=100-199" or "bytes=-500" (the last 500 bytes).</summary>
    public static bool TryParseRange(string header, long size, out long start, out long end)
    {
        start = 0;
        end = size - 1;
        if (!header.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase)) return false;
        var spec = header[6..].Split(',')[0].Trim();
        var dash = spec.IndexOf('-');
        if (dash < 0 || size <= 0) return false;
        var a = spec[..dash].Trim();
        var b = spec[(dash + 1)..].Trim();
        if (a.Length == 0)
        {
            if (!long.TryParse(b, out var suffix) || suffix <= 0) return false;
            start = Math.Max(0, size - suffix);
            return true;
        }
        if (!long.TryParse(a, out start) || start >= size) return false;
        if (b.Length > 0)
        {
            if (!long.TryParse(b, out end) || end < start) return false;
            end = Math.Min(end, size - 1);
        }
        return true;
    }

    private static Task WriteHeadAsync(NetworkStream s, string status, string type, long length, string? extra, bool keepAlive, CancellationToken ct)
    {
        var h = $"HTTP/1.1 {status}\r\nContent-Type: {type}\r\nContent-Length: {length}\r\n{extra}" +
                $"Connection: {(keepAlive ? "keep-alive" : "close")}\r\nCache-Control: no-store\r\n\r\n";
        return s.WriteAsync(Encoding.ASCII.GetBytes(h), ct).AsTask();
    }
}
