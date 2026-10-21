using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using OpenTV.Core;
using OpenTV.Core.Discovery;

namespace OpenTV.ProtocolTest;

/// <summary>
/// OpenTV protocol sanity test.
///
///   dotnet run -- [--offline] [--host 192.168.2.24] [--token HEX] [--display] [--usb] [--pair]
///
/// Offline checks always run. The live part only reads from the TV: mDNS discovery, GET /api/hello, a control
/// channel with a wrong token (expects DENIED) and, with a token (--token or OTV_TOKEN), WELCOME, PING/PONG,
/// GET /api/state and the remote model reaching "Connected". Nothing here presses a key or changes what's on
/// the TV screen. --display does the display handshake only (HELLO, WELCOME, BYE: the TV starts showing a
/// stream only once VIDEO_CONFIG arrives, which this never sends). --usb forwards the TV's ports over adb to
/// 28800-28802 and removes the forwards afterwards. --pair is the one thing that shows something on the TV
/// (the 4-digit code), so it only runs when asked.
/// </summary>
public static class Program
{
    private static int failures;
    private static int passes;

    public static async Task<int> Main(string[] args)
    {
        var offline = args.Contains("--offline");
        var host = Arg(args, "--host");
        var token = Arg(args, "--token") ?? Environment.GetEnvironmentVariable("OTV_TOKEN");
        if (string.IsNullOrWhiteSpace(token)) token = null;

        Console.WriteLine("OpenTV protocol test");
        Console.WriteLine("── offline ──");
        Check("control frames", ControlFrames);
        Check("control frame reader", ControlReader);
        Check("display frames", DisplayFrames);
        Check("Annex-B parsing", AnnexBParsing);
        Check("pointer move coalescing (120 Hz)", Coalescing);
        Check("wheel and touchpad scroll phases", ScrollPhases);
        Check("mDNS answer parsing", MdnsParsing);
        Check("adb device list parsing", AdbParsing);
        Check("token store keys", StoreKeys);
        await CheckAsync("media file server (ranges)", MediaServerRanges);

        if (!offline)
        {
            Console.WriteLine("── live (read-only) ──");
            await LiveAsync(host, token, args.Contains("--display"), args.Contains("--usb"), args.Contains("--pair"));
        }

        Console.WriteLine();
        Console.WriteLine(failures == 0 ? $"All {passes} checks passed." : $"{failures} of {passes + failures} checks FAILED.");
        return failures == 0 ? 0 : 1;
    }

    private static string? Arg(string[] args, string name)
    {
        var i = Array.IndexOf(args, name);
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }

    // ---- harness ---------------------------------------------------------------------------------

    private static void Check(string name, Action test)
    {
        try
        {
            test();
            Pass(name);
        }
        catch (Exception e)
        {
            Fail(name, e.Message);
        }
    }

    private static async Task CheckAsync(string name, Func<Task> test)
    {
        try
        {
            await test();
            Pass(name);
        }
        catch (Exception e)
        {
            Fail(name, e.Message);
        }
    }

    private static void Pass(string name, string? detail = null)
    {
        passes++;
        Console.WriteLine($"  ok    {name}{(detail is null ? "" : "  · " + detail)}");
    }

    private static void Fail(string name, string why)
    {
        failures++;
        Console.WriteLine($"  FAIL  {name}: {why}");
    }

    private static void Skip(string name, string why) => Console.WriteLine($"  skip  {name}: {why}");

    private static void Expect(bool condition, string what)
    {
        if (!condition) throw new Exception(what);
    }

    private static void Bytes(byte[] actual, string hex, string what)
    {
        var a = Convert.ToHexString(actual);
        Expect(a == hex.Replace(" ", ""), $"{what}: got {a}, expected {hex}");
    }

    // ---- offline ---------------------------------------------------------------------------------

