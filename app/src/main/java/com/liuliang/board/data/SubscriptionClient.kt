package com.liuliang.board.data

import com.liuliang.board.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    data class Success(val info: TrafficInfo?) : FetchResult
    data class Failure(val status: RefreshStatus) : FetchResult
}

fun interface SubscriptionSource { fun fetch(url: String, userAgent: String): FetchResult }

class SubscriptionClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).followSslRedirects(false).build()) : SubscriptionSource {
    override fun fetch(url: String, userAgent: String): FetchResult = try {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Cache-Control", "no-cache").build()
        // GET is compatible with providers that omit subscription headers on HEAD. Never parse/store the node configuration.
        client.newCall(request).execute().use { response ->
            when {
                response.code in listOf(400, 401, 403, 404, 410) -> FetchResult.Failure(RefreshStatus.INVALID_LINK)
                !response.isSuccessful -> FetchResult.Failure(RefreshStatus.SERVER_ERROR)
                else -> FetchResult.Success(HeaderParser.parse(response.headers.values("subscription-userinfo").joinToString(";")))
            }
        }
    } catch (e: InterruptedIOException) { FetchResult.Failure(RefreshStatus.TIMEOUT) }
    catch (e: IOException) { FetchResult.Failure(RefreshStatus.NETWORK_ERROR) }
    catch (e: IllegalArgumentException) { FetchResult.Failure(RefreshStatus.INVALID_LINK) }
}

object InputRules {
    fun validate(name: String, url: String, ua: String, allowLocalHttp: Boolean = false): String? {
        if (name.isBlank() || name.length > 100) return "名称不能为空，且最多 100 个字符"
        val parsed = url.toHttpUrlOrNull() ?: return "请输入完整的 HTTPS 订阅链接"
        val local = parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2", "::1")
        if (!parsed.isHttps && !(allowLocalHttp && local)) return "请使用 HTTPS 订阅链接"
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "暂不支持在链接中包含用户名和密码"
        if (url.length > 8192 || parsed.fragment != null) return "订阅链接过长或包含不支持的片段"
        if (ua.isBlank() || ua.length > 256 || ua.any { it.code !in 32..126 }) return "请求标识需为 1–256 个英文、数字或半角符号"
        return null
    }
    fun thresholds(text: String, usage: Boolean): List<Int>? {
        val items = text.replace('，', ',').split(',').map { it.trim() }
        if (items.isEmpty() || items.size > 8 || items.any { it.isEmpty() }) return null
        val values = items.map { it.toIntOrNull() ?: return null }
        val range = if (usage) 1..100 else 0..365
        if (values.any { it !in range }) return null
        return values.distinct().let { if (usage) it.sorted() else it.sortedDescending() }
    }
}
