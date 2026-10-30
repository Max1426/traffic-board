package com.liuliang.board.model

enum class AlertKind { USAGE, EXPIRY }
data class AlertEvent(val kind: AlertKind, val threshold: Int, val reached: Set<Int>, val message: String)
data class AlertEvaluation(val memory: AlertMemory, val events: List<AlertEvent>)

object AlertEngine {
    fun evaluate(sub: Subscription, settings: AlertSettings, now: Long): AlertEvaluation {
        val info = sub.info ?: return AlertEvaluation(sub.alerts, emptyList())
        val percent = info.percent
        val usageReached = percent?.let { p -> settings.usageThresholds.filter { p >= it }.toSet() }
        val expiryReached = info.expire?.let { expiry ->
            (settings.expiryDays + 0).filter { expiry - now <= it.toLong() * 86400 }.toSet()
        }
        val usageMemory = if (usageReached == null) sub.alerts.usage else sub.alerts.usage.intersect(usageReached)
        val expiryMemory = if (info.expire == sub.alerts.expireIdentity) {
            if (expiryReached == null) sub.alerts.expiry else sub.alerts.expiry.intersect(expiryReached)
        } else emptySet()
        val memory = AlertMemory(usageMemory, expiryMemory, info.expire)
        val events = mutableListOf<AlertEvent>()
        if (settings.usageEnabled && usageReached != null) {
            val next = (usageReached - usageMemory).maxOrNull()
            if (next != null) events += AlertEvent(AlertKind.USAGE, next, usageReached,
                "已使用 ${Display.percent(percent)}，剩余 ${Display.bytes(info.remaining)}")
        }
        if (settings.expiryEnabled && expiryReached != null) {
            val next = (expiryReached - expiryMemory).minOrNull()
            if (next != null) events += AlertEvent(AlertKind.EXPIRY, next, expiryReached,
                if (next == 0) "订阅已到期，请及时检查或续费" else "订阅将在 $next 天内到期（${Display.date(info.expire)}）")
        }
        return AlertEvaluation(memory, events)
    }
    fun mark(memory: AlertMemory, event: AlertEvent): AlertMemory = when (event.kind) {
        AlertKind.USAGE -> memory.copy(usage = memory.usage + event.reached)
        AlertKind.EXPIRY -> memory.copy(expiry = memory.expiry + event.reached)
    }
    fun warning(sub: Subscription, settings: AlertSettings, now: Long): String? {
        val info = sub.info ?: return null
        if (settings.expiryEnabled && info.expire != null) {
            if (info.expire <= now) return "已到期"
            if (settings.expiryDays.any { info.expire - now <= it.toLong() * 86400 }) return "即将到期"
        }
        if (settings.usageEnabled && info.percent?.let { p -> settings.usageThresholds.any { p >= it } } == true) return "流量不足"
        return null
    }
}