    private static void ControlFrames()
    {
        Bytes(ControlProtocol.HelloFrame("ab"), "0002 01 6162", "HELLO");
        Bytes(ControlProtocol.KeyFrame(RemoteKey.PlayPause), "0009 02 706C6179706175736 5", "KEY playpause");
        Bytes(ControlProtocol.KeyFrame(RemoteKey.VolUp), "0005 02 766F6C7570", "KEY volup");
        Bytes(ControlProtocol.TypeFrame("\b\n"), "0002 0A 080A", "TYPE");
        Bytes(ControlProtocol.PointerEnterFrame(0.5, "bottom"), "0008 04 8000 626F74746F6D", "POINTER_ENTER");
        Bytes(ControlProtocol.PointerMoveFrame(-3, 70000), "0004 05 FFFD 7FFF", "POINTER_MOVE (clamped)");
        Bytes(ControlProtocol.PointerButtonFrame(1, true), "0002 06 0101", "POINTER_BUTTON");
        Bytes(ControlProtocol.PointerScrollFrame(0, -40, ScrollPhase.Changed), "0005 07 0000 FFD8 02", "POINTER_SCROLL");
        Bytes(ControlProtocol.PointerExitFrame(), "0000 08", "POINTER_EXIT");
        Bytes(ControlProtocol.PingFrame(), "0000 0F", "PING");
        Expect(Math.Abs(ControlProtocol.ParseAlong([0xFF, 0xFF]) - 1) < 1e-9, "POINTER_LEFT along");
        var big = ControlProtocol.TextFrame(new string('é', 40000));   // 80000 bytes of UTF-8
        Expect(big.Length <= 3 + 0xFFFF && (big.Length - 3) % 2 == 0, "TEXT is cut to 64 KB on a character boundary");
        foreach (var k in Enum.GetValues<RemoteKey>()) Expect(RemoteKeys.Parse(k.Wire()) == k, $"key name {k}");
        Expect(string.Join(",", Enum.GetValues<RemoteKey>().Select(k => k.Wire())) ==
               "up,down,left,right,select,back,home,control,inputs,playpause,next,previous,volup,voldown,mute,power", "wire names");
    }

    private static void ControlReader()
    {
        var r = new ControlFrameReader();
        var welcome = ControlProtocol.Utf8Frame(ControlProtocol.Welcome, "{\"name\":\"TV\"}");
        var left = ControlProtocol.Frame(ControlProtocol.PointerLeft, [0x40, 0x00]);
        var stream = welcome.Concat(left).Concat(ControlProtocol.Frame(ControlProtocol.Pong, [])).ToArray();
        foreach (var b in stream) r.Feed([b]);   // byte by byte, as TCP may deliver it
        Expect(r.TryRead(out var t1, out var p1) && t1 == ControlProtocol.Welcome && Encoding.UTF8.GetString(p1) == "{\"name\":\"TV\"}", "WELCOME");
        Expect(r.TryRead(out var t2, out var p2) && t2 == ControlProtocol.PointerLeft && Math.Abs(ControlProtocol.ParseAlong(p2) - 0.25) < 0.001, "POINTER_LEFT");
        Expect(r.TryRead(out var t3, out var p3) && t3 == ControlProtocol.Pong && p3.Length == 0, "PONG");
        Expect(!r.TryRead(out _, out _), "nothing left");
    }

    private static void DisplayFrames()
    {
        Bytes(DisplayProtocol.Frame(DisplayProtocol.Hello, "ab"u8), "01 00000002 6162", "HELLO");
        Bytes(DisplayProtocol.Frame(DisplayProtocol.Bye, []), "40 00000000", "BYE");
        Bytes(DisplayProtocol.VideoConfigPayload(1920, 1080, [0, 0, 0, 1, 0x67]), "00 0780 0438 0000000167", "VIDEO_CONFIG");
        Bytes(DisplayProtocol.AudioConfigPayload(48000, 2), "0000BB80 02", "AUDIO_CONFIG");
        var f = DisplayProtocol.VideoFrameFrame(true, 1_000_000, [0, 0, 0, 1, 0x67], [0, 0, 0, 1, 0x65, 0xAA]);
        Bytes(f, "11 00000014 01 00000000000F4240 0000000167 0000000165AA", "VIDEO_FRAME");
        var info = DisplayConnection.ParseWelcome("{\"name\":\"Living room TV\",\"width\":1280,\"height\":720,\"refresh\":50,\"codecs\":[\"hevc\",\"avc\"]}"u8.ToArray(), "x");
        Expect(info is { Name: "Living room TV", Width: 1280, Height: 720, Refresh: 50 } && info.Codecs.Contains("hevc"), "WELCOME parse");
        Expect(DisplayConnection.ParseWelcome([], "TV") is { Width: 1920, Height: 1080, Refresh: 60 }, "WELCOME defaults");
    }

