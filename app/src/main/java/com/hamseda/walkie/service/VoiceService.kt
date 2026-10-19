package com.hamseda.walkie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hamseda.walkie.MainActivity
import com.hamseda.walkie.R
import com.hamseda.walkie.audio.AudioEngine
import com.hamseda.walkie.crypto.AndroidPeerIdentityStore
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private var sessionCpuWakeLock: PowerManager.WakeLock? = null
    lateinit var sessionManager: SessionManager
        private set
    lateinit var messagingEngine: MessagingEngine
        private set

    inner class LocalBinder : Binder() {
        fun getManager(): SessionManager = sessionManager
        fun getMessaging(): MessagingEngine = messagingEngine
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audioEngine = AudioEngine(this)
        sessionManager = SessionManager(
            serviceScope,
            audioEngine,
            peerIdentityStore = AndroidPeerIdentityStore(this),
        )
        // Apply the user's speakerphone preference to the audio engine.
        val settings = com.hamseda.walkie.data.SettingsRepository(this)
        serviceScope.launch {
            settings.speakerphoneFlow.collect { audioEngine.setSpeakerphone(it) }
        }
        // Offline text messaging rides the same encrypted session pipes.
        messagingEngine = MessagingEngine(this, settings, serviceScope)
        messagingEngine.attach(sessionManager)
        serviceScope.launch {
            sessionManager.phase.collect { phase ->
                if (phase == SessionManager.Phase.IDLE) {
                    setSessionCpuAwake(false)
                    stopForegroundCompat()
                } else {
                    // Listening/handshake phases must not hold the CPU awake
                    // indefinitely. Audio processing needs the partial wake lock
                    // only after both peers have confirmed the authenticated session.
                    setSessionCpuAwake(phase == SessionManager.Phase.IN_SESSION)
                    ensureForeground(phase)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                // endSession() performs asynchronous flush + teardown. Do not
                // stop the Service immediately: onDestroy cancels serviceScope
                // and could interrupt transport disconnection before it runs.
                serviceScope.launch {
                    if (sessionManager.phase.value != SessionManager.Phase.IDLE &&
                        sessionManager.phase.value != SessionManager.Phase.ENDED
                    ) {
                        sessionManager.endSession()
                    }
                    withTimeoutOrNull(15_000L) {
                        sessionManager.phase.first { it == SessionManager.Phase.IDLE }
                    }
                    stopForegroundCompat()
                    stopSelf(startId)
                }
            }
            ACTION_START -> ensureForeground(sessionManager.phase.value)
        }
        // NOT_STICKY: if the system kills the service, do not auto-resurrect
        // it with a null intent. The UI restarts the service explicitly when
        // the user wants voice; a phantom restart could hold the mic and the
        // foreground notification without user intent.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Keep the CPU wake lock held for the whole teardown: close()'s
        // transport disconnect, executor drain, and shutdown run
        // asynchronously on the manager's cleanup scope, and releasing the
        // lock first would let Doze sleep the device mid-teardown — the
        // best-effort disconnect might never be sent and the peer would wait
        // out the full liveness timeout instead of seeing a fast hangup.
        // The lock is released via onCleanupDone when that work completes
        // (wake locks die with the process, so no leak if it never runs).
        try {
            sessionManager.close(onCleanupDone = { setSessionCpuAwake(false) })
        } catch (e: Exception) { Log.w(TAG, "onDestroy: session close failed: ${e.javaClass.simpleName}") }
        try {
            audioEngine.release()
        } catch (e: Exception) { Log.w(TAG, "onDestroy: audio release failed: ${e.javaClass.simpleName}") }
        try {
            messagingEngine.detach()
        } catch (e: Exception) { Log.w(TAG, "onDestroy: messaging detach failed: ${e.javaClass.simpleName}") }
        serviceScope.cancel()
        super.onDestroy()
    }

    @Synchronized
    @SuppressLint("WakelockTimeout")
    private fun setSessionCpuAwake(awake: Boolean) {
        if (awake) {
            val lock = sessionCpuWakeLock ?: run {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HamSeda:VoiceSession")
                    .apply { setReferenceCounted(false) }
                    .also { sessionCpuWakeLock = it }
            }
            try {
                if (!lock.isHeld) {
                    lock.acquire()
                    AppLog.log(TAG, "partial CPU wake lock acquired for active voice session")
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not acquire session wake lock: ${e.message}")
            }
        } else {
            val lock = sessionCpuWakeLock
            if (lock != null) {
                try {
                    if (lock.isHeld) lock.release()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "could not release session wake lock: ${e.message}")
                } finally {
                    sessionCpuWakeLock = null
                }
                AppLog.log(TAG, "partial CPU wake lock released")
            }
        }
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
            // L7: startService() throws IllegalStateException if the app is
            // in the background and the service isn't running. Swallow it:
            // there's nothing to stop in that case.
            try {
                context.startService(
                    Intent(context, VoiceService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop ignored: ${e.message}")
            }
        }
    }
}
