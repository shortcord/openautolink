using System.Net.Sockets;

namespace OpenAutoLink.Core.Transport;

public sealed class TcpConnector
{
    public async Task<Socket> ConnectAsync(string host, int port, CancellationToken cancellationToken)
    {
        var socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
        using var registration = cancellationToken.Register(() => socket.Dispose());
        await socket.ConnectAsync(host, port, cancellationToken);
        return socket;
    }
}
