package com.hamseda.walkie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hamseda.walkie.MainActivity
import com.hamseda.walkie.R
import com.hamseda.walkie.audio.AudioEngine
import com.hamseda.walkie.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the voice session.
 *
 * The microphone is only ever opened while this service runs in the
 * foreground with the `microphone` service type, started from a
 * user-initiated flow (connect / push-to-talk). An ongoing notification
 * with a Stop action is shown for the whole session; stopping the session
 * (from the UI or the notification) ends the session, wipes keys, stops
 * audio/transport, and removes the service.
 */
class VoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var audioEngine: AudioEngine
    lateinit var sessionManager: SessionManager
        private set

    inner class LocalBinder : Binder() {
        fun getManager(): SessionManager = sessionManager
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audioEngine = AudioEngine(this)
        sessionManager = SessionManager(serviceScope, audioEngine)
        // Apply the user's speakerphone preference to the audio engine.
        val settings = com.hamseda.walkie.data.SettingsRepository(this)
        serviceScope.launch {
            settings.speakerphoneFlow.collect { audioEngine.setSpeakerphone(it) }
        }
        serviceScope.launch {
            sessionManager.phase.collect { phase ->
                if (phase == SessionManager.Phase.IDLE) {
                    stopForegroundCompat()
                } else {
                    ensureForeground(phase)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                serviceScope.launch {
                    sessionManager.endSession()
                }
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_START -> ensureForeground(sessionManager.phase.value)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try {
            sessionManager.close()
        } catch (_: Exception) {}
        try {
            audioEngine.release()
        } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(phase: SessionManager.Phase) {
        try {
            val notification = buildNotification(phase)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground rejected: ${e.message}")
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(phase: SessionManager.Phase): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (phase) {
            SessionManager.Phase.CONNECTING_TRANSPORT -> getString(R.string.notif_connecting)
            SessionManager.Phase.HANDSHAKE -> getString(R.string.notif_handshake)
            SessionManager.Phase.AWAITING_SAS_CONFIRM -> getString(R.string.notif_verify)
            SessionManager.Phase.IN_SESSION -> getString(R.string.notif_in_session)
            else -> getString(R.string.notif_active)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notif_stop),
                stopIntent,
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "HamSedaService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "hamseda_session"

        const val ACTION_START = "com.hamseda.walkie.action.START"
        const val ACTION_STOP = "com.hamseda.walkie.action.STOP"

        /** Starts the service (it foregrounds itself when a session begins). */
        fun start(context: android.content.Context) {
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, VoiceService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
