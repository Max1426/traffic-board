package com.liuliang.board.data

import com.liuliang.board.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BoardState(
    val data: BoardData = BoardData(),
    val initialized: Boolean = false,
    val storageError: String? = null,
    val refreshing: Set<String> = emptySet(),
)

fun interface AlertSender { fun send(sub: Subscription, event: AlertEvent): Boolean }

class BoardRepository(
    private val store: BoardStore,
    private val source: SubscriptionSource,
    private val sender: AlertSender,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val allowLocalHttp: Boolean = false,
) {
    private val mutex = Mutex()
    private val requestMutexes = ConcurrentHashMap<String, Mutex>()
    private val mutableState = MutableStateFlow(BoardState())
    val state: StateFlow<BoardState> = mutableState

    suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock {
        if (!mutableState.value.initialized) {
            mutableState.value = try { BoardState(data = store.read(), initialized = true) }
            catch (e: Exception) { BoardState(initialized = true, storageError = "无法读取本地加密数据。请重新打开应用；原文件已保留，未覆盖。") }
        }
    } }

    private fun commit(data: BoardData) {
        check(mutableState.value.storageError == null) { "加密数据不可用，无法保存" }
        store.write(data)
        mutableState.value = mutableState.value.copy(data = data)
    }

    suspend fun saveSubscription(id: String?, name: String, url: String, ua: String): String = withContext(Dispatchers.IO) {
        initialize()
        val cleanName = name.trim(); val cleanUrl = url.trim(); val cleanUa = ua.trim()
        InputRules.validate(cleanName, cleanUrl, cleanUa, allowLocalHttp)?.let { throw IllegalArgumentException(it) }
        mutex.withLock {
            val data = mutableState.value.data
            require(data.subscriptions.none { it.url == cleanUrl && it.id != id }) { "这个订阅链接已经添加过了" }
            val old = data.subscriptions.find { it.id == id }
            if (id != null) require(old != null) { "此订阅已被删除" }
            val changedSource = old != null && (old.url != cleanUrl || old.userAgent != cleanUa)
            val sub = if (old == null || changedSource) Subscription(old?.id ?: UUID.randomUUID().toString(), cleanName, cleanUrl, cleanUa, sourceRevision = if (old == null) 0 else old.sourceRevision + 1)
                else old.copy(name = cleanName)
            commit(data.copy(subscriptions = if (old == null) data.subscriptions + sub else data.subscriptions.map { if (it.id == sub.id) sub else it }))
            sub.id
        }
    }

    suspend fun deleteSubscription(id: String) = withContext(Dispatchers.IO) { initialize(); mutex.withLock {
        commit(mutableState.value.data.let { it.copy(subscriptions = it.subscriptions.filterNot { sub -> sub.id == id }) })
    } }

    suspend fun savePreferences(preferences: UiPreferences) = withContext(Dispatchers.IO) { initialize(); mutex.withLock {
        commit(mutableState.value.data.copy(preferences = preferences))
    } }

    suspend fun saveSettings(settings: AlertSettings) = withContext(Dispatchers.IO) { initialize(); mutex.withLock {
        require(settings.usageThresholds.isNotEmpty() && settings.usageThresholds.size <= 8 && settings.usageThresholds.all { it in 1..100 })
        require(settings.expiryDays.isNotEmpty() && settings.expiryDays.size <= 8 && settings.expiryDays.all { it in 0..365 })
        val normalized = settings.copy(usageThresholds = settings.usageThresholds.distinct().sorted(), expiryDays = settings.expiryDays.distinct().sortedDescending())
        val old = mutableState.value.data
        // Keep delivered levels that still exist. New thresholds become eligible on the next successful refresh.
        commit(old.copy(settings = normalized, subscriptions = old.subscriptions.map {
            it.copy(alerts = it.alerts.copy(usage = it.alerts.usage.intersect(normalized.usageThresholds.toSet()),
                expiry = it.alerts.expiry.intersect((normalized.expiryDays + 0).toSet())))
        }))
    } }

    suspend fun refreshAll(onlyStale: Boolean = false) {
        initialize()
        if (state.value.storageError != null) return
        val ids = state.value.data.subscriptions.filter {
            !onlyStale || it.lastSuccessAt == null || now() - it.lastSuccessAt >= SIX_HOURS || it.lastSuccessAt > now()
        }.map { it.id }
        ids.forEach { refresh(it) }
    }

    suspend fun refresh(id: String) = requestMutexes.computeIfAbsent(id) { Mutex() }.withLock { refreshLocked(id) }

    private suspend fun refreshLocked(id: String) = withContext(Dispatchers.IO) {
        initialize()
        val target = mutex.withLock {
            val state = mutableState.value
            if (state.storageError != null || id in state.refreshing) return@withContext
            val sub = state.data.subscriptions.find { it.id == id } ?: return@withContext
            mutableState.value = state.copy(refreshing = state.refreshing + id)
            sub
        }
        try {
            val result = source.fetch(target.url, target.userAgent)
            ensureActive()
            mutex.withLock {
                val data = mutableState.value.data
                val current = data.subscriptions.find { it.id == id } ?: return@withLock
                // Never attach the result of an old URL/UA to an edited subscription.
                if (current.sourceRevision != target.sourceRevision || current.url != target.url || current.userAgent != target.userAgent) return@withLock
                val time = now()
                var updated = when (result) {
                    is FetchResult.Failure -> current.copy(lastAttemptAt = time, status = result.status)
                    is FetchResult.Success -> current.copy(info = result.info, lastSuccessAt = time, lastAttemptAt = time,
                        status = when { result.info == null -> RefreshStatus.NO_INFO; result.info.complete -> RefreshStatus.READY; else -> RefreshStatus.PARTIAL })
                }
                if (result is FetchResult.Success && result.info != null) {
                    val evaluation = AlertEngine.evaluate(updated, data.settings, time)
                    var memory = evaluation.memory
                    for (event in evaluation.events) if (sender.send(updated, event)) memory = AlertEngine.mark(memory, event)
                    updated = updated.copy(alerts = memory)
                }
                commit(data.copy(subscriptions = data.subscriptions.map { if (it.id == id) updated else it }))
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { mutableState.value = mutableState.value.copy(refreshing = mutableState.value.refreshing - id) } }
        }
    }
    companion object { const val SIX_HOURS = 6 * 60 * 60L }
}