    private static void AnnexBParsing()
    {
        byte[] sps = [0x67, 0x64, 0x00, 0x28], pps = [0x68, 0xEE, 0x3C, 0x80], idr = [0x65, 0x88, 0x84, 0x00, 0x33], aud = [0x09, 0xF0];
        byte[] Start4(byte[] n) => [0, 0, 0, 1, .. n];
        byte[] Start3(byte[] n) => [0, 0, 1, .. n];
        var key = Start4(aud).Concat(Start4(sps)).Concat(Start3(pps)).Concat(Start4(idr)).ToArray();
        var nals = AnnexB.Split(key);
        Expect(nals.Count == 4, $"four NAL units, got {nals.Count}");
        Expect(nals[1].length == sps.Length && nals[2].length == pps.Length, "start codes not counted into NALs");
        Expect(AnnexB.IsKeyframe(key), "IDR is a keyframe");
        Bytes(AnnexB.ParameterSets(key), "00000001 67640028 00000001 68EE3C80", "SPS/PPS");
        var bare = Start4(idr);
        Bytes(AnnexB.ForTv(bare, true, AnnexB.ParameterSets(key)), "00000001 67640028 00000001 68EE3C80 00000001 6588840033", "keyframe gets SPS/PPS");
        Bytes(AnnexB.ForTv(key, true, []), "00000001 67640028 00000001 68EE3C80 00000001 6588840033", "AUD dropped, no duplicates");
        var p = Start4([0x41, 0x9A, 0x00]);
        Expect(!AnnexB.IsKeyframe(p), "P frame is not a keyframe");
    }

    private static void Coalescing()
    {
        int sx = 0, sy = 0, sends = 0;
        using (var c = new MoveCoalescer((x, y) => { sx += x; sy += y; sends++; }))
        {
            var sw = Stopwatch.StartNew();
            var adds = 0;
            while (sw.ElapsedMilliseconds < 250)
            {
                c.Add(1, -2);   // a 1000+ Hz mouse
                adds++;
                Thread.Sleep(0);
                if (adds % 4 == 0) Thread.Sleep(1);
            }
            Thread.Sleep(40);
            c.Flush();
            Expect(sx == adds && sy == -2 * adds, $"nothing lost ({sx},{sy}) vs {adds}");
            Expect(sends <= 250 / 8 + 4, $"at most ~120 sends a second, got {sends} in 250 ms from {adds} moves");
        }
        var big = new List<(int, int)>();
        using (var c = new MoveCoalescer((x, y) => big.Add((x, y))))
        {
            c.Add(40000, 0);
            c.Flush();
            Thread.Sleep(30);
        }
        Expect(big.Sum(b => b.Item1) == 40000 && big.All(b => b.Item1 <= short.MaxValue), "large moves split into s16 frames");
    }

    private static void ScrollPhases()
    {
        var s = new ScrollPhaser();
        var notch = s.Wheel(0, 120, 0);
        Expect(notch.Count == 1 && notch[0] == new ScrollStep(0, 40, ScrollPhase.Wheel), $"wheel notch up → +40 discrete, got {notch[0]}");
        Expect(s.Wheel(0, -240, 500)[0] == new ScrollStep(0, -80, ScrollPhase.Wheel), "two notches down");
        Expect(s.Wheel(120, 0, 700)[0] == new ScrollStep(-40, 0, ScrollPhase.Wheel), "scroll right moves content left");
        var a = s.Wheel(0, 30, 1000)[0];
        var b = s.Wheel(0, 30, 1016)[0];
        var c = s.Wheel(0, 120, 1032)[0];   // a 120 inside a gesture is still part of it
        Expect(a.Phase == ScrollPhase.Began && b.Phase == ScrollPhase.Changed && c.Phase == ScrollPhase.Changed, "touchpad: began, changed");
        Expect(a.Dy + b.Dy + c.Dy == 60, $"touchpad deltas add up (180/3 = 60), got {a.Dy + b.Dy + c.Dy}");
        Expect(s.Tick(1100) is null, "no end while fingers move");
        Expect(s.Tick(1200) == new ScrollStep(0, 0, ScrollPhase.Ended), "ended after a quiet gap");
        Expect(s.Tick(1300) is null, "ended once");
        s.Wheel(0, 10, 2000);
        var stale = s.Wheel(0, 10, 2500);
        Expect(stale.Count == 2 && stale[0].Phase == ScrollPhase.Ended && stale[1].Phase == ScrollPhase.Began, "a gesture nobody ended ends before the next");
    }

