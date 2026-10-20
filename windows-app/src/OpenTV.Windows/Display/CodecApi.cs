using System.Runtime.InteropServices;

namespace OpenTV.Windows.Display;

/// <summary>ICodecAPI (strmif.h): how Media Foundation encoders take settings such as low-latency mode.</summary>
[ComImport, Guid("901db4c7-31ce-41a2-85dc-8fa0bf41b8da"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface ICodecAPI
{
    [PreserveSig] int IsSupported(ref Guid api);
    [PreserveSig] int IsModifiable(ref Guid api);
    [PreserveSig] int GetParameterRange(ref Guid api, out object valueMin, out object valueMax, out object steppingDelta);
    [PreserveSig] int GetParameterValues(ref Guid api, out IntPtr values, out uint valuesCount);
    [PreserveSig] int GetDefaultValue(ref Guid api, out object value);
    [PreserveSig] int GetValue(ref Guid api, out object value);
    [PreserveSig] int SetValue(ref Guid api, ref object value);
}

/// <summary>The codecapi.h settings used here (GUIDs checked against the published header).</summary>
internal static class CodecApi
{
    public static readonly Guid AVLowLatencyMode = new("9c27891a-ed7a-40e1-88e8-b22727a024ee");
    public static readonly Guid AVEncCommonRateControlMode = new("1c0608e9-370c-4710-8a58-cb6181c42423");
    public static readonly Guid AVEncCommonMeanBitRate = new("f7222374-2144-4815-b550-a37f8e12ee52");
    public static readonly Guid AVEncCommonMaxBitRate = new("9651eae4-39b9-4ebf-85ef-d7f444ec7465");
    public static readonly Guid AVEncCommonBufferSize = new("0db96574-b6a4-4c8b-8106-3773de0310cd");
    public static readonly Guid AVEncCommonQualityVsSpeed = new("98332df8-03cd-476b-89fa-3f9e442dec9f");
    public static readonly Guid AVEncMPVGOPSize = new("95f31b26-95a4-41aa-9303-246a7fc6eef1");
    public static readonly Guid AVEncMPVDefaultBPictureCount = new("8d390aac-dc5c-4200-b57f-814d04babab2");
    public static readonly Guid AVEncVideoForceKeyFrame = new("398c1b98-8353-475a-9ef2-8f265d260345");

    public const uint RateControlCbr = 0, RateControlPeakConstrainedVbr = 1, RateControlLowDelayVbr = 4;

    /// <summary>Sets one value; returns whether the encoder took it (not every encoder supports every setting).</summary>
    public static bool Set(ICodecAPI? api, Guid key, object value)
    {
        if (api is null) return false;
        try
        {
            var k = key;
            var v = value;
            return api.SetValue(ref k, ref v) >= 0;
        }
        catch (Exception)
        {
            return false;
        }
    }
}
