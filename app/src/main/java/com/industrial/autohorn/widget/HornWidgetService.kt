package com.industrial.autohorn.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.industrial.autohorn.R
import com.industrial.autohorn.audio.HornAudioEngineFactory
import com.industrial.autohorn.audio.HornSession
import com.industrial.autohorn.audio.HornSessionFactory
import com.industrial.autohorn.data.HornPreferencesRepositoryFactory
import com.industrial.autohorn.haptic.AndroidHornHaptics
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One-tap widget/tile playback. A foreground mediaPlayback service is the only reliable way to
 * produce a short horn burst from a background tap without violating Android background limits.
 */
class HornWidgetService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: HornSession? = null
    private var playJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_horn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.widget_playing))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        playJob = scope.launch { playOnce() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        playJob?.cancel()
        scope.cancel()
        runCatching {
            session?.stop()
            session?.release()
        }
        session = null
        running.set(false)
        super.onDestroy()
    }

    private suspend fun playOnce() {
        var engine: HornSession? = null
        try {
            val repository = HornPreferencesRepositoryFactory.create(applicationContext)
            val type = runCatching { repository.selectedHorn.first() }.getOrDefault(
                com.industrial.autohorn.audio.HornType.CAR,
            )
            engine = HornSessionFactory.create(
                context = applicationContext,
                engine = HornAudioEngineFactory.create(),
                haptics = AndroidHornHaptics(applicationContext),
            )
            session = engine
            if (engine.start(type) == com.industrial.autohorn.audio.HornStartResult.GRANTED) {
                delay(HONK_DURATION_MS)
                engine.stop()
            }
        } catch (_: Exception) {
            // A widget tap must never crash the host; the notification simply disappears.
        } finally {
            runCatching { engine?.release() }
            session = null
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.widget_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "horn_widget"
        private const val NOTIFICATION_ID = 0x484F524E
        private const val HONK_DURATION_MS = 1_200L
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun start(context: Context) {
            val intent = Intent(context, HornWidgetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