    private static void MdnsParsing()
    {
        var q = DnsMessage.PtrQuery(MdnsBrowser.ServiceType, unicastResponse: true);
        Bytes(q[..12], "0000 0000 0001 0000 0000 0000", "query header");
        Expect(q[^4] == 0 && q[^3] == 12 && q[^2] == 0x80 && q[^1] == 1, "PTR, IN, QU bit");

        var packet = BuildAnswer();
        var b = new MdnsBrowser();
        TvDevice? got = null;
        b.Found += tv => got = tv;
        b.Handle(packet, IPAddress.Parse("192.168.2.99"));
        Expect(got is not null, "TV found from PTR+SRV+TXT+A");
        Expect(got!.Name == "Living room TV" && got.Host == "192.168.2.24" && got.Port == 8800 &&
               got.TvId == "d9d4124a3eb5c1f3" && got.Model == "Chromecast HD", $"fields: {got}");
        Expect(DnsMessage.ParseResponse(q).Count == 0, "queries are ignored");
    }

    /// <summary>A response like the TV's: PTR answer, then SRV, TXT and A, with name compression.</summary>
    private static byte[] BuildAnswer()
    {
        var ms = new MemoryStream();
        void U16(int v) { ms.WriteByte((byte)(v >> 8)); ms.WriteByte((byte)v); }
        void U32(uint v) { U16((int)(v >> 16)); U16((int)(v & 0xFFFF)); }
        void Name(params string[] labels)
        {
            foreach (var l in labels) { var x = Encoding.UTF8.GetBytes(l); ms.WriteByte((byte)x.Length); ms.Write(x); }
            ms.WriteByte(0);
        }
        void Rr(Action name, int type, int cls, uint ttl, byte[] rdata) { name(); U16(type); U16(cls); U32(ttl); U16(rdata.Length); ms.Write(rdata); }
        U16(0); U16(0x8400); U16(0); U16(1); U16(0); U16(3);
        var serviceAt = (int)ms.Position;
        // PTR: _tvremote._tcp.local -> Living room TV Remote.<pointer to service>
        var ptrRdata = new MemoryStream();
        var inst = Encoding.UTF8.GetBytes("Living room TV Remote");
        ptrRdata.WriteByte((byte)inst.Length); ptrRdata.Write(inst); ptrRdata.WriteByte((byte)(0xC0 | serviceAt >> 8)); ptrRdata.WriteByte((byte)serviceAt);
        Rr(() => Name("_tvremote", "_tcp", "local"), 12, 1, 4500, ptrRdata.ToArray());
        var instanceAt = (int)ms.Position - (int)ptrRdata.Length;
        void InstName() { ms.WriteByte((byte)(0xC0 | instanceAt >> 8)); ms.WriteByte((byte)instanceAt); }
        var srv = new MemoryStream();
        srv.Write([0, 0, 0, 0, 0x22, 0x60]);   // priority, weight, port 8800
        foreach (var l in new[] { "Android-7", "local" }) { var x = Encoding.UTF8.GetBytes(l); srv.WriteByte((byte)x.Length); srv.Write(x); }
        srv.WriteByte(0);
        Rr(InstName, 33, 0x8001, 120, srv.ToArray());
        var txt = new MemoryStream();
        foreach (var kv in new[] { "name=Living room TV", "id=d9d4124a3eb5c1f3", "model=Chromecast HD" }) { var x = Encoding.UTF8.GetBytes(kv); txt.WriteByte((byte)x.Length); txt.Write(x); }
        Rr(InstName, 16, 0x8001, 4500, txt.ToArray());
        Rr(() => Name("Android-7", "local"), 1, 0x8001, 120, [192, 168, 2, 24]);
        return ms.ToArray();
    }

