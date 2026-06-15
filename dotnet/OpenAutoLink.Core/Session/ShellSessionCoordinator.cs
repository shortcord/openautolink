using OpenAutoLink.Core.Diagnostics;
using OpenAutoLink.Core.Transport;

namespace OpenAutoLink.Core.Session;

public sealed class ShellSessionCoordinator
{
    private readonly PhoneDiscoveryService _discoveryService;
    private readonly TcpConnector _tcpConnector;

    public ShellSessionCoordinator(PhoneDiscoveryService discoveryService, TcpConnector tcpConnector)
    {
        _discoveryService = discoveryService;
        _tcpConnector = tcpConnector;
        Status = SessionStatus.Idle();
    }

    public SessionStatus Status { get; private set; }

    public event EventHandler<SessionStatus>? StatusChanged;

    public async Task StartAsync(bool nativeLoaded, CancellationToken cancellationToken)
    {
        UpdateStatus(SessionState.Discovering, "Discovering companion...");
        var phone = await _discoveryService.DiscoverOnceAsync(cancellationToken);
        if (phone is null)
        {
            UpdateStatus(SessionState.Error, "Discovery scaffold only: no companion implementation yet.");
            return;
        }

        UpdateStatus(SessionState.Connecting, $"Connecting to {phone.Host}:{phone.Port}...");
        using var socket = await _tcpConnector.ConnectAsync(phone.Host, phone.Port, cancellationToken);
        UpdateStatus(nativeLoaded ? SessionState.NativeReady : SessionState.Error,
            nativeLoaded ? "Connected. Native shim can be started next." : "Connected, but native shim is not packaged yet.");
        OalLogger.Info($"Socket connected to {socket.RemoteEndPoint}");
    }

    public void Reset() => UpdateStatus(SessionState.Idle, "Ready");

    private void UpdateStatus(SessionState state, string message)
    {
        Status = new SessionStatus(state, message);
        StatusChanged?.Invoke(this, Status);
    }
}
