namespace OpenAutoLink.Core.Session;

public enum SessionState
{
    Idle,
    Discovering,
    Connecting,
    NativeReady,
    Streaming,
    Error,
}