    private static void AdbParsing()
    {
        const string mac = "List of devices attached\n36221HFGN5P56G         device usb:1-1 product:boreal model:Chromecast_HD device:boreal transport_id:3\n" +
                           "192.168.2.24:5555      device product:boreal model:Chromecast_HD transport_id:4\nemulator-5554 device\nABC123 unauthorized usb:2-1\n";
        Expect(AdbLink.UsbSerials(mac).SequenceEqual(["36221HFGN5P56G"]), "macOS output");
        const string win = "List of devices attached\r\n36221HFGN5P56G\tdevice product:boreal model:Chromecast_HD device:boreal transport_id:1\r\n\r\n";
        Expect(AdbLink.UsbSerials(win).SequenceEqual(["36221HFGN5P56G"]), "Windows output (no usb: field)");
        Expect(AdbLink.UsbSerials("* daemon started successfully\nList of devices attached\n\n").Count == 0, "no devices");
    }

    private static void StoreKeys()
    {
        var s = new TvStore();
        var byIp = new TvDevice { Host = "192.168.2.24" };
        s.SetToken("t1", byIp);
        Expect(s.Token(byIp) == "t1", "token by address");
        var withId = byIp with { TvId = "abc" };
        Expect(s.Token(withId) == "t1", "old address pairing still found once the ID is known");
        s.SetToken("t1", withId);
        Expect(s.Settings.Tokens.Keys.SequenceEqual(["abc"]), "moved under the TV's ID");
        var usb = withId with { Host = "127.0.0.1", Port = 18800, HomeHost = "192.168.2.24", Usb = true };
        Expect(s.Token(usb) == "t1", "USB route uses the same pairing");
        Expect(TvDevice.FromAddress("http://192.168.2.24:8800/") is { Host: "192.168.2.24", Port: 8800 }, "address parsing");
    }

    private static async Task MediaServerRanges()
    {
        var path = Path.Combine(Path.GetTempPath(), $"opentv-test-{Guid.NewGuid():N}.mp4");
        var data = new byte[100_000];
        new Random(1).NextBytes(data);
        await File.WriteAllBytesAsync(path, data);
        try
        {
            using var server = new MediaServer();
            server.Start();
            var shared = server.Share(path);
            Expect(shared.EndsWith(Path.GetFileName(path)) && MediaServer.CanPlayOnTv(path), "URL keeps the extension the TV looks at");
            using var http = new HttpClient { BaseAddress = new Uri($"http://127.0.0.1:{server.Port}") };

            var full = await http.GetByteArrayAsync(shared);
            Expect(full.SequenceEqual(data), "whole file");

            var req = new HttpRequestMessage(HttpMethod.Get, shared);
            req.Headers.Range = new RangeHeaderValue(1000, 1999);
            var part = await http.SendAsync(req);
            var body = await part.Content.ReadAsByteArrayAsync();
            Expect((int)part.StatusCode == 206 && body.SequenceEqual(data[1000..2000]), "bytes=1000-1999");
            Expect(part.Content.Headers.ContentRange?.ToString() == "bytes 1000-1999/100000", "Content-Range");

            var tail = new HttpRequestMessage(HttpMethod.Get, shared);
            tail.Headers.Range = new RangeHeaderValue(null, 500);
            var tb = await (await http.SendAsync(tail)).Content.ReadAsByteArrayAsync();
            Expect(tb.SequenceEqual(data[^500..]), "suffix range");

            var bad = new HttpRequestMessage(HttpMethod.Get, shared);
            bad.Headers.Range = new RangeHeaderValue(200_000, null);
            Expect((int)(await http.SendAsync(bad)).StatusCode == 416, "range past the end");

            var head = await http.SendAsync(new HttpRequestMessage(HttpMethod.Head, shared));
            Expect(head.Content.Headers.ContentLength == data.Length && head.Content.Headers.ContentType?.MediaType == "video/mp4", "HEAD");

            var other = await http.GetAsync("/m/notakey/x.mp4");
            Expect((int)other.StatusCode == 404, "only shared files are served");
            var local = MediaServer.LocalAddressToward("192.168.2.24");
            Expect(local is not null, "local address toward the TV's network");
        }
        finally
        {
            File.Delete(path);
        }
    }

    // ---- live ------------------------------------------------------------------------------------

