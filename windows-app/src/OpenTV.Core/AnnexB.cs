namespace OpenTV.Core;

/// <summary>
/// H.264 Annex-B helpers for the display stream: Media Foundation's encoders already write start codes, so
/// all that's needed is finding the parameter sets (for VIDEO_CONFIG) and making sure every keyframe carries
/// them in front, which is what the TV's decoder expects.
/// </summary>
public static class AnnexB
{
    public const int NalSlice = 1, NalIdr = 5, NalSei = 6, NalSps = 7, NalPps = 8, NalAud = 9;

    private static readonly byte[] StartCode = [0, 0, 0, 1];

    /// <summary>The NAL units in an Annex-B buffer, without their start codes.</summary>
    public static List<(int offset, int length)> Split(ReadOnlySpan<byte> data)
    {
        var nals = new List<(int, int)>();
        var start = -1;
        var i = 0;
        while (i + 2 < data.Length)
        {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1)
            {
                if (start >= 0) nals.Add((start, TrimZeros(data, start, i) - start));
                i += 3;
                start = i;
                continue;
            }
            i++;
        }
        if (start >= 0 && start < data.Length) nals.Add((start, data.Length - start));
        return nals;
    }

    /// <summary>A 4-byte start code is 00 00 00 01: the zero before "00 00 01" belongs to it, not the NAL.</summary>
    private static int TrimZeros(ReadOnlySpan<byte> data, int start, int end)
    {
        while (end > start && data[end - 1] == 0) end--;
        return end;
    }

    public static int Type(byte header) => header & 0x1F;

    public static bool IsKeyframe(ReadOnlySpan<byte> data)
    {
        foreach (var (o, l) in Split(data))
        {
            if (l > 0 && Type(data[o]) == NalIdr) return true;
        }
        return false;
    }

    /// <summary>SPS and PPS, each with a 4-byte start code, or an empty array when the buffer has none.</summary>
    public static byte[] ParameterSets(ReadOnlySpan<byte> data)
    {
        using var ms = new MemoryStream();
        foreach (var (o, l) in Split(data))
        {
            if (l == 0) continue;
            var t = Type(data[o]);
            if (t is NalSps or NalPps)
            {
                ms.Write(StartCode);
                ms.Write(data.Slice(o, l));
            }
        }
        return ms.ToArray();
    }

    /// <summary>
    /// The frame as it goes to the TV: access unit delimiters dropped, and for a keyframe that came without
    /// them, <paramref name="parameterSets"/> put in front.
    /// </summary>
    public static byte[] ForTv(ReadOnlySpan<byte> data, bool keyframe, ReadOnlySpan<byte> parameterSets)
    {
        var nals = Split(data);
        var hasSps = false;
        foreach (var (o, l) in nals)
        {
            if (l > 0 && Type(data[o]) == NalSps) hasSps = true;
        }
        using var ms = new MemoryStream(data.Length + parameterSets.Length + 8);
        if (keyframe && !hasSps) ms.Write(parameterSets);
        foreach (var (o, l) in nals)
        {
            if (l == 0 || Type(data[o]) == NalAud) continue;
            ms.Write(StartCode);
            ms.Write(data.Slice(o, l));
        }
        return ms.ToArray();
    }
}
