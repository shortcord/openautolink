namespace OpenAutoLink.Core.Transport;

public sealed class PhoneDiscoveryService
{
    public Task<DiscoveredPhone?> DiscoverOnceAsync(CancellationToken cancellationToken)
    {
        return Task.FromResult<DiscoveredPhone?>(null);
    }
}
