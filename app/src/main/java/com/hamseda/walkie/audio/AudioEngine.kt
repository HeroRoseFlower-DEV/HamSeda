package com.hamseda.walkie.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * Low-latency voice capture/playback engine.
 *
 * - Capture: [AudioRecord] with `VOICE_COMMUNICATION` source, 16 kHz mono
 *   PCM16, read in exact 20 ms (320-sample) chunks on a dedicated thread —
 *   never the main thread. Encoded frames are delivered via [onFrame].
 * - Playback: [AudioTrack] in streaming mode fed from a [JitterBuffer];
 *   lost frames are concealed by repeating the last frame with decay
 *   (max 5), then silence.
 * - Audio focus: transient focus is requested when capture starts; a focus
 *   loss (e.g. an incoming call) stops transmission via [Listener].
 * - Effects: AcousticEchoCanceler / NoiseSuppressor / AutomaticGainControl
 *   are enabled only when the platform reports them available.
 * - Routing: `MODE_IN_COMMUNICATION`; speakerphone on by default
 *   (walkie-talkie style), toggleable; headset plug/unplug is observed.
 *
 * Every started component has a matching stop path; [release] is idempotent.
 */
class AudioEngine(private val context: Context) : AudioPipeline {

    enum class AudioRoute { SPEAKER, EARPIECE, WIRED_HEADSET, BLUETOOTH_SCO }

    override var pipelineListener: AudioPipeline.PipelineListener? = null

    /** Detailed route updates for the UI (beyond the pipeline contract). */
    var routeListener: ((AudioRoute) -> Unit)? = null

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val captureRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var recorder: AudioRecord? = null

    private val playbackRunning = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private var track: AudioTrack? = null

    private var focusRequest: AudioFocusRequest? = null
    /** Audio routing is global to the device; restore the previous state when this app stops audio. */
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null
    private var effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @Volatile
    var speakerphoneOn: Boolean = true
        private set