    private static async Task LiveAsync(string? host, string? token, bool display, bool usb, bool pair)
    {
        TvDevice? tv = null;
        var sw = Stopwatch.StartNew();
        var found = await MdnsBrowser.BrowseAsync(TimeSpan.FromSeconds(4));
        if (found.Count > 0)
        {
            Pass("mDNS discovery (_tvremote._tcp)", string.Join("; ", found.Select(f => $"{f.Name} at {f.Host}:{f.Port} id {f.TvId} model {f.Model}")));
            tv = host is null ? found[0] : found.FirstOrDefault(f => f.Host == host) ?? found[0];
        }
        else if (host is not null)
        {
            Fail("mDNS discovery (_tvremote._tcp)", "nothing answered in 4 s");
        }
        else
        {
            Fail("mDNS discovery (_tvremote._tcp)", "nothing answered in 4 s; pass --host to test the rest");
            return;
        }
        if (host is not null && tv?.Host != host) tv = TvDevice.FromAddress(host);
        if (tv is null) return;

        TvHello hello;
        try
        {
            hello = await new RemoteClient(tv, null).HelloAsync();
            Pass("GET /api/hello", $"name \"{hello.Name}\", id {hello.Id}, paired {hello.Paired}, control {hello.ControlPort}, display {hello.DisplayPort}");
            Expect(!hello.Paired, "an unknown device must not count as paired");
            Expect(hello.ControlPort == 8801 && hello.DisplayPort == 8802, "ports");
            tv = tv with { Name = hello.Name, TvId = hello.Id ?? tv.TvId, ControlPort = hello.ControlPort, DisplayPort = hello.DisplayPort };
        }
        catch (Exception e)
        {
            Fail("GET /api/hello", e.Message);
            return;
        }

        try
        {
            await new RemoteClient(tv, "0000").StateAsync();
            Fail("API refuses a wrong token", "GET /api/state answered without a valid token");
        }
        catch (RemoteException e) when (e.Kind == RemoteErrorKind.NotPaired)
        {
            Pass("API refuses a wrong token (401)");
        }

        await CheckAsync("control channel refuses a wrong token (DENIED)", async () =>
        {
            using var ch = new ControlChannel();
            var denied = new TaskCompletionSource();
            ch.StateChanged += s => { if (s == ChannelState.Denied) denied.TrySetResult(); };
            ch.Connect(tv, "not-a-token");
            Expect(await Task.WhenAny(denied.Task, Task.Delay(5000)) == denied.Task, "no DENIED within 5 s");
        });

        if (pair && token is null) token = await PairAsync(tv);

        if (token is null)
        {
            Skip("paired checks", "no token (set OTV_TOKEN or --token, or run with --pair)");
            return;
        }

        await PairedChecksAsync(tv, token, "Wi-Fi");
        if (display) await DisplayHandshakeAsync(tv, token);
        await ModelAsync(tv, token);
        if (usb) await UsbAsync(token);
    }

    private static async Task PairedChecksAsync(TvDevice tv, string token, string via)
    {
        try
        {
            var h = await new RemoteClient(tv, token).HelloAsync();
            Expect(h.Paired, "the TV doesn't know this token");
            Pass($"[{via}] token accepted (/api/hello paired = true)");
        }
        catch (Exception e)
        {
            Fail($"[{via}] token accepted", e.Message);
            return;
        }

        await CheckAsync($"[{via}] GET /api/state", async () =>
        {
            var np = await new RemoteClient(tv, token).StateAsync();
            Console.WriteLine($"          now playing: {(np is null ? "nothing" : $"\"{np.Title}\" {np.Artist} playing={np.Playing}")}");
        });

