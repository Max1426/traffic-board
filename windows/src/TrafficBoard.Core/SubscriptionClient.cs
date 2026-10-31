using System.Net;
namespace TrafficBoard.Core;
public sealed record FetchResult(TrafficInfo? Info = null, RefreshStatus? Error = null);
public interface ISubscriptionSource { Task<FetchResult> FetchAsync(string url, string userAgent, CancellationToken cancellationToken); }
public sealed class SubscriptionClient : ISubscriptionSource, IDisposable
{
    private readonly HttpClient client;
    private readonly bool allowLoopback;
    public SubscriptionClient(HttpMessageHandler? handler = null, bool allowLoopback = false)
    {
        client = new(handler ?? new HttpClientHandler { AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.All });
        client.Timeout = TimeSpan.FromSeconds(20); this.allowLoopback = allowLoopback;
    }
    public async Task<FetchResult> FetchAsync(string url, string userAgent, CancellationToken cancellationToken)
    {
        try
        {
            var uri = new Uri(url);
            for (int redirect = 0; redirect <= 5; redirect++)
            {
                if (InputRules.Validate("request", uri.AbsoluteUri, userAgent, allowLoopback) != null) return new(Error: RefreshStatus.InvalidLink);
                using var request = new HttpRequestMessage(HttpMethod.Get, uri);
                request.Headers.TryAddWithoutValidation("User-Agent", userAgent); request.Headers.CacheControl = new() { NoCache = true };
                using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
                var code = (int)response.StatusCode;
                if (code is 301 or 302 or 303 or 307 or 308)
                {
                    if (redirect == 5 || response.Headers.Location == null) return new(Error: RefreshStatus.ServerError);
                    uri = new Uri(uri, response.Headers.Location); continue;
                }
                if (code is 400 or 401 or 403 or 404 or 410) return new(Error: RefreshStatus.InvalidLink);
                if (!response.IsSuccessStatusCode) return new(Error: RefreshStatus.ServerError);
                return new(HeaderParser.Parse(response.Headers.TryGetValues("subscription-userinfo", out var values) ? string.Join(';', values) : null));
            }
            return new(Error: RefreshStatus.ServerError);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return new(Error: RefreshStatus.Timeout); }
        catch (HttpRequestException) { return new(Error: RefreshStatus.NetworkError); }
        catch (IOException) { return new(Error: RefreshStatus.NetworkError); }
        catch (UriFormatException) { return new(Error: RefreshStatus.InvalidLink); }
        catch (ArgumentException) { return new(Error: RefreshStatus.InvalidLink); }
    }
    public void Dispose() => client.Dispose();
}
