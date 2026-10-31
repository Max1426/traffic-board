using System.Globalization;
using System.Text.RegularExpressions;

namespace TrafficBoard.Core;

public sealed record TrafficInfo(long? Upload = null, long? Download = null, long? Total = null, long? Expire = null)
{
    public long? Used => Upload is { } u && Download is { } d && u <= long.MaxValue - d ? u + d : null;
    public long? Limit => Total is > 0 ? Total : null;
    public long? Remaining => Used is { } u && Limit is { } t ? Math.Max(0, t - u) : null;
    public double? Percent => Used is { } u && Limit is { } t ? (double)u / t * 100 : null;
    public bool HasAny => Upload != null || Download != null || Total != null || Expire != null;
    public bool Complete => Used != null && Limit != null && Expire != null;
}
public enum RefreshStatus { Never, Ready, Partial, NoInfo, NetworkError, Timeout, InvalidLink, ServerError }
public enum ThemeMode { System, Light, Dark }
public sealed record AlertMemory
{
    public HashSet<int> Usage { get; init; } = [];
    public HashSet<int> Expiry { get; init; } = [];
    public long? ExpireIdentity { get; init; }
}
public sealed record Subscription
{
    public const string DefaultUserAgent = "clash-traffic-board/1.0";
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
    public string Name { get; init; } = "";
    public string Url { get; init; } = "";
    public string UserAgent { get; init; } = DefaultUserAgent;
    public TrafficInfo? Info { get; init; }
    public long? LastSuccessAt { get; init; }
    public long? LastAttemptAt { get; init; }
    public RefreshStatus Status { get; init; }
    public AlertMemory Alerts { get; init; } = new();
    public long SourceRevision { get; init; }
    public string Host => Uri.TryCreate(Url, UriKind.Absolute, out var uri) ? uri.IdnHost : "未知来源";
    public bool Failed => Status is RefreshStatus.NetworkError or RefreshStatus.Timeout or RefreshStatus.InvalidLink or RefreshStatus.ServerError;
}
public sealed record AlertSettings
{
    public bool UsageEnabled { get; init; } = true;
    public bool ExpiryEnabled { get; init; } = true;
    public int[] UsageThresholds { get; init; } = [80, 95];
    public int[] ExpiryDays { get; init; } = [7, 3];
}
public sealed record UiPreferences(ThemeMode Theme = ThemeMode.System);
public sealed record BoardData
{
    public int Version { get; init; } = 1;
    public Subscription[] Subscriptions { get; init; } = [];
    public AlertSettings Settings { get; init; } = new();
    public UiPreferences Preferences { get; init; } = new();
}
public static partial class HeaderParser
{
    public static TrafficInfo? Parse(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return null;
        var fields = header.Split(';').Select(p => p.Split('=', 2)).Where(p => p.Length == 2)
            .GroupBy(p => p[0].Trim(), StringComparer.OrdinalIgnoreCase).ToDictionary(g => g.Key, g => g.Select(p => p[1].Trim()).ToArray(), StringComparer.OrdinalIgnoreCase);
        long? Number(string key) => fields.TryGetValue(key, out var values) && values.Length == 1 && Digits().IsMatch(values[0]) && long.TryParse(values[0], NumberStyles.None, CultureInfo.InvariantCulture, out var n) ? n : null;
        var expire = Number("expire");
        var info = new TrafficInfo(Number("upload"), Number("download"), Number("total"), expire is >= 1 and <= 253402300799 ? expire : null);
        return info.HasAny ? info : null;
    }
    [GeneratedRegex("^[0-9]+$", RegexOptions.CultureInvariant)] private static partial Regex Digits();
}
public static class InputRules
{
    public static string? Validate(string name, string url, string ua, bool allowLoopback = false)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Length > 100) return "名称不能为空，且最多 100 个字符";
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host) || (uri.Scheme != "https" && !(allowLoopback && uri.Scheme == "http" && uri.IsLoopback))) return "请输入完整的 HTTPS 订阅链接";
        if (uri.UserInfo.Length != 0) return "暂不支持在链接中包含用户名和密码";
        if (url.Length > 8192 || uri.Fragment.Length != 0) return "订阅链接过长或包含不支持的片段";
        if (string.IsNullOrWhiteSpace(ua) || ua.Length > 256 || ua.Any(c => c is < ' ' or > '~')) return "请求标识需为 1–256 个英文、数字或半角符号";
        return null;
    }
    public static int[]? Thresholds(string text, bool usage)
    {
        var parts = text.Replace('，', ',').Split(',').Select(s => s.Trim()).ToArray();
        if (parts.Length is < 1 or > 8) return null;
        var numbers = new List<int>();
        foreach (var p in parts)
        {
            if (!int.TryParse(p, NumberStyles.None, CultureInfo.InvariantCulture, out var n) || (usage ? n is < 1 or > 100 : n is < 0 or > 365)) return null;
            numbers.Add(n);
        }
        return usage ? numbers.Distinct().Order().ToArray() : numbers.Distinct().OrderDescending().ToArray();
    }
}
public static class Display
{
    public static string Bytes(long? value)
    {
        if (value == null) return "未知";
        string[] units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
        double size = value.Value; int i = 0;
        while (size >= 1024 && i < units.Length - 1) { size /= 1024; i++; }
        return i == 0 ? $"{value} B" : size.ToString("F2", CultureInfo.InvariantCulture) + " " + units[i];
    }
    public static string Date(long? seconds, bool full = false)
    {
        if (seconds == null) return "未提供";
        try { return DateTimeOffset.FromUnixTimeSeconds(seconds.Value).ToLocalTime().ToString(full ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd", CultureInfo.InvariantCulture); }
        catch (ArgumentOutOfRangeException) { return "未提供"; }
    }
    public static string Percent(double? n) => n?.ToString("F1", CultureInfo.InvariantCulture) + (n == null ? "未知" : "%");
    public static string Status(RefreshStatus status) => status switch
    {
        RefreshStatus.Never => "等待刷新", RefreshStatus.Ready => "已更新", RefreshStatus.Partial => "部分信息未提供", RefreshStatus.NoInfo => "服务商未提供统计信息",
        RefreshStatus.NetworkError => "网络连接失败，请检查网络后重试", RefreshStatus.Timeout => "请求超时，请稍后重试", RefreshStatus.InvalidLink => "链接失效或无访问权限，请检查订阅链接", _ => "服务商响应异常，请稍后重试"
    };
}
