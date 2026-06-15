using _Microsoft.Android.Resource.Designer;
using Android;
using Android.App;
using Android.Content.PM;
using Android.OS;
using Android.Views;
using Android.Widget;
using OpenAutoLink.CarApp.Native;
using OpenAutoLink.Core.Diagnostics;
using OpenAutoLink.Core.Session;
using OpenAutoLink.Core.Transport;
using ShellSessionStatus = OpenAutoLink.Core.Session.SessionStatus;

namespace OpenAutoLink.CarApp;

public sealed class MainActivity : Activity
{
    private readonly ShellSessionCoordinator _coordinator = new(new PhoneDiscoveryService(), new TcpConnector());
    private TextView? _statusView;
    private bool _nativeLoaded;
    private CancellationTokenSource? _connectCts;

    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);
        Window?.AddFlags(WindowManagerFlags.KeepScreenOn);
        SetContentView(ResourceConstant.Layout.Main);

        _statusView = FindViewById<TextView>(ResourceConstant.Id.statusText);
        var connectButton = FindViewById<Button>(ResourceConstant.Id.connectButton);
        var resetButton = FindViewById<Button>(ResourceConstant.Id.resetButton);

        _coordinator.StatusChanged += (_, status) => RunOnUiThread(() => UpdateStatus(status));
        _nativeLoaded = NativeMethods.TryLoad();
        UpdateStatus(new ShellSessionStatus(SessionState.Idle,
            _nativeLoaded ? "Ready. Native shim detected." : "Ready. Native shim not packaged yet."));

        connectButton?.SetOnClickListener(new ClickListener(async () => await StartSessionAsync()));
        resetButton?.SetOnClickListener(new ClickListener(ResetSession));

        RequestMissingPermissions();
        OalLogger.Info("AAOS shell activity created");
    }

    protected override void OnDestroy()
    {
        _connectCts?.Cancel();
        _connectCts?.Dispose();
        base.OnDestroy();
    }

    private async Task StartSessionAsync()
    {
        if (_connectCts is not null)
            await _connectCts.CancelAsync();

        _connectCts?.Dispose();
        _connectCts = new CancellationTokenSource();

        try
        {
            await _coordinator.StartAsync(_nativeLoaded, _connectCts.Token);
        }
        catch (System.OperationCanceledException)
        {
            UpdateStatus(new ShellSessionStatus(SessionState.Idle, "Cancelled"));
        }
        catch (Exception ex)
        {
            UpdateStatus(new ShellSessionStatus(SessionState.Error, ex.Message));
            OalLogger.Error($"StartSession failed: {ex}");
        }
    }

    private void ResetSession()
    {
        _connectCts?.Cancel();
        _coordinator.Reset();
    }

    private void UpdateStatus(ShellSessionStatus status)
    {
        if (_statusView is not null)
        {
            _statusView.Text = status.Message;
        }
    }

    private void RequestMissingPermissions()
    {
        var requiredPermissions = new List<string>
        {
            Manifest.Permission.RecordAudio,
            Manifest.Permission.AccessFineLocation,
            Manifest.Permission.AccessCoarseLocation,
            Manifest.Permission.BluetoothConnect,
            Manifest.Permission.BluetoothScan,
        };

        if (OperatingSystem.IsAndroidVersionAtLeast(33))
        {
            requiredPermissions.Add(Manifest.Permission.PostNotifications);
        }

        var missing = requiredPermissions
            .Where(permission => CheckSelfPermission(permission) != Permission.Granted)
            .ToArray();

        if (missing.Length > 0)
        {
            RequestPermissions(missing, 1001);
        }
    }

    private sealed class ClickListener : Java.Lang.Object, View.IOnClickListener
    {
        private readonly Action _action;

        public ClickListener(Action action)
        {
            _action = action;
        }

        public void OnClick(View? v) => _action();
    }
}
