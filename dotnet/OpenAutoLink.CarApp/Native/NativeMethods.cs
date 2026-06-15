using System.Runtime.InteropServices;

namespace OpenAutoLink.CarApp.Native;

internal static class NativeMethods
{
    private const string LibraryName = "oal_shim";

    public static bool TryLoad()
    {
        try
        {
            return NativeLibrary.TryLoad(LibraryName, typeof(NativeMethods).Assembly, null, out _);
        }
        catch
        {
            return false;
        }
    }

    [DllImport(LibraryName, EntryPoint = "oal_shim_version")]
    public static extern IntPtr GetVersion();
}
