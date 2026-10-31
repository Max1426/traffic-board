using TrafficBoard.Core;
using Xunit;
using System.Net;
using System.Security.Cryptography;
using System.Text;

namespace TrafficBoard.Tests;
public class RulesTests
{
    [Fact] public void NormalHeaderAndBinaryUnits()
    {
        var info = HeaderParser.Parse("UPLOAD=1073741824; download=2147483648; total=128849018880; expire=1900000000")!;
        Assert.Equal(3221225472, info.Used); Assert.Equal("3.00 GiB", Display.Bytes(info.Used)); Assert.Equal(2.5, info.Percent); Assert.True(info.Complete);
    }
    [Theory] [InlineData(null)] [InlineData("")] [InlineData("nonsense")] [InlineData("upload=-1; download=oops; expire=0")] [InlineData("upload=9223372036854775808")]
    public void InvalidOrMissingInfo(string? header) => Assert.Null(HeaderParser.Parse(header));
    [Fact] public void DuplicateFieldIsUnknown() { var i = HeaderParser.Parse("upload=1; Upload=2; download=3; total=100")!; Assert.Null(i.Upload); Assert.Null(i.Used); Assert.Equal(100, i.Total); }
    [Fact] public void PartialDoesNotInventZero() { var i = HeaderParser.Parse("download=10")!; Assert.Null(i.Used); Assert.False(i.Complete); Assert.Equal(10, i.Download); }
    [Fact] public void OverflowDoesNotWrap() => Assert.Null(new TrafficInfo(long.MaxValue, 1, 100).Used);
    [Fact] public void ZeroIsNotUnlimited() { var i = new TrafficInfo(1, 2, 0); Assert.Equal(0, i.Total); Assert.Null(i.Remaining); Assert.Null(i.Percent); }
    [Fact] public void ExceededKeepsActualPercentage() { var i = new TrafficInfo(100, 50, 100); Assert.Equal(150, i.Percent); Assert.Equal(0, i.Remaining); }
    [Theory] [InlineData("0",true)] [InlineData("101",true)] [InlineData("",true)] [InlineData("1,,2",true)] [InlineData("366",false)] [InlineData("-1",false)] [InlineData("1,2,3,4,5,6,7,8,9",true)]
    public void InvalidThresholds(string text, bool usage) => Assert.Null(InputRules.Thresholds(text, usage));
    [Fact] public void ThresholdsNormalize() { Assert.Equal([80, 95], InputRules.Thresholds("95，80,80", true)!); Assert.Equal([7,3,0], InputRules.Thresholds("0,3,7", false)!); }
    [Theory] [InlineData("http://example.com/sub")] [InlineData("https://user:pass@example.com/sub")] [InlineData("https://example.com/sub#token")] [InlineData("not a URL")]
    public void InvalidLinks(string url) => Assert.NotNull(InputRules.Validate("name", url, "clash"));
    [Fact] public void UserAgentAndNameValidation() { Assert.NotNull(InputRules.Validate("", "https://example.com", "clash")); Assert.NotNull(InputRules.Validate("n", "https://example.com", "clash\nsecret")); Assert.Null(InputRules.Validate("n", "https://example.com/?token=example", "custom-clash")); }
    [Fact] public void DateBoundaries() { Assert.Null(HeaderParser.Parse("expire=253402300800")); Assert.NotEqual("未提供", Display.Date(1900000000, true)); Assert.Equal("未提供", Display.Date(long.MaxValue)); }
}
public class AlertTests
{
    private static Subscription Sub(long used=96, long? expire=1800000000) => new() { Name="test", Info=new(0,used,100,expire) };
    [Fact] public void OnlyHighestUsageAndMostUrgentExpiry()
    {
        var e=AlertEngine.Evaluate(Sub(expire:1000),new(),2000); Assert.Equal(2,e.Events.Length); Assert.Equal(95,e.Events[0].Threshold); Assert.Equal(0,e.Events[1].Threshold);
    }
    [Fact] public void DedupAndRecovery()
    {
        var sub=Sub(); var first=AlertEngine.Evaluate(sub,new(),1000); var memory=AlertEngine.Mark(first.Memory,first.Events.Single());
        Assert.Empty(AlertEngine.Evaluate(sub with{Alerts=memory},new(),1000).Events);
        var lower=AlertEngine.Evaluate(sub with{Info=new(0,10,100,1800000000),Alerts=memory},new(),1000);
        Assert.Empty(lower.Memory.Usage); Assert.Single(AlertEngine.Evaluate(sub with{Alerts=lower.Memory},new(),1000).Events);
    }
    [Fact] public void NewExpiryAllowsReminderAgain()
    {
        var sub=Sub(10,1000); var e=AlertEngine.Evaluate(sub,new(),2000); var m=AlertEngine.Mark(e.Memory,e.Events.Single());
        Assert.Empty(AlertEngine.Evaluate(sub with{Alerts=m},new(),2000).Events);
        Assert.Single(AlertEngine.Evaluate(sub with{Alerts=m,Info=new(0,10,100,1500)},new(),2000).Events);
    }
    [Fact] public void DisabledAndUnknownDoNotNotify()
    {
        Assert.Empty(AlertEngine.Evaluate(Sub(expire:1000),new(){UsageEnabled=false,ExpiryEnabled=false},2000).Events);
        Assert.Empty(AlertEngine.Evaluate(new(){Info=new(null,null,0)},new(),2000).Events);
    }
}
internal sealed class MemoryStore : IBoardStore
{
    public BoardData Data=new(); public bool FailRead,FailWrite; public int Writes;
    public BoardData Read() => FailRead ? throw new IOException() : Data;
    public void Write(BoardData d) { if(FailWrite) throw new IOException(); Data=d; Writes++; }
}
internal sealed class Clock : IClock { public long Now {get;set;}=1800000000; }
internal sealed class Sender : IAlertSender { public bool Enabled=true; public int Count; public bool Send(Subscription s, AlertEvent e) { if(!Enabled)return false;Count++;return true; } }
internal sealed class Source : ISubscriptionSource
{
    public FetchResult Result=new(new(0,10,100,1900000000)); public int Calls;
    public Func<CancellationToken,Task<FetchResult>>? Handler;
    public Task<FetchResult> FetchAsync(string u,string a,CancellationToken ct) { Calls++;return Handler?.Invoke(ct)??Task.FromResult(Result); }
}
public class RepositoryTests
{
    private readonly MemoryStore store=new(); private readonly Source source=new(); private readonly Sender sender=new(); private readonly Clock clock=new();
    private BoardRepository Repo()=>new(store,source,sender,clock);
    private static Task<string> Add(BoardRepository r)=>r.SaveSubscriptionAsync(null,"Test","https://example.com/sub","clash");
    [Fact] public async Task FailedRefreshPreservesSnapshot()
    {
        var r=Repo();var id=await Add(r);await r.RefreshAsync(id);var before=r.Data.Subscriptions.Single();clock.Now++;
        source.Result=new(Error:RefreshStatus.NetworkError);await r.RefreshAsync(id);var after=r.Data.Subscriptions.Single();
        Assert.Equal(before.Info,after.Info);Assert.Equal(before.LastSuccessAt,after.LastSuccessAt);Assert.Equal(clock.Now,after.LastAttemptAt);Assert.True(after.Failed);
    }
    [Fact] public async Task RenameKeepsSnapshotButNewSourceClearsIt()
    {
        var r=Repo();var id=await Add(r);await r.RefreshAsync(id);await r.SaveSubscriptionAsync(id,"Rename","https://example.com/sub","clash");Assert.NotNull(r.Data.Subscriptions.Single().Info);
        await r.SaveSubscriptionAsync(id,"Rename","https://example.com/new","clash");Assert.Null(r.Data.Subscriptions.Single().Info);Assert.Equal(1,r.Data.Subscriptions.Single().SourceRevision);
    }
    [Fact] public async Task NoInfoSuccessClearsOldSnapshot()
    {
        var r=Repo();var id=await Add(r);await r.RefreshAsync(id);source.Result=new();await r.RefreshAsync(id);Assert.Null(r.Data.Subscriptions.Single().Info);Assert.Equal(RefreshStatus.NoInfo,r.Data.Subscriptions.Single().Status);
    }
    [Fact] public async Task DuplicateLinkRejected()
    {
        var r=Repo();await Add(r);await Assert.ThrowsAsync<ArgumentException>(()=>Add(r));Assert.Single(r.Data.Subscriptions);
    }
    [Fact] public async Task ReadFailureNeverOverwritesFile()
    {
        store.FailRead=true;var r=Repo();await r.InitializeAsync();Assert.NotNull(r.StorageError);await Assert.ThrowsAsync<IOException>(()=>Add(r));Assert.Equal(0,store.Writes);
    }
    [Fact] public async Task FailedWriteDoesNotPublishNewState()
    {
        var r=Repo();var id=await Add(r);store.FailWrite=true;await Assert.ThrowsAsync<IOException>(()=>r.DeleteAsync(id));Assert.Single(r.Data.Subscriptions);
    }
    [Theory] [InlineData(false)] [InlineData(true)]
    public async Task StaleResponseDoesNotOverwriteEditedOrDeleted(bool delete)
    {
        var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);var finish=new TaskCompletionSource<FetchResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        source.Handler=_=>{entered.SetResult();return finish.Task;};
        var r=Repo();var id=await Add(r);var request=r.RefreshAsync(id);await entered.Task;
        if(delete)await r.DeleteAsync(id);else await r.SaveSubscriptionAsync(id,"new","https://example.com/new","clash");
        finish.SetResult(new(new(0,99,100,1900000000)));await request;
        Assert.False(r.IsRefreshing(id));if(delete)Assert.Empty(r.Data.Subscriptions);else Assert.Null(r.Data.Subscriptions.Single().Info);
    }
    [Fact] public async Task PerSubscriptionRequestsSerializeAndUseEditedLink()
    {
        var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);var finish=new TaskCompletionSource<FetchResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        source.Handler=_=>{if(source.Calls==1){entered.SetResult();return finish.Task;}return Task.FromResult(new FetchResult(new(0,20,100,1900000000)));};
        var r=Repo();var id=await Add(r);var first=r.RefreshAsync(id);await entered.Task;await r.SaveSubscriptionAsync(id,"new","https://example.com/new","clash");var second=r.RefreshAsync(id);
        Assert.Equal(1,source.Calls);finish.SetResult(new(new(0,99,100)));await Task.WhenAll(first,second);Assert.Equal(20,r.Data.Subscriptions.Single().Info?.Used);
    }
    [Fact] public async Task CancelRefreshDoesNotCommit()
    {
        var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        source.Handler=async ct=>{entered.SetResult();await Task.Delay(Timeout.Infinite,ct);return new();};
        var r=Repo();var id=await Add(r);using var cts=new CancellationTokenSource();var task=r.RefreshAsync(id,cts.Token);await entered.Task;cts.Cancel();await Assert.ThrowsAnyAsync<OperationCanceledException>(()=>task);Assert.False(r.IsRefreshing(id));Assert.Null(r.Data.Subscriptions.Single().LastAttemptAt);
    }
    [Fact] public async Task NotificationMemoryPersistsAndDeniedDoesNotConsume()
    {
        source.Result=new(new(0,96,100,1900000000));sender.Enabled=false;var r=Repo();var id=await Add(r);await r.RefreshAsync(id);Assert.Empty(store.Data.Subscriptions.Single().Alerts.Usage);
        sender.Enabled=true;await r.RefreshAsync(id);Assert.Equal(1,sender.Count);var restored=Repo();await restored.RefreshAsync(id);Assert.Equal(1,sender.Count);
    }
    [Fact] public async Task ChangedThresholdBecomesEligibleAndPreferencesPersist()
    {
        source.Result=new(new(0,96,100,1900000000));var r=Repo();var id=await Add(r);await r.RefreshAsync(id);
        await r.SaveSettingsAsync(new(){UsageThresholds=[90],ExpiryDays=[10,2]});await r.SavePreferencesAsync(new(ThemeMode.Dark));await r.RefreshAsync(id);
        Assert.Equal(2,sender.Count);var restored=Repo();await restored.InitializeAsync();Assert.Equal(ThemeMode.Dark,restored.Data.Preferences.Theme);Assert.Equal([10,2],restored.Data.Settings.ExpiryDays);
    }
    [Fact] public async Task SchedulerUsesClockAndStartupStaleRule()
    {
        var r=Repo();await Add(r);await using var s=new RefreshScheduler(r,clock);await s.CheckAsync();Assert.Equal(1,source.Calls);
        clock.Now+=21599;await s.CheckAsync();Assert.Equal(1,source.Calls);clock.Now++;await s.CheckAsync();Assert.Equal(2,source.Calls);
        await s.CheckAsync(true);Assert.Equal(2,source.Calls);
    }
    [Fact] public async Task SchedulerDisposalCancelsAndDrains()
    {
        var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);source.Handler=async ct=>{entered.SetResult();await Task.Delay(Timeout.Infinite,ct);return new();};
        var r=Repo();var id=await Add(r);var s=new RefreshScheduler(r,clock);s.Start();await entered.Task;await s.DisposeAsync();Assert.False(r.IsRefreshing(id));Assert.Null(r.Data.Subscriptions.Single().Info);
    }
}
public class StorageTests
{
    [Fact] public void DpapiRoundTripAndAtomicReplacement()
    {
        var root=Path.Combine(Path.GetTempPath(),"TrafficBoard-tests-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try
        {
            var store=new EncryptedBoardStore(root);Assert.Empty(store.Read().Subscriptions);
            var data=new BoardData{Subscriptions=[new(){Name="中文测试",Url="https://example.com/?token=not-a-real-token",Info=new(1,2,100,1900000000)}],Preferences=new(ThemeMode.Dark)};
            store.Write(data);var bytes=File.ReadAllBytes(store.FilePath);Assert.DoesNotContain("not-a-real-token",Encoding.UTF8.GetString(bytes));Assert.Equal(data.Subscriptions[0].Url,store.Read().Subscriptions[0].Url);
            store.Write(data with{Settings=new(){UsageThresholds=[70,90]}});Assert.Equal([70,90],store.Read().Settings.UsageThresholds);Assert.Single(Directory.GetFiles(root));
            bytes[^1]^=0x22;File.WriteAllBytes(store.FilePath,bytes);Assert.ThrowsAny<CryptographicException>(()=>store.Read());Assert.Equal(bytes,File.ReadAllBytes(store.FilePath));
        }
        finally { if(Path.GetFullPath(root).StartsWith(Path.GetFullPath(Path.GetTempPath()),StringComparison.OrdinalIgnoreCase))Directory.Delete(root,true); }
    }
}
internal sealed class Handler(Func<HttpRequestMessage,CancellationToken,Task<HttpResponseMessage>> handler) : HttpMessageHandler
{ protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)=>handler(request,ct); }
internal sealed class UnreadableContent : HttpContent
{
    protected override Task SerializeToStreamAsync(Stream stream,TransportContext? context)=>throw new Exception("Body must never be downloaded");
    protected override bool TryComputeLength(out long length){length=100000000;return true;}
}
public class NetworkTests
{
    [Fact] public async Task ReadsOnlyResponseHeadersAndSendsCustomUserAgent()
    {
        using var client=new SubscriptionClient(new Handler((request,ct)=>
        {
            Assert.Equal(HttpMethod.Get,request.Method);Assert.Equal("custom-clash",request.Headers.UserAgent.ToString());Assert.True(request.Headers.CacheControl?.NoCache);
            var response=new HttpResponseMessage(HttpStatusCode.OK){Content=new UnreadableContent()};response.Headers.TryAddWithoutValidation("subscription-userinfo","upload=1;download=2;total=100");return Task.FromResult(response);
        }));
        Assert.Equal(3,(await client.FetchAsync("https://example.com/sub","custom-clash",default)).Info?.Used);
    }
    [Theory] [InlineData(401,RefreshStatus.InvalidLink)] [InlineData(404,RefreshStatus.InvalidLink)] [InlineData(410,RefreshStatus.InvalidLink)] [InlineData(500,RefreshStatus.ServerError)] [InlineData(429,RefreshStatus.ServerError)]
    public async Task HttpErrorsAreClassified(int code,RefreshStatus expected)
    { using var client=new SubscriptionClient(new Handler((r,c)=>Task.FromResult(new HttpResponseMessage((HttpStatusCode)code))));Assert.Equal(expected,(await client.FetchAsync("https://example.com","clash",default)).Error); }
    [Fact] public async Task RefusesHttpsDowngrade()
    { int calls=0;using var client=new SubscriptionClient(new Handler((r,c)=>{calls++;var response=new HttpResponseMessage(HttpStatusCode.Redirect);response.Headers.Location=new("http://example.com/plain");return Task.FromResult(response);}));Assert.Equal(RefreshStatus.InvalidLink,(await client.FetchAsync("https://example.com","clash",default)).Error);Assert.Equal(1,calls); }
    [Fact] public async Task FollowsBoundedHttpsRedirect()
    { int calls=0;using var client=new SubscriptionClient(new Handler((r,c)=>{calls++;var response=new HttpResponseMessage(calls==1?HttpStatusCode.Redirect:HttpStatusCode.OK);if(calls==1)response.Headers.Location=new("/new",UriKind.Relative);return Task.FromResult(response);}));Assert.Null((await client.FetchAsync("https://example.com","clash",default)).Error);Assert.Equal(2,calls); }
    [Fact] public async Task NetworkAndTimeoutAreDifferent()
    {
        using var failed=new SubscriptionClient(new Handler((r,c)=>throw new HttpRequestException()));using var timeout=new SubscriptionClient(new Handler((r,c)=>throw new TaskCanceledException()));
        Assert.Equal(RefreshStatus.NetworkError,(await failed.FetchAsync("https://example.com","clash",default)).Error);Assert.Equal(RefreshStatus.Timeout,(await timeout.FetchAsync("https://example.com","clash",default)).Error);
    }
}
