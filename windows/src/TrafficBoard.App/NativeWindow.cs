using System.Runtime.InteropServices;
namespace TrafficBoard.App;
internal sealed class NativeWindow : IDisposable
{
    private readonly nint hwnd;
    private readonly SubclassProc callback;
    private readonly Action resume;
    private readonly Action settingsChanged;
    public NativeWindow(nint hwnd, Action resume, Action settingsChanged)
    {
        this.hwnd = hwnd; this.resume = resume; this.settingsChanged = settingsChanged; callback = WindowProc;
        SetWindowSubclass(hwnd, callback, 1, 0);
    }
    private nint WindowProc(nint window, uint message, nuint wParam, nint lParam, nuint id, nuint data)
    {
        if (message == 0x218 && (wParam == 0x12 || wParam == 0x7)) resume();
        if (message is 0x1A or 0x31A or 0x320) settingsChanged();
        if (message == 0x24)
        {
            var minmax = Marshal.PtrToStructure<MinMaxInfo>(lParam);
            var scale = GetDpiForWindow(window) / 96.0;
            minmax.MinTrackSize = new Point((int)(760 * scale), (int)(580 * scale));
            Marshal.StructureToPtr(minmax, lParam, false); return 0;
        }
        return DefSubclassProc(window, message, wParam, lParam);
    }
    public void Dispose() => RemoveWindowSubclass(hwnd, callback, 1);
    public static bool HighContrast
    {
        get { var value = new HighContrastInfo { Size = (uint)Marshal.SizeOf<HighContrastInfo>() }; return SystemParametersInfo(0x42, value.Size, ref value, 0) && (value.Flags & 1) != 0; }
    }
    [StructLayout(LayoutKind.Sequential)] private struct HighContrastInfo { public uint Size, Flags; public nint DefaultScheme; }
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool SystemParametersInfo(uint action, uint param, ref HighContrastInfo info, uint flags);
    [StructLayout(LayoutKind.Sequential)] private struct Point(int x, int y) { public int X = x, Y = y; }
    [StructLayout(LayoutKind.Sequential)] private struct MinMaxInfo { public Point Reserved, MaxSize, MaxPosition, MinTrackSize, MaxTrackSize; }
    private delegate nint SubclassProc(nint hwnd, uint message, nuint wParam, nint lParam, nuint id, nuint data);
    [DllImport("comctl32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool SetWindowSubclass(nint hwnd, SubclassProc proc, nuint id, nuint data);
    [DllImport("comctl32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc proc, nuint id);
    [DllImport("comctl32.dll")] private static extern nint DefSubclassProc(nint hwnd, uint message, nuint wParam, nint lParam);
    [DllImport("user32.dll")] internal static extern uint GetDpiForWindow(nint hwnd);
}
