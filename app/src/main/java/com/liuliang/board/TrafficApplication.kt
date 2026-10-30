package com.liuliang.board

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.liuliang.board.data.*
import com.liuliang.board.model.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class TrafficApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: BoardRepository
        private set
    override fun onCreate() {
        super.onCreate()
        val notifier = TrafficNotifier(this)
        repository = BoardRepository(EncryptedBoardStore(this), SubscriptionClient(), notifier, allowLocalHttp = BuildConfig.DEBUG)
        scope.launch { repository.initialize() }
        val work = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(6, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("subscription-refresh-six-hours", ExistingPeriodicWorkPolicy.UPDATE, work)
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = (applicationContext as TrafficApplication).repository
        repo.refreshAll()
        val state = repo.state.value
        if (state.storageError != null) Result.failure()
        else if (state.data.subscriptions.any { it.status in setOf(RefreshStatus.NETWORK_ERROR, RefreshStatus.TIMEOUT, RefreshStatus.SERVER_ERROR) }) Result.retry()
        else Result.success()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.retry() }
}

class TrafficNotifier(private val context: Context) : AlertSender {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init { manager.createNotificationChannel(NotificationChannel(CHANNEL, "流量与到期提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "订阅额度不足或即将到期时提醒"
    }) }
    override fun send(sub: Subscription, event: AlertEvent): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val id = (sub.id + event.kind.name).hashCode()
        val intent = Intent(context, MainActivity::class.java).putExtra("subscriptionId", sub.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${sub.name} · ${if (event.kind == AlertKind.USAGE) "流量提醒" else "到期提醒"}")
            .setContentText(event.message).setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setContentIntent(pending).setAutoCancel(true).build()
        return try { manager.notify(id, notification); true } catch (e: SecurityException) { false }
    }
    companion object { const val CHANNEL = "quota-alerts" }
}