    @Volatile
    var currentRoute: AudioRoute = AudioRoute.SPEAKER
        private set

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            Log.w(TAG, "audio focus lost ($change)")
            pipelineListener?.onAudioFocusLost()
        }
    }

    private val routeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val plugged = intent.getIntExtra("state", 0) == 1
                    updateRoute(if (plugged) AudioRoute.WIRED_HEADSET else guessRoute())
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    updateRoute(AudioRoute.SPEAKER)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        context.registerReceiver(routeReceiver, filter)
    }

    // -------------------------------------------------------------- capture

    /**
     * Starts microphone capture. [onFrame] is invoked on the capture thread
     * for every 20 ms frame with the encoded bytes and a 0..1 voice level
     * (for the transmit animation — real measured amplitude, not simulated).
     * Returns false if the recorder could not be started.
     */
    override fun startCapture(codec: AudioCodec, onFrame: (encoded: ByteArray, level: Float) -> Unit): Boolean {
        if (captureRunning.get()) return true
        // The UI requests RECORD_AUDIO before PTT, but the engine must not
        // assume it: fail closed with a clear error instead of throwing
        // SecurityException (also satisfies lint's MissingPermission check).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pipelineListener?.onCaptureError("microphone permission not granted")
            return false
        }
        if (!requestFocus()) {
            Log.w(TAG, "could not gain audio focus")
        }
        val minBuf = AudioRecord.getMinBufferSize(
            AudioCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            abandonFocus()
            pipelineListener?.onCaptureError("microphone unavailable (min buffer=$minBuf)")
            return false
        }
        val format = AudioFormat.Builder()
            .setSampleRate(AudioCodec.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        // Note: AudioRecord.Builder has no setAudioAttributes — routing and
        // usage are driven by the VOICE_COMMUNICATION audio source.
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Exception) {
            abandonFocus()
            pipelineListener?.onCaptureError("microphone unavailable: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            abandonFocus()
            pipelineListener?.onCaptureError("microphone unavailable (init failed)")
            return false
        }
        attachEffects(rec.audioSessionId)
        recorder = rec
        captureRunning.set(true)
        applyAudioMode(force = true)
        captureThread = Thread({
            val frame = ShortArray(AudioCodec.FRAME_SAMPLES)
            try {
                rec.startRecording()
            } catch (e: IllegalStateException) {
                pipelineListener?.onCaptureError("microphone start failed: ${e.message}")
                captureRunning.set(false)
                return@Thread
            }
            // HS-09: classify every negative read result. ERROR_DEAD_OBJECT
            // and invalid-operation are fatal (stop, don't spin); transient
            // errors back off with a bounded retry count.
            var consecutiveErrors = 0
            while (captureRunning.get()) {
                val read = rec.read(frame, 0, frame.size)
                if (read < 0) {
                    consecutiveErrors++
                    when (read) {
                        AudioRecord.ERROR_DEAD_OBJECT -> {
                            Log.w(TAG, "AudioRecord dead object; stopping capture")
                            pipelineListener?.onCaptureError("microphone disconnected")
                            captureRunning.set(false)
                            break
                        }
                        AudioRecord.ERROR_INVALID_OPERATION,
                        AudioRecord.ERROR_BAD_VALUE -> {
                            pipelineListener?.onCaptureError("microphone read error ($read)")
                            captureRunning.set(false)
                            break
                        }
                        else -> {
                            Log.w(TAG, "AudioRecord.read error: $read")
                            if (consecutiveErrors > MAX_CONSECUTIVE_READ_ERRORS) {
                                pipelineListener?.onCaptureError(
                                    "microphone read error ($read)"
                                )
                                captureRunning.set(false)
                                break
                            }
                            try {
                                Thread.sleep(READ_ERROR_BACKOFF_MS)
                            } catch (_: InterruptedException) {
                                break
                            }
                            continue
                        }
                    }
                }
                consecutiveErrors = 0
                if (read != frame.size) continue // wait for a full 20 ms frame
                var peak = 0
                for (s in frame) peak = maxOf(peak, abs(s.toInt()))
                val level = min(1f, peak / 32768f)
                try {
                    onFrame(codec.encode(frame), level)
                } catch (e: Exception) {
                    Log.w(TAG, "encode failed: ${e.message}")
                }
            }
        }, "HamSeda-Capture").also { it.start() }
        return true
    }

    override fun stopCapture() {
        captureRunning.set(false)
        val worker = captureThread
        // Audio errors can invoke stopCapture from the capture callback itself.
        // Joining the current thread would stall the callback for the full timeout.
        if (worker != null && worker !== Thread.currentThread()) {
            try { worker.join(500) } catch (_: InterruptedException) {}
        }
        captureThread = null
        try { recorder?.stop() } catch (_: IllegalStateException) {}
        recorder?.release()
        recorder = null
        releaseEffects()
        abandonFocus()
        restoreAudioModeIfIdle()
    }

    override val isCapturing: Boolean get() = captureRunning.get()

    // ------------------------------------------------------------- playback

    /**
     * Starts speaker playback fed from [buffer]. Lost frames are concealed
     * with last-frame repeat (decaying), then silence.
     */
    override fun startPlayback(codec: AudioCodec, buffer: JitterBuffer): Boolean {
        if (playbackRunning.get()) return true
        val minBuf = AudioTrack.getMinBufferSize(
            AudioCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val format = AudioFormat.Builder()
            .setSampleRate(AudioCodec.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val at = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack build failed: ${e.message}")
            return false
        }
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            at.release()
            return false
        }
        applyAudioMode(force = true)
        // Audio playback must start before we report success to SessionManager.
        // Previously the worker thread could fail at AudioTrack.play() only
        // after startPlayback() had already returned true.
        try {
            at.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack play failed: ${e.message}")
            at.release()
            restoreAudioModeIfIdle()
            return false
        }
        track = at
        playbackRunning.set(true)
        playbackThread = Thread({
            val silence = ShortArray(AudioCodec.FRAME_SAMPLES)
            var last: ShortArray? = null
            var plcCount = 0
            while (playbackRunning.get()) {
                when (val r = buffer.takeNext()) {
                    is JitterBuffer.TakeResult.Frame -> {
                        val pcm = try {
                            codec.decode(r.packet.payload)
                        } catch (e: Exception) {
                            Log.w(TAG, "decode failed, concealing")
                            null
                        }
                        if (pcm != null) {
                            at.write(pcm, 0, pcm.size)
                            last = pcm
                            plcCount = 0
                        } else {
                            conceal(at, last, silence, plcCount).also { plcCount = it }
                        }
                    }
                    is JitterBuffer.TakeResult.Lost -> {
                        conceal(at, last, silence, plcCount).also { plcCount = it }
                    }
                    JitterBuffer.TakeResult.NotReady -> {
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }, "HamSeda-Playback").also { it.start() }
        return true
    }

    private fun conceal(
        at: AudioTrack,
        last: ShortArray?,
        silence: ShortArray,
        plcCount: Int,
    ): Int {
        return if (last != null && plcCount < MAX_PLC_FRAMES) {
            val decayed = ShortArray(last.size) { i -> (last[i] * PLC_DECAY).toInt().toShort() }
            at.write(decayed, 0, decayed.size)
            plcCount + 1
        } else {
            at.write(silence, 0, silence.size)
            plcCount + 1
        }
    }

    override fun stopPlayback() {
        playbackRunning.set(false)
        try { playbackThread?.join(500) } catch (_: InterruptedException) {}
        playbackThread = null
        try { track?.stop() } catch (_: IllegalStateException) {}
        track?.release()
        track = null
        restoreAudioModeIfIdle()
    }

    val isPlaying: Boolean get() = playbackRunning.get()

    // ----------------------------------------------------------------- misc

    override fun setSpeakerphone(on: Boolean) {
        speakerphoneOn = on
        applyAudioMode()
    }

    private fun applyAudioMode(force: Boolean = false) {
        // VoiceService is bound for UI state even while idle. Never change
        // device-wide routing until playback/capture actually starts.
        if (!force && !captureRunning.get() && !playbackRunning.get()) return
        try {
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
                previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            audioManager.isSpeakerphoneOn = speakerphoneOn && currentRoute != AudioRoute.WIRED_HEADSET
        } catch (e: SecurityException) {
            Log.w(TAG, "audio mode change rejected: ${e.message}")
        }
    }

    private fun restoreAudioModeIfIdle() {
        if (captureRunning.get() || playbackRunning.get()) return
        val mode = previousAudioMode
        val speaker = previousSpeakerphoneOn
        if (mode == null && speaker == null) return
        try {
            if (speaker != null) audioManager.isSpeakerphoneOn = speaker
            if (mode != null) audioManager.mode = mode
        } catch (e: SecurityException) {
            Log.w(TAG, "audio mode restore rejected: ${e.message}")
        } finally {
            previousAudioMode = null
            previousSpeakerphoneOn = null
        }
    }

    private fun guessRoute(): AudioRoute =
        if (audioManager.isWiredHeadsetOn) AudioRoute.WIRED_HEADSET
        else if (audioManager.isBluetoothScoOn) AudioRoute.BLUETOOTH_SCO
        else if (speakerphoneOn) AudioRoute.SPEAKER else AudioRoute.EARPIECE

    private fun updateRoute(route: AudioRoute) {
        if (route != currentRoute) {
            currentRoute = route
            applyAudioMode()
            routeListener?.invoke(route)
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        focusRequest = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let {
                    it.enabled = true
                    effects.add(it)
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    it.enabled = true
                    effects.add(it)
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let {
                    it.enabled = true
                    effects.add(it)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio effect attach failed: ${e.message}")
        }
    }

    private fun releaseEffects() {
        effects.forEach {
            try {
                it.enabled = false
                it.release()
            } catch (_: Exception) {}
        }
        effects.clear()
    }

    fun release() {
        stopCapture()
        stopPlayback()
        try {
            context.unregisterReceiver(routeReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    companion object {
        private const val TAG = "HamSedaAudio"
        private const val MAX_PLC_FRAMES = 5
        private const val PLC_DECAY = 0.85f
        /** HS-09: bounded retries for transient AudioRecord.read errors. */
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10
        private const val READ_ERROR_BACKOFF_MS = 20L
    }
}
