package com.liuliang.board

import com.liuliang.board.data.*
import com.liuliang.board.model.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkTest {
    @Test fun getReadsHeadersAndSendsCustomUa() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Subscription-Userinfo", "upload=10; download=20; total=100; expire=1800000000").setBody("not parsed"))
        val result = SubscriptionClient().fetch(server.url("/sub?token=private").toString(), "custom-clash") as FetchResult.Success
        assertEquals(30L, result.info!!.used)
        val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("custom-clash", request.getHeader("User-Agent"))
    }
    @Test fun missingStatsIsSuccessfulButUnknown() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("nodes"))
        assertNull((SubscriptionClient().fetch(server.url("/").toString(), "clash") as FetchResult.Success).info)
    }
    @Test fun authAndExpiredLinksAreDistinctFromServerFailure() = MockWebServer().use { server ->
        for (code in listOf(401, 403, 404, 410)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(RefreshStatus.INVALID_LINK, (SubscriptionClient().fetch(server.url("/").toString(), "clash") as FetchResult.Failure).status)
        }
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(RefreshStatus.SERVER_ERROR, (SubscriptionClient().fetch(server.url("/").toString(), "clash") as FetchResult.Failure).status)
    }
    @Test fun delayedHeadersAreTimeout() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS))
        val client = SubscriptionClient(OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build())
        assertEquals(RefreshStatus.TIMEOUT, (client.fetch(server.url("/").toString(), "clash") as FetchResult.Failure).status)
    }
    @Test fun unreachableHostIsNetworkFailure() {
        val server = MockWebServer(); server.start(); val url = server.url("/").toString(); server.shutdown()
        assertEquals(RefreshStatus.NETWORK_ERROR, (SubscriptionClient().fetch(url, "clash") as FetchResult.Failure).status)
    }
}
