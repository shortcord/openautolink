using Android.App;

namespace OpenAutoLink.CarApp;

public sealed class OalApplication : Application
{
    public OalApplication(IntPtr handle, Android.Runtime.JniHandleOwnership ownership) : base(handle, ownership)
    {
    }
}
