using System.Buffers.Binary;
using System.Text;

namespace OpenTV.Core;

/// <summary>Scroll phases on the control channel (POINTER_SCROLL's last byte). Momentum is never sent.</summary>
public enum ScrollPhase : byte
{
    /// <summary>A mouse wheel notch: one step, no gesture around it.</summary>
    Wheel = 0,
    Began = 1,
    Changed = 2,
    Ended = 4,
}

/// <summary>
/// Frames on the live control channel (TCP 8801): <c>u16 BE length, u8 type, payload</c>.
/// Built and parsed here so the app and the protocol test share one implementation.
/// </summary>
public static class ControlProtocol
{
    // PC -> TV
    public const byte Hello = 0x01, Key = 0x02, Text = 0x03, PointerEnter = 0x04, PointerMove = 0x05,
        PointerButton = 0x06, PointerScroll = 0x07, PointerExit = 0x08, Type = 0x0A, Ping = 0x0F;

    // TV -> PC
    public const byte Welcome = 0x81, Denied = 0x82, State = 0x83, PointerLeft = 0x84, Pong = 0x8F;

    public const int MaxPayload = 0xFFFF;

    public static byte[] Frame(byte type, ReadOnlySpan<byte> payload)
    {
        if (payload.Length > MaxPayload) payload = payload[..MaxPayload];
        var f = new byte[3 + payload.Length];
        BinaryPrimitives.WriteUInt16BigEndian(f, (ushort)payload.Length);
        f[2] = type;
        payload.CopyTo(f.AsSpan(3));
        return f;
    }

    public static byte[] Utf8Frame(byte type, string text) => Frame(type, Utf8Prefix(text, MaxPayload));

    public static byte[] HelloFrame(string token) => Utf8Frame(Hello, token);
    public static byte[] KeyFrame(RemoteKey key) => Utf8Frame(Key, key.Wire());
    public static byte[] KeyFrame(string name) => Utf8Frame(Key, name);
    public static byte[] TextFrame(string text) => Utf8Frame(Text, text);
    public static byte[] TypeFrame(string chars) => Utf8Frame(Type, chars);
    public static byte[] PingFrame() => Frame(Ping, []);
    public static byte[] PointerExitFrame() => Frame(PointerExit, []);

    /// <summary>The PC's cursor crossed onto the TV at <paramref name="along"/> (0..1) of <paramref name="edge"/>.</summary>
    public static byte[] PointerEnterFrame(double along, string edge = "bottom")
    {
        var e = Encoding.UTF8.GetBytes(edge);
        var p = new byte[2 + e.Length];
        BinaryPrimitives.WriteUInt16BigEndian(p, U16(along));
        e.CopyTo(p, 2);
        return Frame(PointerEnter, p);
    }

    public static byte[] PointerMoveFrame(int dx, int dy)
    {
        Span<byte> p = stackalloc byte[4];
        BinaryPrimitives.WriteInt16BigEndian(p, S16(dx));
        BinaryPrimitives.WriteInt16BigEndian(p[2..], S16(dy));
        return Frame(PointerMove, p);
    }

    /// <summary>Button 0 is the left (primary) button, 1 the right one (Back on the TV).</summary>
    public static byte[] PointerButtonFrame(int button, bool down) =>
        Frame(PointerButton, [(byte)button, (byte)(down ? 1 : 0)]);

    public static byte[] PointerScrollFrame(int dx, int dy, ScrollPhase phase)
    {
        Span<byte> p = stackalloc byte[5];
        BinaryPrimitives.WriteInt16BigEndian(p, S16(dx));
        BinaryPrimitives.WriteInt16BigEndian(p[2..], S16(dy));
        p[4] = (byte)phase;
        return Frame(PointerScroll, p);
    }

    /// <summary>POINTER_LEFT's payload: where along the edge the cursor left the TV (0..1).</summary>
    public static double ParseAlong(ReadOnlySpan<byte> payload) =>
        payload.Length >= 2 ? BinaryPrimitives.ReadUInt16BigEndian(payload) / 65535.0 : 0.5;

    public static ushort U16(double v) => (ushort)Math.Round(Math.Clamp(v, 0, 1) * 65535);

    public static short S16(int v) => (short)Math.Clamp(v, short.MinValue, short.MaxValue);

    /// <summary>The longest prefix of <paramref name="text"/> that fits in <paramref name="max"/> UTF-8 bytes.</summary>
    public static byte[] Utf8Prefix(string text, int max)
    {
        var b = Encoding.UTF8.GetBytes(text);
        if (b.Length <= max) return b;
        var n = max;
        while (n > 0 && (b[n] & 0xC0) == 0x80) n--;   // don't cut a character in half
        return b[..n];
    }
}

/// <summary>Splits a byte stream into control-channel frames (the TV's side of the protocol, and tests).</summary>
public sealed class ControlFrameReader
{
    private byte[] buffer = new byte[4096];
    private int length;

    public void Feed(ReadOnlySpan<byte> data)
    {
        if (length + data.Length > buffer.Length) Array.Resize(ref buffer, Math.Max(buffer.Length * 2, length + data.Length));
        data.CopyTo(buffer.AsSpan(length));
        length += data.Length;
    }

    public bool TryRead(out byte type, out byte[] payload)
    {
        type = 0;
        payload = [];
        if (length < 3) return false;
        int len = BinaryPrimitives.ReadUInt16BigEndian(buffer);
        if (length < 3 + len) return false;
        type = buffer[2];
        payload = buffer.AsSpan(3, len).ToArray();
        Buffer.BlockCopy(buffer, 3 + len, buffer, 0, length - 3 - len);
        length -= 3 + len;
        return true;
    }
}
