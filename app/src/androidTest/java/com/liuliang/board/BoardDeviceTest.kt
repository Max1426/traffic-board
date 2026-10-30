package com.liuliang.board

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import com.liuliang.board.ui.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.liuliang.board.data.EncryptedBoardStore
import com.liuliang.board.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BoardDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as TrafficApplication
    private val repo get() = app.repository

    @Before fun cleanTestData() = runBlocking {
        repo.initialize()
        repo.state.value.data.subscriptions.forEach { repo.deleteSubscription(it.id) }
        repo.saveSettings(AlertSettings())
        repo.savePreferences(UiPreferences())
        rule.waitForIdle()
    }

    @Test fun addRefreshEditSettingsDeleteAndEncryptedPersistence() = MockWebServer().use { server ->
        val expiry = System.currentTimeMillis() / 1000 + 80 * 86400
        server.enqueue(MockResponse().setHeader("subscription-userinfo", "upload=1073741824; download=2093796556; total=128849018880; expire=$expiry").setBody("ignored"))
        val url = server.url("/subscription?token=demo-credential-not-real").toString()
        rule.onNodeWithText("从第一个订阅开始").performScrollTo().assertIsDisplayed()
        capture("01-empty")
        addThroughUi("日常使用", url)
        rule.waitUntil(15000) { repo.state.value.data.subscriptions.firstOrNull()?.status == RefreshStatus.READY }
        rule.onNodeWithText("日常使用").assertIsDisplayed()
        rule.onAllNodesWithText(url).assertCountEquals(0)
        capture("02-details")
        val firstId = repo.state.value.data.subscriptions.single().id
        val before = repo.state.value.data.subscriptions.single().info
        val stored = EncryptedBoardStore(app).read()
        assertEquals(repo.state.value.data, stored)
        val ciphertext = File(app.noBackupFilesDir, "board.v1.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(ciphertext.contains("demo-credential-not-real")); assertFalse(ciphertext.contains(url))
        assertTrue(File(app.noBackupFilesDir, "board.v1.enc").length() > 0)

        // A failed refresh keeps the successful counters and reports the reason.
        server.enqueue(MockResponse().setResponseCode(403))
        rule.onNodeWithText("刷新订阅").performScrollTo().performClick()
        rule.waitUntil(15000) { repo.state.value.data.subscriptions.first().status == RefreshStatus.INVALID_LINK }
        assertEquals(before, repo.state.value.data.subscriptions.first().info)
        rule.onNodeWithText("更新失败 · 当前显示上次成功数据").performScrollTo().assertIsDisplayed()

        // Renaming preserves counters; source edits are separately covered by repository race tests.
        rule.onNodeWithText("编辑订阅").performScrollTo().performClick()
        rule.onNodeWithTag("name-input").performTextReplacement("日常使用 · 主力套餐")
        server.enqueue(MockResponse().setHeader("subscription-userinfo", "upload=1073741824; download=2093796556; total=128849018880; expire=$expiry"))
        rule.onNodeWithText("保存并刷新").performScrollTo().performClick()
        rule.waitUntil(15000) { repo.state.value.data.subscriptions.first().status == RefreshStatus.READY && repo.state.value.refreshing.isEmpty() }
        rule.onNodeWithContentDescription("返回").performClick()

        server.enqueue(MockResponse().setHeader("subscription-userinfo", "upload=0; download=103079215104; total=107374182400; expire=${System.currentTimeMillis() / 1000 + 2 * 86400}"))
        addThroughUi("备用订阅 · 临近到期", server.url("/backup").toString())
        rule.waitUntil(15000) { repo.state.value.data.subscriptions.size == 2 && repo.state.value.data.subscriptions.last().status == RefreshStatus.READY }
        rule.onNodeWithContentDescription("返回").performClick()
        capture("03-home")
        rule.onNodeWithTag("home-list").performScrollToNode(hasText("备用订阅 · 临近到期"))
        capture("04-cards")

        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("usage-thresholds").performScrollTo().performTextReplacement("70, 90")
        rule.onNodeWithTag("expiry-thresholds").performScrollTo().performTextReplacement("10, 2")
        rule.onNodeWithText("保存设置").performScrollTo().performClick()
        rule.waitUntil { repo.state.value.data.settings.usageThresholds == listOf(70, 90) }
        assertEquals(listOf(10, 2), EncryptedBoardStore(app).read().settings.expiryDays)
        rule.onNodeWithContentDescription("设置").performClick()
        capture("05-settings")
        rule.onNodeWithContentDescription("返回").performClick()

        // Persisted data is still usable after the Activity is recreated.
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("home-list").performScrollToNode(hasText("日常使用 · 主力套餐"))
        rule.onNodeWithText("日常使用 · 主力套餐").performClick()
        rule.onNodeWithText("删除订阅").performScrollTo().performClick()
        rule.onNodeWithText("删除", useUnmergedTree = true).performClick()
        rule.waitUntil { repo.state.value.data.subscriptions.none { it.id == firstId } }
        assertEquals(1, EncryptedBoardStore(app).read().subscriptions.size)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("subscription-refresh-six-hours").get().isNotEmpty())
        // Leave only local fixture data on the emulator, never inside the shipped APK.
    }

    @Test fun validationLongNamesUnknownFieldsAndLargeFontLayout() = MockWebServer().use { server ->
        rule.onNodeWithTag("add-subscription").performClick()
        rule.onNodeWithText("保存并刷新").performScrollTo().performClick()
        rule.onNodeWithTag("editor-error").assertExists()
        rule.onNodeWithTag("name-input").performScrollTo().performTextInput("这是一个用于验证小屏幕和大字体显示的超长订阅名称")
        rule.onNodeWithTag("url-input").performTextInput(server.url("/partial").toString())
        server.enqueue(MockResponse().setHeader("subscription-userinfo", "download=100; total=0; expire=0"))
        rule.onNodeWithText("保存并刷新").performScrollTo().performClick()
        rule.waitUntil(15000) { repo.state.value.data.subscriptions.firstOrNull()?.status == RefreshStatus.PARTIAL }
        rule.onNodeWithText("部分信息未提供").assertExists()
        assertNull(repo.state.value.data.subscriptions.single().info!!.used)
        assertNull(repo.state.value.data.subscriptions.single().info!!.limit)
        capture("06-long-name")
        server.enqueue(MockResponse())
        rule.onNodeWithText("刷新订阅").performScrollTo().performClick()
        rule.waitUntil(15000) { repo.state.value.data.subscriptions.single().status == RefreshStatus.NO_INFO }
        assertNull(repo.state.value.data.subscriptions.single().info)
    }

    @Test fun notificationsAreDeliveredOnceAndRetainTheirThresholdState() = MockWebServer().use { server ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        val expiry = System.currentTimeMillis() / 1000 + 2 * 86400
        val header = "upload=0; download=96; total=100; expire=$expiry"
        val id = runBlocking { repo.saveSubscription(null, "提醒验证", server.url("/alerts").toString(), "clash") }
        server.enqueue(MockResponse().setHeader("subscription-userinfo", header))
        runBlocking { repo.refresh(id) }
        rule.waitUntil(10000) { manager.activeNotifications.size == 2 }
        val firstPosts = manager.activeNotifications.associate { it.id to it.postTime }
        server.enqueue(MockResponse().setHeader("subscription-userinfo", header))
        runBlocking { repo.refresh(id) }
        assertEquals(firstPosts, manager.activeNotifications.associate { it.id to it.postTime })
        val restored = EncryptedBoardStore(app).read().subscriptions.single()
        assertEquals(setOf(80, 95), restored.alerts.usage)
        assertEquals(setOf(7, 3), restored.alerts.expiry)
        manager.cancelAll()
    }

    @Test fun materialThemesAndHapticsPreferencesPersistImmediately() {
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("theme-DARK").performClick()
        rule.waitUntil { repo.state.value.data.preferences.themeMode == ThemeMode.DARK }
        rule.waitForIdle()
        assertTrue(rule.onRoot().captureToImage().toPixelMap()[1, 1].luminance() < 0.2f)
        capture("07-material-dark")
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            rule.onNodeWithTag("dynamic-colors").performScrollTo().performClick()
            rule.waitUntil { !repo.state.value.data.preferences.dynamicColors }
        }
        rule.onNodeWithTag("theme-LIGHT").performScrollTo().performClick()
        rule.waitUntil { repo.state.value.data.preferences.themeMode == ThemeMode.LIGHT }
        rule.waitForIdle()
        assertTrue(rule.onRoot().captureToImage().toPixelMap()[1, 1].luminance() > 0.8f)
        capture("08-material-light")
        rule.onNodeWithTag("haptics-switch").performScrollTo().performClick()
        rule.waitUntil { !repo.state.value.data.preferences.hapticsEnabled }
        assertEquals(repo.state.value.data.preferences, EncryptedBoardStore(app).read().preferences)
        rule.onNodeWithContentDescription("返回").performClick()
        capture("09-home-light")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("theme-LIGHT").assertIsSelected()
        rule.onNodeWithTag("haptics-switch").performScrollTo().assertIsOff()
    }

    @Test fun frostedBarsRemainInteractiveWhileContentScrollsBehindThem() {
        runBlocking {
            repo.savePreferences(UiPreferences(themeMode = ThemeMode.DARK))
            repeat(6) { repo.saveSubscription(null, "毛玻璃滚动验证 ${it + 1}", "https://example.com/$it", "clash") }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("home-list").performTouchInput { swipeUp(startY = centerY, endY = centerY - 240f) }
        capture("10-frosted-top")
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("theme-DARK").assertIsSelected()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithTag("home-list").performScrollToNode(hasText("毛玻璃滚动验证 6"))
        capture("11-frosted-bottom")
        val barY = rule.activity.resources.displayMetrics.density * 60f
        rule.onRoot().performTouchInput { click(Offset(width * .6f, barY)) }
        rule.onNodeWithTag("home-list").assertExists()
        rule.onNodeWithTag("add-subscription").performClick()
        rule.onNodeWithTag("name-input").assertExists()
    }

    @Test fun realBackdropBlurReducesStripeContrastAndFallbackStillRenders() {
        fun variance(blur: Boolean): Double {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BoardTheme(UiPreferences(themeMode = ThemeMode.DARK)) {
                        val layer = rememberGraphicsLayer()
                        val source = remember(layer) { BackdropSource(layer) }
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize().backdropSource(source)) {
                                val stripe = 4.dp.toPx()
                                for (i in 0..(size.width / stripe).toInt()) drawRect(if (i % 2 == 0) Color.White else Color.Black, Offset(i * stripe, 0f), Size(stripe, size.height))
                            }
                            FrostedBar(source, top = true, blurSupported = blur) { Spacer(Modifier.height(128.dp)) }
                        }
                    }
                }
            }
            rule.waitForIdle()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            capture(if (blur) "14-blur-debug" else "13-fallback-debug")
            val samples = (20 until pixels.width - 20).map { pixels[it, 64].red.toDouble() }
            val mean = samples.average()
            return samples.map { (it - mean) * (it - mean) }.average()
        }
        val fallback = variance(false)
        assertTrue("Fallback should preserve the translucent background", fallback > .005)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue("Blur should reduce high-frequency detail", variance(true) < fallback * .25)
    }

    @Test fun progressiveBlurIsOpaqueAndChangesContinuouslyAcrossTheWholeFade() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val density = rule.activity.resources.displayMetrics.density
        rule.runOnUiThread {
            rule.activity.setContent {
                BoardTheme(UiPreferences(themeMode = ThemeMode.DARK, dynamicColors = false)) {
                    val layer = rememberGraphicsLayer()
                    val source = remember(layer) { BackdropSource(layer) }
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().backdropSource(source)) {
                            val stripe = 12.dp.toPx()
                            for (i in 0..(size.width / stripe).toInt()) drawRect(if (i % 2 == 0) Color.White else Color.Black, Offset(i * stripe, 0f), Size(stripe, size.height))
                        }
                        FrostedBar(source, top = true) { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        fun row(y: Int): Pair<Double, Double> {
            val values = (pixels.width / 5 until pixels.width * 4 / 5).map { pixels[it, y].red.toDouble() }
            val mean = values.average()
            return mean to values.map { (it - mean) * (it - mean) }.average()
        }
        val profile = ((80 * density).toInt()..(144 * density).toInt()).map { row(it) }
        assertTrue("Strong glass must hide sharp strokes", row((40 * density).toInt()).second < .005)
        listOf(1, 8, 16, 32).forEach { dp -> assertTrue("Screen edge must not leak sharp strokes at $dp dp", row((dp * density).toInt()).second < .005) }
        assertTrue("Transition must meet the original content", profile.last().second > .12)
        assertTrue("No abrupt grey stripe in the transition", profile.zipWithNext().all { (a, b) -> kotlin.math.abs(a.first - b.first) < .025 })
        assertTrue("Detail must recover continuously", profile.zipWithNext().all { (a, b) -> b.second + .006 >= a.second })
        capture("12-gradient-proof")
    }

    private fun addThroughUi(name: String, url: String) {
        rule.onNodeWithTag("add-subscription").performClick()
        rule.onNodeWithTag("name-input").performTextInput(name)
        rule.onNodeWithTag("url-input").performTextInput(url)
        rule.onNodeWithText("保存并刷新").performScrollTo().performClick()
    }
    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        // Surface composition and native transitions use the real clock, outside Compose's test clock.
        Thread.sleep(400)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val directory = File(app.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
