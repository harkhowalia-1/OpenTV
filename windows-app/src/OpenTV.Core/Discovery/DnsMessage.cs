using System.Buffers.Binary;
using System.Net;
using System.Text;

namespace OpenTV.Core.Discovery;

/// <summary>One resource record from an mDNS answer (only the kinds DNS-SD needs are decoded).</summary>
public sealed record DnsRecord(string Name, ushort Type, uint Ttl)
{
    public string? Target { get; init; }                 // PTR, SRV
    public ushort Port { get; init; }                    // SRV
    public IPAddress? Address { get; init; }             // A
    public IReadOnlyDictionary<string, string>? Txt { get; init; }   // TXT
}

/// <summary>
/// The small part of the DNS wire format (RFC 1035, with mDNS's cache-flush and unicast-response bits)
/// that browsing for one service type needs: a PTR question out, PTR/SRV/TXT/A records back.
/// </summary>
public static class DnsMessage
{
    public const ushort TypeA = 1, TypePtr = 12, TypeTxt = 16, TypeAaaa = 28, TypeSrv = 33, TypeAny = 255;

    /// <summary>A query for <paramref name="name"/>'s PTR records, asking for unicast replies (QU).</summary>
    public static byte[] PtrQuery(string name, bool unicastResponse, ushort id = 0)
    {
        using var ms = new MemoryStream();
        Span<byte> h = stackalloc byte[12];
        BinaryPrimitives.WriteUInt16BigEndian(h, id);
        BinaryPrimitives.WriteUInt16BigEndian(h[4..], 1);   // one question
        ms.Write(h);
        WriteName(ms, name);
        Span<byte> q = stackalloc byte[4];
        BinaryPrimitives.WriteUInt16BigEndian(q, TypePtr);
        BinaryPrimitives.WriteUInt16BigEndian(q[2..], (ushort)(unicastResponse ? 0x8001 : 0x0001));
        ms.Write(q);
        return ms.ToArray();
    }

    private static void WriteName(Stream s, string name)
    {
        foreach (var label in name.TrimEnd('.').Split('.'))
        {
            var b = Encoding.UTF8.GetBytes(label);
            s.WriteByte((byte)b.Length);
            s.Write(b);
        }
        s.WriteByte(0);
    }

    /// <summary>All answer, authority and additional records of a response; empty for queries or junk.</summary>
    public static List<DnsRecord> ParseResponse(ReadOnlySpan<byte> msg)
    {
        var records = new List<DnsRecord>();
        if (msg.Length < 12) return records;
        var flags = BinaryPrimitives.ReadUInt16BigEndian(msg[2..]);
        if ((flags & 0x8000) == 0) return records;   // a question from someone else
        int qd = BinaryPrimitives.ReadUInt16BigEndian(msg[4..]);
        int total = BinaryPrimitives.ReadUInt16BigEndian(msg[6..]) + BinaryPrimitives.ReadUInt16BigEndian(msg[8..]) +
                    BinaryPrimitives.ReadUInt16BigEndian(msg[10..]);
        var pos = 12;
        try
        {
            for (var i = 0; i < qd; i++)
            {
                ReadName(msg, ref pos);
                pos += 4;
            }
            for (var i = 0; i < total && pos + 10 <= msg.Length; i++)
            {
                var name = ReadName(msg, ref pos);
                var type = BinaryPrimitives.ReadUInt16BigEndian(msg[pos..]);
                var ttl = BinaryPrimitives.ReadUInt32BigEndian(msg[(pos + 4)..]);
                int rdlen = BinaryPrimitives.ReadUInt16BigEndian(msg[(pos + 8)..]);
                pos += 10;
                if (pos + rdlen > msg.Length) break;
                var rd = pos;
                var r = new DnsRecord(name, type, ttl);
                switch (type)
                {
                    case TypePtr:
                        r = r with { Target = ReadName(msg, ref rd) };
                        break;
                    case TypeSrv when rdlen >= 7:
                        var port = BinaryPrimitives.ReadUInt16BigEndian(msg[(pos + 4)..]);
                        rd = pos + 6;
                        r = r with { Port = port, Target = ReadName(msg, ref rd) };
                        break;
                    case TypeA when rdlen == 4:
                        r = r with { Address = new IPAddress(msg.Slice(pos, 4)) };
                        break;
                    case TypeAaaa when rdlen == 16:
                        r = r with { Address = new IPAddress(msg.Slice(pos, 16)) };
                        break;
                    case TypeTxt:
                        r = r with { Txt = ParseTxt(msg.Slice(pos, rdlen)) };
                        break;
                }
                records.Add(r);
                pos += rdlen;
            }
        }
        catch (Exception e) when (e is IndexOutOfRangeException or ArgumentOutOfRangeException or FormatException)
        {
            // A truncated or malformed packet: keep what was readable.
        }
        return records;
    }

    private static Dictionary<string, string> ParseTxt(ReadOnlySpan<byte> rd)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var i = 0;
        while (i < rd.Length)
        {
            int n = rd[i++];
            if (i + n > rd.Length) break;
            var s = Encoding.UTF8.GetString(rd.Slice(i, n));
            i += n;
            var eq = s.IndexOf('=');
            if (eq > 0) d[s[..eq]] = s[(eq + 1)..];
            else if (s.Length > 0) d[s] = "";
        }
        return d;
    }

    /// <summary>Reads a possibly compressed name starting at <paramref name="pos"/> and moves past it.</summary>
    public static string ReadName(ReadOnlySpan<byte> msg, ref int pos)
    {
        var sb = new StringBuilder();
        var p = pos;
        var jumped = false;
        for (var hops = 0; hops < 64; hops++)
        {
            int len = msg[p];
            if (len == 0)
            {
                if (!jumped) pos = p + 1;
                return sb.ToString();
            }
            if ((len & 0xC0) == 0xC0)
            {
                var target = (len & 0x3F) << 8 | msg[p + 1];
                if (!jumped) pos = p + 2;
                jumped = true;
                p = target;
                continue;
            }
            if (sb.Length > 0) sb.Append('.');
            sb.Append(Encoding.UTF8.GetString(msg.Slice(p + 1, len)));
            p += 1 + len;
        }
        throw new FormatException("name loop");
    }
}
