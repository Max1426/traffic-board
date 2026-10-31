using System.Collections.Concurrent;
namespace TrafficBoard.Core;

public interface IClock { long Now { get; } }
public sealed class SystemClock : IClock { public long Now => DateTimeOffset.UtcNow.ToUnixTimeSeconds(); }
public interface IAlertSender { bool Send(Subscription subscription, AlertEvent alert); }
public sealed class BoardRepository(IBoardStore store, ISubscriptionSource source, IAlertSender sender, IClock clock, bool allowLoopback = false)
{
    public const long SixHours = 21600;
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly ConcurrentDictionary<string, SemaphoreSlim> requests = new();
    private readonly HashSet<string> refreshing = [];
    public BoardData Data { get; private set; } = new();
    public string? StorageError { get; private set; }
    public bool Initialized { get; private set; }
    public event Action? Changed;
    public bool IsRefreshing(string id) { lock (refreshing) return refreshing.Contains(id); }
    private void Notify() => Changed?.Invoke();
    public async Task DrainAsync() { await gate.WaitAsync().ConfigureAwait(false); gate.Release(); }
    public async Task InitializeAsync()
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (Initialized) return;
            try { Data = await Task.Run(store.Read).ConfigureAwait(false); }
            catch { StorageError = "无法读取本地加密数据，原文件已保留。请重新打开应用或检查当前 Windows 用户。"; }
            Initialized = true; Notify();
        }
        finally { gate.Release(); }
    }
    private async Task CommitAsync(BoardData next)
    {
        if (StorageError != null) throw new IOException("加密数据不可用，未覆盖原文件");
        await Task.Run(() => store.Write(next)).ConfigureAwait(false);
        Data = next; Notify();
    }
    public async Task<string> SaveSubscriptionAsync(string? id, string name, string url, string ua)
    {
        await InitializeAsync(); name = name.Trim(); url = url.Trim(); ua = ua.Trim();
        if (InputRules.Validate(name, url, ua, allowLoopback) is { } error) throw new ArgumentException(error);
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (Data.Subscriptions.Any(s => s.Url == url && s.Id != id)) throw new ArgumentException("这个订阅链接已经添加过了");
            var old = Data.Subscriptions.FirstOrDefault(s => s.Id == id);
            if (id != null && old == null) throw new ArgumentException("此订阅已被删除");
            var changed = old != null && (old.Url != url || old.UserAgent != ua);
            var sub = old == null || changed ? new Subscription { Id = old?.Id ?? Guid.NewGuid().ToString("N"), Name = name, Url = url, UserAgent = ua, SourceRevision = (old?.SourceRevision ?? -1) + 1 } : old with { Name = name };
            await CommitAsync(Data with { Subscriptions = old == null ? [.. Data.Subscriptions, sub] : Data.Subscriptions.Select(s => s.Id == sub.Id ? sub : s).ToArray() });
            return sub.Id;
        }
        finally { gate.Release(); }
    }
    public async Task DeleteAsync(string id)
    {
        await InitializeAsync(); await gate.WaitAsync().ConfigureAwait(false);
        try { await CommitAsync(Data with { Subscriptions = Data.Subscriptions.Where(s => s.Id != id).ToArray() }); }
        finally { gate.Release(); }
    }
    public async Task SavePreferencesAsync(UiPreferences preferences)
    {
        await InitializeAsync(); await gate.WaitAsync().ConfigureAwait(false);
        try { await CommitAsync(Data with { Preferences = preferences }); }
        finally { gate.Release(); }
    }
    public async Task SaveSettingsAsync(AlertSettings settings)
    {
        var usage = InputRules.Thresholds(string.Join(',', settings.UsageThresholds), true);
        var expiry = InputRules.Thresholds(string.Join(',', settings.ExpiryDays), false);
        if (usage == null || expiry == null) throw new ArgumentException("提醒阈值无效");
        await InitializeAsync(); await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            await CommitAsync(Data with
            {
                Settings = settings with { UsageThresholds = usage, ExpiryDays = expiry },
                Subscriptions = Data.Subscriptions.Select(s => s with { Alerts = s.Alerts with { Usage = s.Alerts.Usage.Intersect(usage).ToHashSet(), Expiry = s.Alerts.Expiry.Intersect(expiry.Append(0)).ToHashSet() } }).ToArray()
            });
        }
        finally { gate.Release(); }
    }
    public async Task RefreshAllAsync(bool onlyStale = false, CancellationToken cancellationToken = default)
    {
        await InitializeAsync();
        if (StorageError != null) return;
        var ids = Data.Subscriptions.Where(s => !onlyStale || s.LastSuccessAt == null || clock.Now - s.LastSuccessAt >= SixHours || s.LastSuccessAt > clock.Now).Select(s => s.Id).ToArray();
        foreach (var id in ids) { cancellationToken.ThrowIfCancellationRequested(); await RefreshAsync(id, cancellationToken).ConfigureAwait(false); }
    }
    public async Task RefreshAsync(string id, CancellationToken cancellationToken = default)
    {
        var requestGate = requests.GetOrAdd(id, _ => new(1, 1));
        await requestGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await InitializeAsync();
            if (StorageError != null) return;
            var target = Data.Subscriptions.FirstOrDefault(s => s.Id == id);
            if (target == null) return;
            lock (refreshing) refreshing.Add(id); Notify();
            try
            {
                var result = await source.FetchAsync(target.Url, target.UserAgent, cancellationToken).ConfigureAwait(false);
                cancellationToken.ThrowIfCancellationRequested();
                await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
                try
                {
                    var current = Data.Subscriptions.FirstOrDefault(s => s.Id == id);
                    if (current == null || current.SourceRevision != target.SourceRevision || current.Url != target.Url || current.UserAgent != target.UserAgent) return;
                    var updated = result.Error is { } failure ? current with { Status = failure, LastAttemptAt = clock.Now } : current with { Info = result.Info, LastSuccessAt = clock.Now, LastAttemptAt = clock.Now, Status = result.Info == null ? RefreshStatus.NoInfo : result.Info.Complete ? RefreshStatus.Ready : RefreshStatus.Partial };
                    await CommitAsync(Data with { Subscriptions = Data.Subscriptions.Select(s => s.Id == id ? updated : s).ToArray() });
                    if (result.Error == null && result.Info != null)
                    {
                        var evaluation = AlertEngine.Evaluate(updated, Data.Settings, clock.Now);
                        var memory = evaluation.Memory;
                        foreach (var alert in evaluation.Events) if (sender.Send(updated, alert)) memory = AlertEngine.Mark(memory, alert);
                        updated = updated with { Alerts = memory };
                        await CommitAsync(Data with { Subscriptions = Data.Subscriptions.Select(s => s.Id == id ? updated : s).ToArray() });
                    }
                }
                finally { gate.Release(); }
            }
            finally { lock (refreshing) refreshing.Remove(id); Notify(); }
        }
        finally { requestGate.Release(); }
    }
}

