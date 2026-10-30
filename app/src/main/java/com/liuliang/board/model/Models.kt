package com.liuliang.board.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

data class TrafficInfo(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
) {
    val used: Long? get() = upload?.let { u -> download?.let { d -> if (u <= Long.MAX_VALUE - d) u + d else null } }
    val limit: Long? get() = total?.takeIf { it > 0 }
    val remaining: Long? get() = used?.let { u -> limit?.let { max(0L, it - u) } }
    val percent: Double? get() = used?.let { u -> limit?.let { u.toDouble() / it * 100.0 } }
    val hasAny: Boolean get() = upload != null || download != null || total != null || expire != null
    val complete: Boolean get() = used != null && limit != null && expire != null
}

enum class RefreshStatus { NEVER, READY, PARTIAL, NO_INFO, NETWORK_ERROR, TIMEOUT, INVALID_LINK, SERVER_ERROR }

data class AlertMemory(
    val usage: Set<Int> = emptySet(),
    val expiry: Set<Int> = emptySet(),
    val expireIdentity: Long? = null,
)

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val userAgent: String = DEFAULT_UA,
    val info: TrafficInfo? = null,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val status: RefreshStatus = RefreshStatus.NEVER,
    val alerts: AlertMemory = AlertMemory(),
    val sourceRevision: Long = 0,
) {
    val host: String get() = runCatching { java.net.URI(url).host }.getOrNull() ?: "未知来源"
    val failed: Boolean get() = status in setOf(RefreshStatus.NETWORK_ERROR, RefreshStatus.TIMEOUT, RefreshStatus.INVALID_LINK, RefreshStatus.SERVER_ERROR)
    companion object { const val DEFAULT_UA = "clash-traffic-board/1.0" }
}

data class AlertSettings(
    val usageEnabled: Boolean = true,
    val expiryEnabled: Boolean = true,
    val usageThresholds: List<Int> = listOf(80, 95),
    val expiryDays: List<Int> = listOf(7, 3),
)

enum class ThemeMode(val label: String) { SYSTEM("系统"), LIGHT("浅色"), DARK("深色") }
data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColors: Boolean = true,
    val hapticsEnabled: Boolean = true,
)
data class BoardData(val subscriptions: List<Subscription> = emptyList(), val settings: AlertSettings = AlertSettings(), val preferences: UiPreferences = UiPreferences())

object HeaderParser {
    fun parse(header: String?): TrafficInfo? {
        if (header.isNullOrBlank()) return null
        val fields = header.split(';').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) null else pair[0].trim().lowercase(Locale.ROOT) to pair[1].trim()
        }.groupBy({ it.first }, { it.second })
        fun number(key: String): Long? = fields[key]?.singleOrNull()?.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
        return TrafficInfo(number("upload"), number("download"), number("total"),
            number("expire")?.takeIf { it in 1..253402300799L }).takeIf { it.hasAny }
    }
}

object Display {
    fun bytes(value: Long?): String {
        if (value == null) return "未知"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var size = value.toDouble()
        var index = 0
        while (size >= 1024 && index < units.lastIndex) { size /= 1024; index++ }
        return if (index == 0) "$value B" else String.format(Locale.ROOT, "%.2f %s", size, units[index])
    }
    fun date(seconds: Long?, full: Boolean = false): String = seconds?.let {
        runCatching { DateTimeFormatter.ofPattern(if (full) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it)) }.getOrNull()
    } ?: "未提供"
    fun percent(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "未知"
    fun status(status: RefreshStatus): String = when (status) {
        RefreshStatus.NEVER -> "等待刷新"
        RefreshStatus.READY -> "已更新"
        RefreshStatus.PARTIAL -> "部分信息未提供"
        RefreshStatus.NO_INFO -> "服务商未提供统计信息"
        RefreshStatus.NETWORK_ERROR -> "网络连接失败，请检查网络后重试"
        RefreshStatus.TIMEOUT -> "请求超时，请稍后重试"
        RefreshStatus.INVALID_LINK -> "链接失效或无访问权限，请检查订阅链接"
        RefreshStatus.SERVER_ERROR -> "服务商响应异常，请稍后重试"
    }
}
