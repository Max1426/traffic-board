using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;
namespace TrafficBoard.App;
public partial class App : Application
{
    private MainWindow? window;
    public App() { InitializeComponent(); }
    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        if (Environment.GetCommandLineArgs().Contains("--unregister"))
        {
            try { Microsoft.Windows.AppNotifications.AppNotificationManager.Default.UnregisterAll(); } catch { }
            Exit(); return;
        }
        var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
        var instance = AppInstance.FindOrRegisterForKey("Max1426.TrafficBoard.Windows");
        if (!instance.IsCurrent) { await instance.RedirectActivationToAsync(activation); Exit(); return; }
        window = new MainWindow();
        instance.Activated += (_, e) => window.DispatcherQueue.TryEnqueue(() => { window.Activate(); window.HandleActivation(e); });
        window.Activate(); window.HandleActivation(activation);
    }
}
