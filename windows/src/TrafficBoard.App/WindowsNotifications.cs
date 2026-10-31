using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using TrafficBoard.Core;
namespace TrafficBoard.App;
public sealed class WindowsNotifications : IAlertSender, IDisposable
{
    private bool registered;
    public event Action<string>? Invoked;
    public WindowsNotifications()
    {
        try
        {
            if (!AppNotificationManager.IsSupported()) return;
            AppNotificationManager.Default.NotificationInvoked += OnInvoked;
            AppNotificationManager.Default.Register(); registered = true;
        }
        catch { registered = false; }
    }
    private void OnInvoked(AppNotificationManager sender, AppNotificationActivatedEventArgs e)
    {
        if (e.Arguments.TryGetValue("subscription", out var id)) Invoked?.Invoke(id);
    }
    public bool Enabled
    {
        get { try { return registered && AppNotificationManager.Default.Setting == AppNotificationSetting.Enabled; } catch { return false; } }
    }
    public bool Send(Subscription sub, AlertEvent alert)
    {
        if (!Enabled) return false;
        try
        {
            var notification = new AppNotificationBuilder().AddArgument("subscription", sub.Id)
                .AddText(sub.Name + (alert.Kind == AlertKind.Usage ? " · 流量提醒" : " · 到期提醒")).AddText(alert.Message).BuildNotification();
            notification.Tag = (alert.Kind == AlertKind.Usage ? "u" : "e") + sub.Id[..Math.Min(12, sub.Id.Length)];
            notification.Group = "traffic-board";
            AppNotificationManager.Default.Show(notification); return notification.Id != 0;
        }
        catch { return false; }
    }
    public void Dispose()
    {
        if (!registered) return;
        AppNotificationManager.Default.NotificationInvoked -= OnInvoked;
        try { AppNotificationManager.Default.Unregister(); } catch { }
        registered = false;
    }
}
