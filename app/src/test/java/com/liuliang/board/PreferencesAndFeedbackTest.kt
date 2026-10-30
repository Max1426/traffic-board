package com.liuliang.board

import android.view.HapticFeedbackConstants
import com.liuliang.board.data.*
import com.liuliang.board.model.*
import com.liuliang.board.ui.BoardFeedback
import com.liuliang.board.ui.FeedbackKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreferencesAndFeedbackTest {
    @Test fun oldVersionDataGetsSafeDefaultsWithoutChangingSubscriptionsOrAlerts() {
        val old = BoardData(listOf(Subscription("a", "原订阅", "https://example.com", info = TrafficInfo(1, 5, 100, 1800000000), alerts = AlertMemory(setOf(80), setOf(3), 1800000000))))
        val json = JSONObject(BoardCodec.encode(old)).apply { remove("preferences") }
        assertEquals(old, BoardCodec.decode(json.toString()))
    }
    @Test fun preferenceRoundTripAndUnknownFutureThemeFallback() {
        val data = BoardData(preferences = UiPreferences(ThemeMode.DARK, false, false))
        assertEquals(data, BoardCodec.decode(BoardCodec.encode(data)))
        val future = JSONObject(BoardCodec.encode(data)).apply { getJSONObject("preferences").put("themeMode", "future-theme") }
        assertEquals(ThemeMode.SYSTEM, BoardCodec.decode(future.toString()).preferences.themeMode)
        assertFalse(BoardCodec.decode(future.toString()).preferences.hapticsEnabled)
    }
    @Test fun preferencesSaveWithoutDiscardingSubscriptionsAndPersistAfterReload() = runBlocking {
        var persisted = BoardData(listOf(Subscription("a", "原订阅", "https://example.com")))
        val store = object : BoardStore {
            override fun read() = persisted
            override fun write(data: BoardData) { persisted = data }
        }
        fun repository() = BoardRepository(store, SubscriptionSource { _, _ -> FetchResult.Success(null) }, AlertSender { _, _ -> false })
        val repo = repository()
        val prefs = UiPreferences(ThemeMode.LIGHT, false, false)
        repo.savePreferences(prefs)
        repo.saveSettings(AlertSettings(usageThresholds = listOf(70, 90)))
        val restored = repository().apply { initialize() }.state.value.data
        assertEquals(prefs, restored.preferences)
        assertEquals("原订阅", restored.subscriptions.single().name)
        assertEquals(listOf(70, 90), restored.settings.usageThresholds)
    }
    @Test fun appOffPreventsEveryFeedbackTypeAndTurningOnRestoresIt() {
        var enabled = false; val effects = mutableListOf<Int>(); var clock = 0L
        val feedback = BoardFeedback({ enabled }, { effects += it; true }, { clock }, 35)
        FeedbackKind.entries.forEach { feedback.emit(it); clock += 100 }
        assertTrue(effects.isEmpty())
        enabled = true; feedback.emit(); assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK), effects)
    }
    @Test fun repeatedTapsAreCoalescedButConfirmationAndRejectionRemainDistinct() {
        var clock = 1000L; val effects = mutableListOf<Int>()
        val feedback = BoardFeedback({ true }, { effects += it; true }, { clock }, 35)
        feedback.emit(); clock += 10; feedback.emit(); assertEquals(1, effects.size)
        feedback.emit(FeedbackKind.CONFIRM); assertEquals(HapticFeedbackConstants.CONFIRM, effects.last())
        feedback.emit(FeedbackKind.CONFIRM); assertEquals(2, effects.size)
        clock += 100; feedback.emit(FeedbackKind.REJECT); assertEquals(HapticFeedbackConstants.REJECT, effects.last())
    }
    @Test fun olderAndroidGetsSupportedFeedbackConstants() {
        var clock = 0L; val effects = mutableListOf<Int>()
        val feedback = BoardFeedback({ true }, { effects += it; true }, { clock }, 26)
        feedback.emit(FeedbackKind.CONFIRM); clock += 100; feedback.emit(FeedbackKind.REJECT)
        assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackConstants.LONG_PRESS), effects)
    }
    @Test fun systemDeclinedFeedbackIsNotRecordedAsAPlayedEffect() {
        var attempts = 0
        val feedback = BoardFeedback({ true }, { attempts++; false }, { 1000L }, 35)
        feedback.emit(); feedback.emit(); assertEquals(2, attempts)
    }
}