/// <summary>One foreground process; ticks and resume events coalesce. Exiting cancels requests.</summary>
public sealed class RefreshScheduler(BoardRepository repository, IClock clock) : IAsyncDisposable
{
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim checking = new(1, 1);
    private Task? loop;
    private long? lastAutomaticAttempt;
    public event Action<string>? Error;
    public void Start() => loop ??= RunAsync();
    private async Task RunAsync()
    {
        await CheckAsync();
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1));
        try { while (await timer.WaitForNextTickAsync(lifetime.Token)) await CheckAsync(); }
        catch (OperationCanceledException) { }
    }
    public async Task CheckAsync(bool resume = false)
    {
        if (!await checking.WaitAsync(0)) return;
        try
        {
            if (lastAutomaticAttempt == null || resume || clock.Now - lastAutomaticAttempt >= BoardRepository.SixHours || clock.Now < lastAutomaticAttempt)
            {
                lastAutomaticAttempt = clock.Now;
                await repository.RefreshAllAsync(true, lifetime.Token);
            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
        catch { Error?.Invoke("自动刷新结果无法保存，请检查存储空间后手动重试"); }
        finally { checking.Release(); }
    }
    public CancellationToken Token => lifetime.Token;
    public async ValueTask DisposeAsync()
    {
        await lifetime.CancelAsync();
        if (loop != null) await loop;
        await checking.WaitAsync(); checking.Release();
    }
}