        using var ch = new ControlChannel();
        var welcome = new TaskCompletionSource<(string, string?)>();
        var pongs = new List<TimeSpan>();
        var pong = new SemaphoreSlim(0);
        ch.Welcomed += (n, id) => welcome.TrySetResult((n, id));
        ch.Pong += rtt => { lock (pongs) pongs.Add(rtt); pong.Release(); };
        var sw = Stopwatch.StartNew();
        ch.Connect(tv, token);
        if (await Task.WhenAny(welcome.Task, Task.Delay(5000)) != welcome.Task)
        {
            Fail($"[{via}] control channel WELCOME", $"no WELCOME in 5 s (state {ch.State})");
            return;
        }
        var (name, tvId) = welcome.Task.Result;
        Pass($"[{via}] control channel HELLO → WELCOME", $"\"{name}\" id {tvId} in {sw.ElapsedMilliseconds} ms");
        for (var i = 0; i < 5; i++)
        {
            ch.Ping();
            if (!await pong.WaitAsync(3000))
            {
                Fail($"[{via}] PING → PONG", $"no PONG for ping {i + 1}");
                return;
            }
        }
        Pass($"[{via}] PING → PONG ×5", "round trip " + string.Join(", ", pongs.Select(p => $"{p.TotalMilliseconds:0.0}")) + " ms");
        Expect(ch.State == ChannelState.Ready, "still ready");
        ch.Disconnect();
    }

    private static async Task DisplayHandshakeAsync(TvDevice tv, string token)
    {
        try
        {
            using var wrong = await DisplayConnection.ConnectAsync(tv, "not-a-token");
            Fail("display stream refuses a wrong token", "got WELCOME");
        }
        catch (RemoteException e) when (e.Kind == RemoteErrorKind.NotPaired)
        {
            Pass("display stream refuses a wrong token (DENIED)");
        }
        catch (Exception e)
        {
            Fail("display stream refuses a wrong token", e.Message);
        }
        try
        {
            var c = await DisplayConnection.ConnectAsync(tv, token);
            var info = c.Info;
            string? reason = "not ended";
            c.Ended += r => reason = r;
            c.Stop();
            Pass("display stream HELLO → WELCOME → BYE", $"{info.Width}×{info.Height} @ {info.Refresh} Hz, codecs {string.Join("/", info.Codecs)}; ended cleanly: {reason is null}");
        }
        catch (Exception e)
        {
            Fail("display stream HELLO → WELCOME", e.Message);
        }
    }

    private static async Task ModelAsync(TvDevice tv, string token)
    {
        // The app's own state machine, from a cold start that only knows the token and the TV's ID.
        var store = new TvStore();
        store.SetToken(token, tv);
        store.Tv = new TvDevice { Name = "TV", Host = tv.Host, TvId = tv.TvId };
        using var model = new RemoteModel(store, new SynchronizationContext());
        var connected = new TaskCompletionSource();
        model.Changed += () => { if (model.Phase == RemotePhase.Connected) connected.TrySetResult(); };
        model.Activate();
        if (await Task.WhenAny(connected.Task, Task.Delay(6000)) == connected.Task)
        {
            await Task.Delay(300);
            Pass("remote model reaches Connected", $"TV \"{model.Tv?.Name}\", {model.Found.Count} found by mDNS");
        }
        else
        {
            Fail("remote model reaches Connected", $"phase {model.Phase}");
        }
    }

    private static async Task UsbAsync(string token)
    {
        var link = new AdbLink(new AdbLink.Ports(28800, 28801, 28802));
        if (!link.Available)
        {
            Skip("USB (adb)", "adb not found");
            return;
        }
        var serial = AdbLink.UsbSerials(await link.RunAsync(link.AdbPath!, "devices -l")).FirstOrDefault();
        if (serial is null)
        {
            Skip("USB (adb)", "no TV on USB");
            return;
        }
        try
        {
            var tv = await link.ForwardAsync(serial);
            if (tv is null)
            {
                Fail("USB: /api/hello through adb forward", "no answer on 127.0.0.1:28800");
                return;
            }
            Pass("USB: /api/hello through adb forward", $"\"{tv.Name}\" id {tv.TvId}");
            await PairedChecksAsync(tv, token, "USB");
        }
        finally
        {
            await link.RemoveForwardsAsync(serial);
        }
    }

    private static async Task<string?> PairAsync(TvDevice tv)
    {
        Console.WriteLine("  The TV now shows a 4-digit code.");
        await new RemoteClient(tv, null).StartPairingAsync();
        Console.Write("  Code: ");
        var code = Console.ReadLine() ?? "";
        try
        {
            var token = await new RemoteClient(tv, null).FinishPairingAsync(code.Trim());
            Pass("pairing (/api/pair/start → /api/pair/finish)");
            Console.WriteLine($"  token: {token} (keep it in OTV_TOKEN for later runs)");
            return token;
        }
        catch (RemoteException e)
        {
            Fail("pairing", e.Message);
            return null;
        }
    }
}
