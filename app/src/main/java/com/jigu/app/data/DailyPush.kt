package com.jigu.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jigu.app.MainActivity
import com.jigu.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** 每日推送：WorkManager 周期任务（默认 07:30），通知亮出今日名句 */
object DailyPush {

    const val CHANNEL_ID = "daily_poem"
    private const val WORK_NAME = "daily_poem"
    const val DEFAULT_HOUR = 7
    const val DEFAULT_MINUTE = 30

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "每日一首", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "每天一首诗，跟着节气过日子" }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    /** 固定每日 24h 周期，初始延迟对齐到下一个推送时刻 */
    fun schedule(context: Context, hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE) {
        ensureChannel(context)
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, minute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next)
        val request = PeriodicWorkRequestBuilder<DailyPoemWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class DailyPoemWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!DailyPush.hasPermission(context)) return@withContext Result.success()
        val result = runCatching { PoemOfDay.pick(PoemsDb.get(context), LocalDate.now()) }.getOrNull()
            ?: return@withContext Result.success()
        val poem = result.poem
        val line = poem.famousLine.ifBlank { poem.paragraphs.firstOrNull().orEmpty() }

        DailyPush.ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DailyPush.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(result.badge?.let { "今日 · $it" } ?: "汲古 · 今日一首")
            .setContentText("${poem.title} ｜ $line")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line\n—— ${poem.author}《${poem.title}》"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context)
            .notify(poem.id, notification)
        Result.success()
    }
}
