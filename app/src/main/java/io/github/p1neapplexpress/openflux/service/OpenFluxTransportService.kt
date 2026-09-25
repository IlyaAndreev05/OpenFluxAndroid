package io.github.p1neapplexpress.openflux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.ui.MainActivity

/** Local TCP relay for Happ/Xray. It deliberately does not extend VpnService. */
class OpenFluxTransportService : Service() {
    companion object {
        const val ACTION_START = "io.github.p1neapplexpress.openflux.transport.START"
        const val ACTION_STOP = "io.github.p1neapplexpress.openflux.transport.STOP"
        const val EXTRA_ARGS = "openflux_args"
        const val EXTRA_KEY = "openflux_key"
        private const val CHANNEL_ID = "openflux-local-transport"
        private const val NOTIFICATION_ID = 2302
    }

    private lateinit var supervisor: NativeProcessSupervisor

    override fun onCreate() {
        super.onCreate()
        supervisor = NativeProcessSupervisor(applicationContext) { message ->
            EventBus.dispatch(AppEvent.NativeProcessExited(message))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        val args = intent?.getStringArrayListExtra(EXTRA_ARGS)
        if (args.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val key = intent.getStringExtra(EXTRA_KEY)
        supervisor.start(args, key)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        supervisor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.local_transport_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val openApp = PendingIntent.getActivity(
            this, 2302, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.local_transport_title))
            .setContentText(getString(R.string.local_transport_msg))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
