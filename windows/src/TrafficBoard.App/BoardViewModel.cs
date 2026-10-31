using CommunityToolkit.Mvvm.ComponentModel;
using System.Collections.ObjectModel;
using TrafficBoard.Core;
namespace TrafficBoard.App;
public sealed class SubscriptionViewModel(Subscription sub, AlertSettings settings, long now, bool refreshing) : ObservableObject
{
    public string Id => sub.Id;
    public string Name => sub.Name;
    public string Host => sub.Host;
    public string UsedTotal => $"{Display.Bytes(sub.Info?.Used)} / {Display.Bytes(sub.Info?.Total)}";
    public string Remaining => "剩余 " + Display.Bytes(sub.Info?.Remaining);
    public string Percent => Display.Percent(sub.Info?.Percent);
    public double Progress => Math.Clamp(sub.Info?.Percent ?? 0, 0, 100);
    public bool Indeterminate => refreshing;
    public string Expiry => Display.Date(sub.Info?.Expire);
    public string Updated => sub.LastSuccessAt == null ? "尚未成功获取" : Display.Date(sub.LastSuccessAt, true);
    public string Status => refreshing ? "正在刷新…" : Display.Status(sub.Status);
    public string Warning => AlertEngine.Warning(sub, settings, now) ?? (sub.Failed && sub.LastSuccessAt != null ? "旧数据 · 刷新失败" : "");
    public bool CanRefresh => !refreshing;
}
public sealed partial class BoardViewModel : ObservableObject
{
    public ObservableCollection<SubscriptionViewModel> Cards { get; } = [];
    [ObservableProperty] public partial string Summary { get; set; } = "正在读取本地数据…";
    [ObservableProperty] public partial string Notice { get; set; } = "";
    [ObservableProperty] public partial bool NoticeOpen { get; set; }
    [ObservableProperty] public partial bool Empty { get; set; } = true;
    public void Update(BoardRepository repo, long now)
    {
        Cards.Clear();
        foreach (var sub in repo.Data.Subscriptions) Cards.Add(new(sub, repo.Data.Settings, now, repo.IsRefreshing(sub.Id)));
        Empty = Cards.Count == 0;
        Summary = $"{Cards.Count} 个订阅  ·  {repo.Data.Subscriptions.Count(s => AlertEngine.Warning(s, repo.Data.Settings, now) != null)} 个需要留意";
        if (repo.StorageError is { } error) ShowNotice(error);
    }
    public void ShowNotice(string text) { Notice = text; NoticeOpen = true; }
}
