package com.hamseda.walkie.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.hamseda.walkie.util.AppLog
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import java.util.Locale

/**
 * Low-latency voice capture/playback engine.
 *
 * - Capture: [AudioRecord] with `VOICE_COMMUNICATION` source, 48 kHz mono
 *   PCM16, read in exact 20 ms (960-sample) chunks on a dedicated thread —
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

    // Playback worker owns these aggregates; they distinguish output write stalls
    // from gaps in incoming media, without logging per-frame audio data.
    private var playbackWriteWindowFrames = 0
    private var playbackWriteWindowSucceeded = 0
    private var playbackWriteWindowFailed = 0
    private var playbackWriteWindowOver5Ms = 0
    private var playbackWriteWindowOver20Ms = 0
    private var playbackWriteWindowTotalNanos = 0L
    private var playbackWriteWindowMaxNanos = 0L

    private var focusRequest: AudioFocusRequest? = null
    /** Audio routing is global to the device; restore the previous state when this app stops audio. */
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
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
        // Be explicit on recent Android versions. Keep the fallback for older API levels.
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(routeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(routeReceiver, filter)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "route receiver registration rejected: ${e.javaClass.simpleName}")
        }
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
                .setBufferSizeInBytes(maxOf(minBuf, AudioCodec.FRAME_BYTES_PCM * 3))
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
        AppLog.log(
            TAG,
            "capture initialized: sampleRate=${AudioCodec.SAMPLE_RATE}, frameMs=20, " +
                "bufferBytes=${maxOf(minBuf, AudioCodec.FRAME_BYTES_PCM * 3)}",
        )
        captureThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "could not set capture audio priority: ${e.javaClass.simpleName}")
            }
            val frame = ShortArray(AudioCodec.FRAME_SAMPLES)
            try {
                rec.startRecording()
            } catch (e: IllegalStateException) {
                pipelineListener?.onCaptureError("microphone start failed: ${e.javaClass.simpleName}")
                captureRunning.set(false)
                frame.fill(0)
                return@Thread
            }
            // HS-09: classify every negative read result. ERROR_DEAD_OBJECT
            // and invalid-operation are fatal (stop, don't spin); transient
            // errors back off with a bounded retry count.
            var consecutiveErrors = 0
            var encodeWindowFrames = 0
            var encodeWindowTotalNanos = 0L
            var encodeWindowMaxNanos = 0L
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
                    val encodeStartedAt = System.nanoTime()
                    val encoded = codec.encode(frame)
                    val encodeElapsedNanos = System.nanoTime() - encodeStartedAt
                    encodeWindowFrames++
                    encodeWindowTotalNanos += encodeElapsedNanos
                    encodeWindowMaxNanos = maxOf(encodeWindowMaxNanos, encodeElapsedNanos)
                    if (encodeWindowFrames >= ENCODE_DIAGNOSTIC_INTERVAL) {
                        AppLog.log(
                            TAG,
                            "capture encode timing: frames=$encodeWindowFrames, " +
                                "avgMs=${String.format(Locale.ROOT, "%.2f", encodeWindowTotalNanos / encodeWindowFrames / 1_000_000.0)}, " +
                                "maxMs=${String.format(Locale.ROOT, "%.2f", encodeWindowMaxNanos / 1_000_000.0)}",
                        )
                        encodeWindowFrames = 0
                        encodeWindowTotalNanos = 0L
                        encodeWindowMaxNanos = 0L
                    }
                    try {
                        onFrame(encoded, level)
                    } finally {
                        encoded.fill(0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "encode failed: ${e.javaClass.simpleName}")
                }
            }
            frame.fill(0)
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
        // Select communication mode before constructing AudioTrack: low-latency
        // eligibility depends on the output route's native sample rate.
        applyAudioMode(force = true)
        val nativeOutputRate =
            AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_VOICE_CALL)
        val canRequestLowLatency = nativeOutputRate == AudioCodec.SAMPLE_RATE
        val requestedBufferBytes = maxOf(minBuf, AudioCodec.FRAME_BYTES_PCM * 3)
        fun buildTrack(performanceMode: Int): AudioTrack? = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(requestedBufferBytes)
                .setPerformanceMode(performanceMode)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack build failed (performanceMode=$performanceMode): ${e.message}")
            null
        }

        val preferredMode = if (canRequestLowLatency) {
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        } else {
            AppLog.log(
                TAG,
                "low-latency output skipped: nativeRate=$nativeOutputRate, " +
                    "requestedRate=${AudioCodec.SAMPLE_RATE}",
            )
            AudioTrack.PERFORMANCE_MODE_NONE
        }
        var candidate = buildTrack(preferredMode)
        if ((candidate == null || candidate.state != AudioTrack.STATE_INITIALIZED) &&
            preferredMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        ) {
            candidate?.release()
            candidate = buildTrack(AudioTrack.PERFORMANCE_MODE_NONE)
        }
        val at = candidate
        if (at == null || at.state != AudioTrack.STATE_INITIALIZED) {
            at?.release()
            Log.w(TAG, "AudioTrack could not initialize at ${AudioCodec.SAMPLE_RATE} Hz")
            restoreAudioModeIfIdle()
            return false
        }
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
        playbackWriteWindowFrames = 0
        playbackWriteWindowSucceeded = 0
        playbackWriteWindowFailed = 0
        playbackWriteWindowOver5Ms = 0
        playbackWriteWindowOver20Ms = 0
        playbackWriteWindowTotalNanos = 0L
        playbackWriteWindowMaxNanos = 0L
        playbackRunning.set(true)
        val initialUnderruns = try { at.underrunCount } catch (_: Exception) { -1 }
        val effectiveBufferMs = at.bufferSizeInFrames * 1000.0 / at.sampleRate
        AppLog.log(
            TAG,
            "playback initialized: sampleRate=${at.sampleRate}, nativeOutputRate=$nativeOutputRate, " +
                "minBufferBytes=$minBuf, requestedBufferBytes=$requestedBufferBytes, " +
                "requestedPerformanceMode=$preferredMode, actualPerformanceMode=${at.performanceMode}, " +
                "bufferFrames=${at.bufferSizeInFrames}, capacityFrames=${at.bufferCapacityInFrames}, " +
                "bufferLimitMs=${String.format(Locale.ROOT, "%.1f", effectiveBufferMs)}, " +
                "initialUnderruns=$initialUnderruns, frameMs=20",
        )
        playbackThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "could not set playback audio priority: ${e.javaClass.simpleName}")
            }
            val silence = ShortArray(AudioCodec.FRAME_SAMPLES)
            var last: ShortArray? = null
            var plcCount = 0
            var lastHealthLogNanos = System.nanoTime()
            try {
                playback@ while (playbackRunning.get()) {
                    val healthNowNanos = System.nanoTime()
                    if (healthNowNanos - lastHealthLogNanos >= PLAYBACK_DIAGNOSTIC_INTERVAL_MS * 1_000_000L) {
                        val underruns = try { at.underrunCount } catch (_: Exception) { -1 }
                        AppLog.log(
                            TAG,
                            "playback health: underruns=$underruns, " +
                                "bufferFrames=${at.bufferSizeInFrames}, capacityFrames=${at.bufferCapacityInFrames}, " +
                                "jitterLost=${buffer.lost}, jitterStaleDropped=${buffer.droppedStale}, " +
                                "jitterBuffered=${buffer.bufferedFrames()}",
                        )
                        lastHealthLogNanos = healthNowNanos
                    }
                    when (val r = buffer.takeNext()) {
                        is JitterBuffer.TakeResult.Frame -> {
                            val pcm = try {
                                codec.decode(r.packet.payload)
                            } catch (e: Exception) {
                                Log.w(TAG, "decode failed, concealing")
                                null
                            } finally {
                                r.packet.payload.fill(0)
                            }
                            if (pcm != null) {
                                if (!writePcm(at, pcm)) {
                                    pcm.fill(0)
                                    reportPlaybackWriteFailure()
                                    break@playback
                                }
                                last?.fill(0)
                                last = pcm
                                plcCount = 0
                            } else {
                                val next = conceal(at, last, silence, plcCount)
                                if (next == null) {
                                    reportPlaybackWriteFailure()
                                    break@playback
                                }
                                plcCount = next
                            }
                        }
                        is JitterBuffer.TakeResult.Lost -> {
                            val next = conceal(at, last, silence, plcCount)
                            if (next == null) {
                                reportPlaybackWriteFailure()
                                break@playback
                            }
                            plcCount = next
                            if (buffer.lost % JITTER_DIAGNOSTIC_INTERVAL == 0L) {
                                AppLog.log(
                                    TAG,
                                    "audio jitter diagnostics: lost=" + buffer.lost +
                                        ", staleDropped=" + buffer.droppedStale +
                                        ", buffered=" + buffer.bufferedFrames(),
                                )
                            }
                        }
                        JitterBuffer.TakeResult.NotReady -> {
                            try { Thread.sleep(2) } catch (_: InterruptedException) { break@playback }
                        }
                    }
                }
            } finally {
                last?.fill(0)
                silence.fill(0)
                buffer.reset()
            }
        }, "HamSeda-Playback").also { it.start() }
        return true
    }

    /** Write an entire PCM frame without silently truncating a partial write. */
    private fun writePcm(at: AudioTrack, pcm: ShortArray): Boolean {
        val startedAtNanos = System.nanoTime()
        var succeeded = false
        try {
            var offset = 0
            while (offset < pcm.size && playbackRunning.get()) {
                val written = at.write(
                    pcm,
                    offset,
                    pcm.size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written <= 0) {
                    if (playbackRunning.get()) {
                        AppLog.log(TAG, "AudioTrack write failed: result=$written, offset=$offset, samples=${pcm.size}")
                    }
                    return false
                }
                offset += written
            }
            succeeded = offset == pcm.size
            return succeeded
        } finally {
            recordPlaybackWriteTiming(System.nanoTime() - startedAtNanos, succeeded)
        }
    }

    private fun recordPlaybackWriteTiming(elapsedNanos: Long, succeeded: Boolean) {
        playbackWriteWindowFrames++
        if (succeeded) playbackWriteWindowSucceeded++ else playbackWriteWindowFailed++
        playbackWriteWindowTotalNanos += elapsedNanos.coerceAtLeast(0L)
        playbackWriteWindowMaxNanos = maxOf(playbackWriteWindowMaxNanos, elapsedNanos)
        if (elapsedNanos >= 5_000_000L) playbackWriteWindowOver5Ms++
        if (elapsedNanos >= 20_000_000L) playbackWriteWindowOver20Ms++
        if (playbackWriteWindowFrames < PLAYBACK_WRITE_DIAGNOSTIC_INTERVAL) return

        fun ms(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)
        AppLog.log(
            TAG,
            "AudioTrack write timing: frames=$playbackWriteWindowFrames, " +
                "succeeded=$playbackWriteWindowSucceeded, failed=$playbackWriteWindowFailed, " +
                "avgMs=" + ms(playbackWriteWindowTotalNanos / playbackWriteWindowFrames) + ", " +
                "maxMs=" + ms(playbackWriteWindowMaxNanos) + ", " +
                "over5Ms=$playbackWriteWindowOver5Ms, over20Ms=$playbackWriteWindowOver20Ms",
        )
        playbackWriteWindowFrames = 0
        playbackWriteWindowSucceeded = 0
        playbackWriteWindowFailed = 0
        playbackWriteWindowOver5Ms = 0
        playbackWriteWindowOver20Ms = 0
        playbackWriteWindowTotalNanos = 0L
        playbackWriteWindowMaxNanos = 0L
    }

    private fun reportPlaybackWriteFailure() {
        if (playbackRunning.compareAndSet(true, false)) {
            val reason = "AudioTrack failed during streaming playback"
            AppLog.log(TAG, reason)
            pipelineListener?.onPlaybackError(reason)
        }
    }

    private fun conceal(
        at: AudioTrack,
        last: ShortArray?,
        silence: ShortArray,
        plcCount: Int,
    ): Int? {
        val generated = last != null && plcCount < MAX_PLC_FRAMES
        val pcm = if (generated) {
            ShortArray(last!!.size) { i -> (last[i] * PLC_DECAY).toInt().toShort() }
        } else {
            silence
        }
        return try {
            if (writePcm(at, pcm)) plcCount + 1 else null
        } finally {
            if (generated) pcm.fill(0)
        }
    }

    override fun stopPlayback() {
        playbackRunning.set(false)
        val worker = playbackThread
        if (worker != null && worker !== Thread.currentThread()) {
            try { worker.join(500) } catch (_: InterruptedException) {}
        }
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
        // Keep the logical route in sync with the speaker toggle on API 31+;
        // communication-device routing no longer follows the legacy flag.
        if (currentRoute == AudioRoute.SPEAKER || currentRoute == AudioRoute.EARPIECE) {
            val route = if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE
            if (route != currentRoute) {
                currentRoute = route
                routeListener?.invoke(route)
            }
        }
        applyAudioMode()
    }

    private fun applyAudioMode(force: Boolean = false) {
        // VoiceService is bound for UI state even while idle. Never change
        // device-wide routing until playback/capture actually starts.
        if (!force && !captureRunning.get() && !playbackRunning.get()) return
        try {
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
                @Suppress("DEPRECATION")
                previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
                if (Build.VERSION.SDK_INT >= 31) {
                    previousCommunicationDevice = audioManager.communicationDevice
                }
            }
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (Build.VERSION.SDK_INT >= 31) {
                applyCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn =
                    speakerphoneOn && currentRoute != AudioRoute.WIRED_HEADSET
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "audio mode change rejected: ${e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio routing state rejected: ${e.javaClass.simpleName}")
        }
    }

    /**
     * API 31+ route selection must use communication devices; the legacy
     * isSpeakerphoneOn flag is not authoritative on modern Android/OEM builds.
     */
    @RequiresApi(31)
    private fun applyCommunicationDevice() {
        val devices = try {
            audioManager.availableCommunicationDevices
        } catch (_: Exception) {
            emptyList()
        }
        val preferredTypes = deviceTypesForRoute(currentRoute) +
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val target = preferredTypes.firstNotNullOfOrNull { wantedType ->
            devices.firstOrNull { it.type == wantedType }
        }
        var selected = false
        try {
            if (target != null) {
                selected = audioManager.setCommunicationDevice(target)
                if (!selected) audioManager.clearCommunicationDevice()
            } else {
                audioManager.clearCommunicationDevice()
            }
        } catch (e: Exception) {
            Log.w(TAG, "communication-device selection failed: ${e.javaClass.simpleName}")
            try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
        }
        val actual = try { audioManager.communicationDevice } catch (_: Exception) { null }
        val callVolume = try {
            "${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/" +
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        } catch (_: Exception) {
            "unknown"
        }
        AppLog.log(
            TAG,
            "audio route: requested=$currentRoute, speakerphonePreference=$speakerphoneOn, " +
                "targetType=${target?.type}, actualType=${actual?.type}, selected=$selected, " +
                "availableTypes=${devices.map { it.type }.distinct().sorted()}, voiceCallVolume=$callVolume",
        )
    }

    private fun restoreAudioModeIfIdle() {
        if (captureRunning.get() || playbackRunning.get()) return
        val mode = previousAudioMode
        val speaker = previousSpeakerphoneOn
        val previousDevice = previousCommunicationDevice
        if (mode == null && speaker == null && previousDevice == null) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val available = try {
                    audioManager.availableCommunicationDevices
                } catch (_: Exception) {
                    emptyList()
                }
                val matchingPrevious = previousDevice?.let { previous ->
                    available.firstOrNull { it.id == previous.id }
                }
                val restored = matchingPrevious != null &&
                    try { audioManager.setCommunicationDevice(matchingPrevious) } catch (_: Exception) { false }
                if (!restored) {
                    try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
                }
            } else if (speaker != null) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speaker
            }
            if (mode != null) audioManager.mode = mode
        } catch (e: SecurityException) {
            Log.w(TAG, "audio mode restore rejected: ${e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio mode restore rejected: ${e.javaClass.simpleName}")
        } finally {
            previousAudioMode = null
            previousSpeakerphoneOn = null
            previousCommunicationDevice = null
        }
    }

    private fun guessRoute(): AudioRoute {
        if (Build.VERSION.SDK_INT >= 31) {
            val types = try {
                audioManager.availableCommunicationDevices.map { it.type }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
            return routeFromDeviceTypes(types, speakerphoneOn)
        }
        @Suppress("DEPRECATION")
        return if (audioManager.isWiredHeadsetOn) AudioRoute.WIRED_HEADSET
        else if (audioManager.isBluetoothScoOn) AudioRoute.BLUETOOTH_SCO
        else if (speakerphoneOn) AudioRoute.SPEAKER else AudioRoute.EARPIECE
    }

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
        val status = mutableListOf<String>()

        // Isolate each effect: a vendor failure while creating AEC must not
        // silently prevent NS and AGC from being attempted on this device.
        fun attach(
            name: String,
            available: () -> Boolean,
            create: () -> android.media.audiofx.AudioEffect?,
        ) {
            var candidate: android.media.audiofx.AudioEffect? = null
            try {
                if (!available()) {
                    status += "$name=unavailable"
                    return
                }
                candidate = create()
                if (candidate == null) {
                    status += "$name=create-null"
                    return
                }
                candidate.enabled = true
                if (!candidate.enabled) {
                    status += "$name=not-enabled"
                    candidate.release()
                    candidate = null
                    return
                }
                effects.add(candidate)
                status += "$name=on"
                candidate = null
            } catch (e: Exception) {
                try {
                    candidate?.enabled = false
                    candidate?.release()
                } catch (_: Exception) {}
                status += "$name=error:${e.javaClass.simpleName}"
            }
        }

        attach("aec", { AcousticEchoCanceler.isAvailable() }) {
            AcousticEchoCanceler.create(sessionId)
        }
        attach("ns", { NoiseSuppressor.isAvailable() }) {
            NoiseSuppressor.create(sessionId)
        }
        attach("agc", { AutomaticGainControl.isAvailable() }) {
            AutomaticGainControl.create(sessionId)
        }
        AppLog.log(TAG, "capture input effects: ${status.joinToString()}")
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
        private const val JITTER_DIAGNOSTIC_INTERVAL = 25L
        private const val ENCODE_DIAGNOSTIC_INTERVAL = 50
        private const val PLAYBACK_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val PLAYBACK_WRITE_DIAGNOSTIC_INTERVAL = 50
        /** HS-09: bounded retries for transient AudioRecord.read errors. */
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10
        private const val READ_ERROR_BACKOFF_MS = 20L
    }
}

/** Preferred Android communication-device types for a logical audio route. */
fun deviceTypesForRoute(route: AudioEngine.AudioRoute): List<Int> = when (route) {
    AudioEngine.AudioRoute.WIRED_HEADSET -> listOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )
    AudioEngine.AudioRoute.BLUETOOTH_SCO -> listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    AudioEngine.AudioRoute.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    AudioEngine.AudioRoute.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
}

/** Deterministic API 31+ route classification from currently available devices. */
fun routeFromDeviceTypes(
    types: Set<Int>,
    speakerphoneOn: Boolean,
): AudioEngine.AudioRoute = when {
    AudioDeviceInfo.TYPE_WIRED_HEADSET in types ||
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types -> AudioEngine.AudioRoute.WIRED_HEADSET
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types -> AudioEngine.AudioRoute.BLUETOOTH_SCO
    speakerphoneOn -> AudioEngine.AudioRoute.SPEAKER
    else -> AudioEngine.AudioRoute.EARPIECE
}
