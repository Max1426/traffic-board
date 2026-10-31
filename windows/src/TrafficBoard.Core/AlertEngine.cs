namespace TrafficBoard.Core;
public enum AlertKind { Usage, Expiry }
public sealed record AlertEvent(AlertKind Kind, int Threshold, HashSet<int> Reached, string Message);
public sealed record AlertEvaluation(AlertMemory Memory, AlertEvent[] Events);
public static class AlertEngine
{
    public static AlertEvaluation Evaluate(Subscription sub, AlertSettings settings, long now)
    {
        if (sub.Info is not { } info) return new(sub.Alerts, []);
        var usage = info.Percent is { } p ? settings.UsageThresholds.Where(t => p >= t).ToHashSet() : null;
        var expiry = info.Expire is { } e ? settings.ExpiryDays.Append(0).Where(d => e - now <= d * 86400L).ToHashSet() : null;
        var memory = new AlertMemory
        {
            Usage = usage == null ? [.. sub.Alerts.Usage] : sub.Alerts.Usage.Intersect(usage).ToHashSet(),
            Expiry = info.Expire != sub.Alerts.ExpireIdentity ? [] : expiry == null ? [.. sub.Alerts.Expiry] : sub.Alerts.Expiry.Intersect(expiry).ToHashSet(),
            ExpireIdentity = info.Expire
        };
        var events = new List<AlertEvent>();
        if (settings.UsageEnabled && usage != null && usage.Except(memory.Usage).OrderDescending().Cast<int?>().FirstOrDefault() is { } next)
            events.Add(new(AlertKind.Usage, next, usage, $"已使用 {Display.Percent(info.Percent)}，剩余 {Display.Bytes(info.Remaining)}"));
        if (settings.ExpiryEnabled && expiry != null && expiry.Except(memory.Expiry).Order().Cast<int?>().FirstOrDefault() is { } days)
            events.Add(new(AlertKind.Expiry, days, expiry, days == 0 ? "订阅已到期，请及时检查或续费" : $"订阅将在 {days} 天内到期（{Display.Date(info.Expire)}）"));
        return new(memory, [.. events]);
    }
    public static AlertMemory Mark(AlertMemory memory, AlertEvent e) => e.Kind == AlertKind.Usage ? memory with { Usage = memory.Usage.Union(e.Reached).ToHashSet() } : memory with { Expiry = memory.Expiry.Union(e.Reached).ToHashSet() };
    public static string? Warning(Subscription sub, AlertSettings settings, long now)
    {
        if (settings.ExpiryEnabled && sub.Info?.Expire is { } expire)
        {
            if (expire <= now) return "已到期";
            if (settings.ExpiryDays.Any(d => expire - now <= d * 86400L)) return "即将到期";
        }
        return settings.UsageEnabled && sub.Info?.Percent is { } p && settings.UsageThresholds.Any(t => p >= t) ? "流量不足" : null;
    }
}
