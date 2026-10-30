package com.liuliang.board

import com.liuliang.board.model.*
import org.junit.Assert.*
import org.junit.Test

class AlertsTest {
    private val now = 1800000000L
    private val settings = AlertSettings()
    private fun sub(used: Long = 96, expire: Long? = now + 2 * 86400) = Subscription("a", "日常", "https://example.com", info = TrafficInfo(0, used, 100, expire))
    @Test fun firstRefreshSendsHighestUsageAndClosestExpiryOnly() {
        val result = AlertEngine.evaluate(sub(), settings, now)
        assertEquals(2, result.events.size)
        assertEquals(95, result.events.first { it.kind == AlertKind.USAGE }.threshold)
        assertEquals(3, result.events.first { it.kind == AlertKind.EXPIRY }.threshold)
        var memory = result.memory
        result.events.forEach { memory = AlertEngine.mark(memory, it) }
        assertTrue(AlertEngine.evaluate(sub().copy(alerts = memory), settings, now).events.isEmpty())
    }
    @Test fun newHigherThresholdStillNotifies() {
        val old = AlertMemory(setOf(80), emptySet(), now + 20 * 86400)
        val result = AlertEngine.evaluate(sub(expire = now + 20 * 86400).copy(alerts = old), settings, now)
        assertEquals(listOf(95), result.events.map { it.threshold })
    }
    @Test fun observedQuotaResetRearmsUsageLevels() {
        val old = AlertMemory(setOf(80, 95))
        val reset = AlertEngine.evaluate(sub(used = 1, expire = null).copy(alerts = old), settings, now)
        assertTrue(reset.memory.usage.isEmpty())
        assertEquals(95, AlertEngine.evaluate(sub(expire = null).copy(alerts = reset.memory), settings, now).events.single().threshold)
    }
    @Test fun dateChangeRearmsExpiryWithoutRearmingUsage() {
        val result = AlertEngine.evaluate(sub().copy(alerts = AlertMemory(setOf(80, 95), setOf(7, 3), now - 5)), settings, now)
        assertEquals(AlertKind.EXPIRY, result.events.single().kind)
    }
    @Test fun expiredHasDistinctOnceOnlyNotification() {
        val expired = sub(used = 1, expire = now - 1).copy(alerts = AlertMemory(expiry = setOf(7, 3), expireIdentity = now - 1))
        val result = AlertEngine.evaluate(expired, settings, now)
        assertEquals(0, result.events.single().threshold)
        val marked = AlertEngine.mark(result.memory, result.events.single())
        assertTrue(AlertEngine.evaluate(expired.copy(alerts = marked), settings, now).events.isEmpty())
        assertEquals("已到期", AlertEngine.warning(expired, settings, now))
    }
    @Test fun unknownCountersDoNotTriggerAndDisabledAlertsStaySilent() {
        assertTrue(AlertEngine.evaluate(sub().copy(info = TrafficInfo(total = 0)), settings, now).events.isEmpty())
        assertTrue(AlertEngine.evaluate(sub(), settings.copy(usageEnabled = false, expiryEnabled = false), now).events.isEmpty())
    }
    @Test fun editedThresholdBecomesEligible() {
        val result = AlertEngine.evaluate(sub().copy(alerts = AlertMemory(setOf(80, 95), setOf(7, 3), now + 2 * 86400)), settings.copy(usageThresholds = listOf(90, 98)), now)
        assertEquals(90, result.events.single().threshold)
    }
}
