package com.liuliang.board

import com.liuliang.board.data.*
import com.liuliang.board.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RepositoryTest {
    private class Store(var data: BoardData = BoardData()) : BoardStore {
        var readsFail = false; var writesFail = false; var writes = 0
        override fun read(): BoardData { if (readsFail) throw IOException(); return data }
        override fun write(data: BoardData) { if (writesFail) throw IOException(); this.data = data; writes++ }
    }
    private val now = 1800000000L
    private val valid = TrafficInfo(1, 90, 100, now + 30 * 86400)
    private fun repo(store: Store, source: SubscriptionSource, sender: AlertSender = AlertSender { _, _ -> true }) = BoardRepository(store, source, sender, { now })
    @Test fun failedRefreshPreservesLastGoodSnapshot() = runBlocking {
        var response: FetchResult = FetchResult.Success(valid)
        val repo = repo(Store(), SubscriptionSource { _, _ -> response })
        val id = repo.saveSubscription(null, "日常", "https://example.com/sub", "clash")
        repo.refresh(id)
        val old = repo.state.value.data.subscriptions.single()
        response = FetchResult.Failure(RefreshStatus.TIMEOUT)
        repo.refresh(id)
        val updated = repo.state.value.data.subscriptions.single()
        assertEquals(old.info, updated.info); assertEquals(old.lastSuccessAt, updated.lastSuccessAt)
        assertEquals(RefreshStatus.TIMEOUT, updated.status); assertTrue(repo.state.value.refreshing.isEmpty())
    }
    @Test fun missingHeaderReplacesStatsWithUnknownInsteadOfPretendingOldDataIsCurrent() = runBlocking {
        val store = Store(BoardData(listOf(Subscription("a", "a", "https://example.com", info = valid))))
        val repo = repo(store, SubscriptionSource { _, _ -> FetchResult.Success(null) })
        repo.refresh("a")
        assertNull(repo.state.value.data.subscriptions.single().info)
        assertEquals(RefreshStatus.NO_INFO, repo.state.value.data.subscriptions.single().status)
    }
    @Test fun deniedNotificationDoesNotConsumeThresholdAndSuccessfulDeliveryPersists() = runBlocking {
        val store = Store()
        var allowed = false; var attempts = 0
        val source = SubscriptionSource { _, _ -> FetchResult.Success(valid) }
        val repo = repo(store, source, AlertSender { _, _ -> attempts++; allowed })
        val id = repo.saveSubscription(null, "a", "https://example.com", "clash")
        repo.refresh(id); assertTrue(store.data.subscriptions.single().alerts.usage.isEmpty())
        allowed = true; repo.refresh(id); assertEquals(setOf(80), store.data.subscriptions.single().alerts.usage)
        val restored = repo(store, source, AlertSender { _, _ -> attempts++; true })
        restored.refresh(id); assertEquals(2, attempts)
    }
    @Test fun changedLinkOrUaClearsStatisticsAndRenamePreservesThem() = runBlocking {
        val repo = repo(Store(), SubscriptionSource { _, _ -> FetchResult.Success(valid) })
        val id = repo.saveSubscription(null, "a", "https://example.com", "clash")
        repo.refresh(id); repo.saveSubscription(id, "新名字", "https://example.com", "clash")
        assertEquals(valid, repo.state.value.data.subscriptions.single().info)
        repo.saveSubscription(id, "新名字", "https://example.com", "clash-meta")
        assertNull(repo.state.value.data.subscriptions.single().info)
    }
    @Test fun subscriptionEditedDuringRequestDoesNotReceiveOldResults() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val repo = repo(Store(), SubscriptionSource { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); FetchResult.Success(valid) })
        val id = repo.saveSubscription(null, "a", "https://example.com/old", "clash")
        val request = async(Dispatchers.Default) { repo.refresh(id) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { repo.saveSubscription(id, "a", "https://example.com/new", "clash") } finally { release.countDown() }
        request.await()
        assertNull(repo.state.value.data.subscriptions.single().info)
        assertEquals(RefreshStatus.NEVER, repo.state.value.data.subscriptions.single().status)
    }
    @Test fun deletionDuringRequestDoesNotResurrectSubscription() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val repo = repo(Store(), SubscriptionSource { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); FetchResult.Success(valid) })
        val id = repo.saveSubscription(null, "a", "https://example.com", "clash")
        val request = async(Dispatchers.Default) { repo.refresh(id) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { repo.deleteSubscription(id) } finally { release.countDown() }
        request.await(); assertTrue(repo.state.value.data.subscriptions.isEmpty())
    }
    @Test fun refreshForEditedLinkQueuesBehindOldRequestAndUsesLatestSource() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val newer = TrafficInfo(0, 15, 200, now + 60 * 86400)
        val repo = repo(Store(), SubscriptionSource { url, _ ->
            if (url.endsWith("/old")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); FetchResult.Success(valid) }
            else FetchResult.Success(newer)
        })
        val id = repo.saveSubscription(null, "a", "https://example.com/old", "clash")
        val oldRequest = async(Dispatchers.Default) { repo.refresh(id) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repo.saveSubscription(id, "a", "https://example.com/new", "clash")
        val newRequest = async(Dispatchers.Default) { repo.refresh(id) }
        release.countDown(); oldRequest.await(); newRequest.await()
        assertEquals(newer, repo.state.value.data.subscriptions.single().info)
        assertTrue(repo.state.value.refreshing.isEmpty())
    }
    @Test fun stalePolicyDoesNotRequestFreshSubscription() = runBlocking {
        var calls = 0
        val store = Store(BoardData(listOf(
            Subscription("fresh", "a", "https://a.example", lastSuccessAt = now - 1),
            Subscription("old", "b", "https://b.example", lastSuccessAt = now - BoardRepository.SIX_HOURS),
            Subscription("new", "c", "https://c.example"))))
        val repo = repo(store, SubscriptionSource { _, _ -> calls++; FetchResult.Success(valid) })
        repo.refreshAll(onlyStale = true); assertEquals(2, calls)
    }
    @Test fun failedStorageReadNeverOverwritesOriginal() = runBlocking {
        val store = Store().apply { readsFail = true }
        val repo = repo(store, SubscriptionSource { _, _ -> FetchResult.Success(valid) })
        repo.initialize(); assertNotNull(repo.state.value.storageError)
        try { repo.saveSubscription(null, "a", "https://example.com", "clash"); fail() } catch (e: IllegalStateException) { }
        assertEquals(0, store.writes)
    }
    @Test fun failedStorageWriteDoesNotPublishUnsavedChanges() = runBlocking {
        val store = Store().apply { writesFail = true }
        val repo = repo(store, SubscriptionSource { _, _ -> FetchResult.Success(valid) })
        try { repo.saveSubscription(null, "a", "https://example.com", "clash"); fail() } catch (e: IOException) { }
        assertTrue(repo.state.value.data.subscriptions.isEmpty())
    }
    @Test fun multipleSubscriptionsSettingsAndDeletionSurviveReload() = runBlocking {
        val store = Store()
        val repo = repo(store, SubscriptionSource { _, _ -> FetchResult.Success(valid) })
        val first = repo.saveSubscription(null, "a", "https://a.example", "clash")
        repo.saveSubscription(null, "b", "https://b.example", "clash")
        repo.saveSettings(AlertSettings(false, true, listOf(90, 70), listOf(4, 1)))
        repo.deleteSubscription(first)
        val reloaded = repo(store, SubscriptionSource { _, _ -> FetchResult.Success(valid) }); reloaded.initialize()
        assertEquals(listOf("b"), reloaded.state.value.data.subscriptions.map { it.name })
        assertEquals(listOf(70, 90), reloaded.state.value.data.settings.usageThresholds)
        assertFalse(reloaded.state.value.data.settings.usageEnabled)
    }
    @Test fun duplicateSubscriptionIsRejected() = runBlocking {
        val repo = repo(Store(), SubscriptionSource { _, _ -> FetchResult.Success(valid) })
        repo.saveSubscription(null, "a", "https://example.com", "clash")
        try { repo.saveSubscription(null, "b", "https://example.com", "clash"); fail() } catch (e: IllegalArgumentException) { }
        assertEquals(1, repo.state.value.data.subscriptions.size)
    }
}
