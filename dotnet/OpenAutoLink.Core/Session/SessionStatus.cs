namespace OpenAutoLink.Core.Session;

public sealed record SessionStatus(SessionState State, string Message)
{
    public static SessionStatus Idle(string message = "Ready") => new(SessionState.Idle, message);
}
