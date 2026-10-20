using System.Runtime.InteropServices;
using OpenTV.Windows.Pointer;
using static OpenTV.Windows.Native.Win32;

namespace OpenTV.Windows.Display;

/// <summary>
/// The TV remote driving the PC while the TV shows its screen: arrows, OK (Enter), Back (Esc), play/pause and
/// track keys become the matching Windows keys; Menu opens Task View (Win+Tab) for switching apps.
/// </summary>
internal static class InputInjector
{
    public static void RemoteKey(string name)
    {
        switch (name)
        {
            case "up": Key(VK_UP, true); break;
            case "down": Key(VK_DOWN, true); break;
            case "left": Key(VK_LEFT, true); break;
            case "right": Key(VK_RIGHT, true); break;
            case "select": Key(VK_RETURN); break;
            case "back": Key(VK_ESCAPE); break;
            case "playpause": Key(VK_MEDIA_PLAY_PAUSE, true); break;
            case "next": Key(VK_MEDIA_NEXT_TRACK, true); break;
            case "previous": Key(VK_MEDIA_PREV_TRACK, true); break;
            case "menu": Chord(VK_LWIN, VK_TAB); break;
        }
    }

    private static INPUT Input(int vk, bool up, bool extended) => new()
    {
        type = INPUT_KEYBOARD,
        ki = new KEYBDINPUT
        {
            wVk = (ushort)vk,
            dwFlags = (up ? KEYEVENTF_KEYUP : 0) | (extended ? KEYEVENTF_EXTENDEDKEY : 0),
            dwExtraInfo = PointerBridge.InjectedMarker,
        },
    };

    private static void Key(int vk, bool extended = false) =>
        SendInput(2, [Input(vk, false, extended), Input(vk, true, extended)], Marshal.SizeOf<INPUT>());

    private static void Chord(int modifier, int vk) =>
        SendInput(4, [Input(modifier, false, true), Input(vk, false, false), Input(vk, true, false), Input(modifier, true, true)], Marshal.SizeOf<INPUT>());
}
