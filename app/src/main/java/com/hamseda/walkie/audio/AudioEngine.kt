package com.hamseda.walkie.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
 * - Capture: [AudioRecord] with `UNPROCESSED` when supported, otherwise `MIC`, at 48 kHz mono
 *   PCM16, read in exact 20 ms (960-sample) chunks on a dedicated thread —
 *   never the main thread. Encoded frames are delivered via [onFrame].
 * - Playback: [AudioTrack] in streaming mode fed from a [JitterBuffer];
 *   lost frames are concealed by repeating the last frame with decay
 *   (max 5), then silence.
 * - Audio focus: transient focus is requested when capture starts; a focus
 *   loss (e.g. an incoming call) stops transmission via [Listener].
 * - Capture is kept unprocessed by the app: no software gain or attached audio effects.
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
    /** Serializes recorder creation/start/stop so a stale PTT start cannot outlive release. */
    private val captureLifecycleLock = Any()
    @Volatile private var captureGeneration = 0L
    private var captureThread: Thread? = null
    private var recorder: AudioRecord? = null
    /** Generation guard for the playback worker; bumped on every stop. */
    @Volatile private var playbackGeneration = 0L
    /**
     * When stopPlayback gives up on a stuck worker (zombie), the timestamp is
     * recorded here. A later startPlayback may proceed past the zombie once
     * ZOMBIE_GRACE_MS has elapsed; the generation guard keeps the zombie from
     * interfering when it finally wakes.
     */
    @Volatile private var playbackZombieSince = 0L

    private val playbackRunning = AtomicBoolean(false)
    /** Serializes AudioTrack creation/start/stop so teardown cannot race a late startup. */
    private val playbackLifecycleLock = Any()
    private var playbackThread: Thread? = null
    /**
     * Scratch for PLC synthesis in [conceal]. Only touched on the playback
     * worker thread, so no synchronization is needed. Sized for the standard
     * frame; [conceal] falls back to allocation if a frame ever differs.
     */
    private val plcScratch = ShortArray(AudioCodec.FRAME_SAMPLES)
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
    private var playbackWriteWindowWaitedFrames = 0
    private var playbackWriteWindowTotalWaitNanos = 0L
    private var playbackWriteWindowMaxWaitNanos = 0L
    private var playbackWriteWindowDeadlineTimeouts = 0

    // Bounds the application-fed PCM backlog in AudioTrack. Pacing waits are
    // measured separately from time spent inside the native AudioTrack.write().
    private var playbackQueueWindowFrames = 0
    private var playbackQueueWindowWaitedFrames = 0
    private var playbackQueueWindowTotalWaitNanos = 0L
    private var playbackQueueWindowMaxWaitNanos = 0L
    private var playbackQueueWindowMaxObservedFrames = 0L

    private var focusRequest: AudioFocusRequest? = null
    /**
     * Capture and playback each hold audio focus independently; the request
     * is abandoned only when the last holder releases it.
     */
    private var focusHolders = 0
    private val focusLock = Any()
    /** Audio routing is global to the device; restore the previous state when this app stops audio. */
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    @Volatile
    var speakerphoneOn: Boolean = true
        private set
    // Accepted limitation: speakerphone defaults ON with no acoustic echo
    // cancellation. AEC is deliberately not applied — the raw-PCM contract
    // forbids app-level DSP, and Android's VOICE_COMMUNICATION source (which
    // would enable platform AEC) applies undisclosed OEM processing. In
    // practice PTT is half-duplex (floor control), so mic and speaker are
    // rarely open simultaneously; speaker echo during the brief overlap is
    // a known, accepted trade-off.

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

    /** Covers route changes not consistently delivered as headset-plug broadcasts. */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateRoute(guessRoute())
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateRoute(guessRoute())
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
        try {
            audioManager.registerAudioDeviceCallback(
                audioDeviceCallback,
                Handler(Looper.getMainLooper()),
            )
        } catch (e: Exception) {
            Log.w(TAG, "audio device callback registration rejected: ${e.javaClass.simpleName}")
        }
    }

    // -------------------------------------------------------------- capture

    private data class CaptureRecorder(
        val recorder: AudioRecord,
        val sourceName: String,
    )

    /**
     * Some older OEM audio HALs reject UNPROCESSED during construction
     * even though microphone capture via MIC works. Prefer UNPROCESSED capture
     * when the device supports it, then fall back to MIC. Never request the
     * VOICE_COMMUNICATION source because it may select a voice-processed path.
     */
    // startCapture checks RECORD_AUDIO before calling this helper. Keep this annotation
    // scoped to the guarded platform-builder calls rather than suppressing permission
    // diagnostics across the whole AudioEngine.
    @SuppressLint("MissingPermission")
    private fun buildCaptureRecorder(
        format: AudioFormat,
        bufferBytes: Int,
        allowUnprocessed: Boolean = true,
    ): CaptureRecorder? {
        // Android documents UNPROCESSED as a reliable request only when the device
        // advertises support. Requesting it unconditionally can silently behave like
        // DEFAULT/MIC on unsupported devices, so do not label that path "raw".
        val unprocessedAdvertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                    ?.equals("true", ignoreCase = true) == true
            } catch (e: Exception) {
                Log.w(TAG, "could not query UNPROCESSED source support: ${e.javaClass.simpleName}")
                false
            }
        } else {
            false
        }
        val sources = buildList {
            if (allowUnprocessed && unprocessedAdvertised) {
                add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            }
            add(MediaRecorder.AudioSource.MIC to "MIC_FALLBACK")
        }
        for ((source, sourceName) in sources) {
            val candidate = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord build failed for source=$sourceName: ${e.javaClass.simpleName}")
                null
            }
            if (candidate == null) continue
            val initialized = try {
                candidate.state == AudioRecord.STATE_INITIALIZED
            } catch (_: Exception) {
                false
            }
            if (initialized) {
                AppLog.log(
                    TAG,
                    "capture source selected: source=$sourceName, " +
                        "unprocessedAdvertised=$unprocessedAdvertised",
                )
                return CaptureRecorder(candidate, sourceName)
            }
            try { candidate.release() } catch (_: Exception) {}
            Log.w(TAG, "AudioRecord source did not initialize: $sourceName")
        }
        return null
    }

    /**
     * Starts microphone capture. [onFrame] is invoked on the capture thread
     * for every 20 ms frame with the encoded bytes and a 0..1 voice level
     * (for the transmit animation — real measured amplitude, not simulated).
     * Returns false if the recorder could not be started.
     */
    override fun startCapture(codec: AudioCodec, onFrame: (encoded: ByteArray, level: Float) -> Unit): Boolean =
        synchronized(captureLifecycleLock) {
            if (captureRunning.get()) return@synchronized true
            startCaptureLocked(codec, onFrame)
        }

    private fun startCaptureLocked(
        codec: AudioCodec,
        onFrame: (encoded: ByteArray, level: Float) -> Unit,
    ): Boolean {
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
        // AudioRecord.Builder has no setAudioAttributes; capture source is
        // selected explicitly below to avoid the voice-processed source path.
        // Request ~200 ms HAL capacity up front: AudioRecord reports overruns
        // only via getMetrics(), so headroom is the only defense against
        // silent sample loss when the worker thread is briefly descheduled.
        // Trade-off: a larger HAL buffer adds a few ms of capture latency;
        // 200 ms is the sweet spot for a PTT app (configurePlaybackBuffer()
        // will use a 60 ms application queue only when LOW_LATENCY mode is
        // actually active).
        val requestedBufferBytes = maxOf(
            minBuf,
            AudioCodec.FRAME_BYTES_PCM * CAPTURE_BUFFER_FRAMES,
        )
        val captureRecorder = buildCaptureRecorder(format, requestedBufferBytes)
        if (captureRecorder == null) {
            abandonFocus()
            pipelineListener?.onCaptureError("microphone unavailable (all capture sources failed to initialize)")
            return false
        }
        val rec = captureRecorder.recorder
        recorder = rec
        captureRunning.set(true)
        val generation = ++captureGeneration
        applyAudioMode(force = true)
        AppLog.log(
            TAG,
            "capture initialized: sampleRate=${AudioCodec.SAMPLE_RATE}, frameMs=20, " +
                "bufferBytes=$requestedBufferBytes, source=${captureRecorder.sourceName}, " +
                "audioProcessing=disabled, " +
                "opusComplexity=${(codec as? OpusCodec)?.encoderComplexityInUse ?: -1}",
        )
        val worker = Thread({
            try {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                } catch (e: Exception) {
                    Log.w(TAG, "could not set capture audio priority: ${e.javaClass.simpleName}")
                }
            val frame = ShortArray(AudioCodec.FRAME_SAMPLES)
            // The stop path can win immediately after Thread.start(). Check the
            // generation and start the recorder under the same lifecycle lock.
            // If a recorder initializes but refuses to start, retry MIC when the
            // preferred UNPROCESSED source was selected. Keep the fallback inside
            // the lifecycle lock so stop/release cannot race recorder replacement.
            var activeRecorder = rec
            val startedRecording = synchronized(captureLifecycleLock) {
                if (!captureRunning.get() || captureGeneration != generation || recorder !== rec) {
                    false
                } else {
                    try {
                        rec.startRecording()
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "microphone start failed for ${captureRecorder.sourceName}: ${e.javaClass.simpleName}")
                        if (captureRecorder.sourceName != "UNPROCESSED") {
                            false
                        } else {
                            val fallback = buildCaptureRecorder(
                                format,
                                requestedBufferBytes,
                                allowUnprocessed = false,
                            )
                            if (fallback == null) {
                                false
                            } else {
                                try {
                                    fallback.recorder.startRecording()
                                    recorder = fallback.recorder
                                    activeRecorder = fallback.recorder
                                    try { rec.release() } catch (_: Exception) {}
                                    AppLog.log(
                                        TAG,
                                        "capture source switched to MIC_FALLBACK after UNPROCESSED start failure",
                                    )
                                    true
                                } catch (fallbackError: Exception) {
                                    try { fallback.recorder.release() } catch (_: Exception) {}
                                    Log.w(
                                        TAG,
                                        "MIC_FALLBACK start failed: ${fallbackError.javaClass.simpleName}",
                                    )
                                    false
                                }
                            }
                        }
                    }
                }
            }
            if (!startedRecording) {
                synchronized(captureLifecycleLock) {
                    if (captureGeneration == generation && recorder === rec) {
                        // Clean up before notifying listeners; callbacks may
                        // themselves request stopCapture().
                        stopCapture()
                        pipelineListener?.onCaptureError("microphone start failed")
                    }
                }
                frame.fill(0)
                return@Thread
            }
            // HS-09: classify every negative read result. ERROR_DEAD_OBJECT
            // and invalid-operation are fatal (stop, don't spin); transient
            // errors back off with a bounded retry count.
            var consecutiveErrors = 0
            var consecutiveZeroReads = 0
            var encodeWindowFrames = 0
            var encodeWindowTotalNanos = 0L
            var encodeWindowMaxNanos = 0L
            while (captureRunning.get() && captureGeneration == generation) {
                // Fill the whole 20 ms frame. A short positive read is valid;
                // discarding it would drop captured samples and create an audible gap.
                var frameSamplesRead = 0
                while (frameSamplesRead < frame.size &&
                    captureRunning.get() && captureGeneration == generation
                ) {
                    val read = activeRecorder.read(
                        frame,
                        frameSamplesRead,
                        frame.size - frameSamplesRead,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (read < 0) {
                        consecutiveErrors++
                        when (read) {
                            AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.w(TAG, "AudioRecord dead object; stopping capture")
                                reportCaptureFailure(generation, "microphone disconnected")
                                break
                            }
                            AudioRecord.ERROR_INVALID_OPERATION,
                            AudioRecord.ERROR_BAD_VALUE -> {
                                reportCaptureFailure(generation, "microphone read error ($read)")
                                break
                            }
                            else -> {
                                Log.w(TAG, "AudioRecord.read error: $read")
                                if (consecutiveErrors > MAX_CONSECUTIVE_READ_ERRORS) {
                                    reportCaptureFailure(generation, "microphone read error ($read)")
                                    break
                                }
                                try {
                                    Thread.sleep(READ_ERROR_BACKOFF_MS)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                                continue
                            }
                        }
                        // Fatal errors end capture via reportCaptureFailure().
                        break
                    }
                    if (read == 0) {
                        // Zero reads are "no progress yet", not hardware errors:
                        // track them separately so a slow-starting HAL cannot
                        // burn the fatal-error budget.
                        consecutiveZeroReads++
                        if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                            reportCaptureFailure(generation, "microphone read made no progress")
                            break
                        }
                        try {
                            Thread.sleep(READ_ERROR_BACKOFF_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        continue
                    }
                    consecutiveErrors = 0
                    consecutiveZeroReads = 0
                    frameSamplesRead += read
                }
                if (frameSamplesRead != frame.size) continue

                // Diagnostics inspect samples but never alter them before encoding.
                var peak = 0
                for (sample in frame) peak = maxOf(peak, abs(sample.toInt()))
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
                                "maxMs=${String.format(Locale.ROOT, "%.2f", encodeWindowMaxNanos / 1_000_000.0)}, " +
                                "audioProcessing=disabled",
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
            } finally {
                // Safety net: if the worker died (exception) without going
                // through stopCapture, clear the running flag so a stale
                // "capturing" state can never block a later startCapture.
                // stopCapture's own join(500) + clear still runs normally.
                synchronized(captureLifecycleLock) {
                    if (captureGeneration == generation) {
                        if (captureRunning.getAndSet(false)) {
                            Log.w(TAG, "capture worker died unexpectedly; cleared stale running flag")
                            pipelineListener?.onCaptureError("capture worker died")
                        }
                    }
                }
            }
        }, "HamSeda-Capture")
        // Publish the worker before it starts; an immediate audio error must
        // recognize that it is running on the current capture thread.
        captureThread = worker
        worker.start()
        return true
    }

    private fun reportCaptureFailure(generation: Long, message: String) {
        synchronized(captureLifecycleLock) {
            // An old recorder must not stop or report errors against a newer PTT run.
            if (captureGeneration != generation || recorder == null) return
            stopCapture()
            pipelineListener?.onCaptureError(message)
        }
    }

    override fun stopCapture() {
        synchronized(captureLifecycleLock) {
            captureRunning.set(false)
            captureGeneration++
            val worker = captureThread
            captureThread = null
            val activeRecorder = recorder
            recorder = null

            // Stop first to unblock a read, then join. If the worker is blocked
            // on this lifecycle lock before startRecording, don't wait on a
            // thread which cannot proceed until this block exits.
            try { activeRecorder?.stop() } catch (_: IllegalStateException) {}
            if (worker != null && worker !== Thread.currentThread() &&
                worker.state != Thread.State.BLOCKED
            ) {
                try { worker.join(500) } catch (_: InterruptedException) {}
            }
            try { activeRecorder?.release() } catch (_: Exception) {}
            abandonFocus()
            restoreAudioModeIfIdle()
        }
    }

    override val isCapturing: Boolean get() = captureRunning.get()

    // ------------------------------------------------------------- playback

    /**
     * Starts speaker playback fed from [buffer]. Lost frames are concealed
     * with last-frame repeat (decaying), then silence.
     */
    /**
     * Prevent a startup deadlock between the app's freshness queue and Android's
     * AudioTrack streaming start threshold. The platform defaults that threshold
     * to its buffer capacity; if we stop writing below it, playback never starts
     * and playbackHeadPosition cannot advance to release the queue.
     */
    private data class PlaybackBufferConfiguration(
        val effectiveBufferFrames: Int,
        val capacityFrames: Int,
        val startThresholdFrames: Int,
        val startThresholdSource: String,
        val queueLimitFrames: Int,
        val bufferResizeResult: Int,
    )

    private fun configurePlaybackBuffer(at: AudioTrack): PlaybackBufferConfiguration {
        // On devices where LOW_LATENCY was requested but not granted, use a
        // larger PCM headroom to absorb short scheduler/HAL stalls. Only use the
        // small queue when the track actually reports LOW_LATENCY mode.
        val trackedQueueFrames = if (
            at.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        ) LOW_LATENCY_PLAYBACK_QUEUE_FRAMES else MAX_TRACKED_PLAYBACK_QUEUE_FRAMES
        val desiredQueueFrames = AudioCodec.FRAME_SAMPLES * trackedQueueFrames
        val capacityFrames = at.bufferCapacityInFrames.coerceAtLeast(1)
        val requestedBufferFrames = minOf(desiredQueueFrames, capacityFrames)
        val resizeResult = try {
            at.setBufferSizeInFrames(requestedBufferFrames)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack buffer resize failed: ${e.javaClass.simpleName}")
            -1
        }
        val effectiveBufferFrames = try {
            at.bufferSizeInFrames.coerceAtLeast(1)
        } catch (_: Exception) {
            capacityFrames
        }
        if (resizeResult <= 0) {
            AppLog.log(
                TAG,
                "AudioTrack buffer resize unavailable; using compatible queue fallback " +
                    "(requestedFrames=$requestedBufferFrames, effectiveFrames=$effectiveBufferFrames, " +
                    "capacityFrames=$capacityFrames, result=$resizeResult)",
            )
        }

        var startThresholdFrames = effectiveBufferFrames
        var startThresholdSource = "effective-buffer-size"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val requestedThreshold = minOf(
                AudioCodec.FRAME_SAMPLES * 2,
                capacityFrames,
            ).coerceAtLeast(1)
            try {
                at.setStartThresholdInFrames(requestedThreshold)
                startThresholdFrames = at.startThresholdInFrames.coerceAtLeast(1)
                startThresholdSource = "api31+"
            } catch (e: Exception) {
                // Keep the implicit reduced-buffer threshold if the newer API is unavailable.
                Log.w(TAG, "AudioTrack start-threshold tuning failed: ${e.javaClass.simpleName}")
            }
        }

        val queueLimitFrames = queueLimitFramesForStartThreshold(
            desiredQueueFrames = desiredQueueFrames,
            frameSamples = AudioCodec.FRAME_SAMPLES,
            startThresholdFrames = startThresholdFrames,
        )
        return PlaybackBufferConfiguration(
            effectiveBufferFrames = effectiveBufferFrames,
            capacityFrames = capacityFrames,
            startThresholdFrames = startThresholdFrames,
            startThresholdSource = startThresholdSource,
            queueLimitFrames = queueLimitFrames,
            bufferResizeResult = resizeResult,
        )
    }

    override fun startPlayback(codec: AudioCodec, buffer: JitterBuffer): Boolean =
        synchronized(playbackLifecycleLock) {
        if (playbackRunning.get()) return true
        // A failed worker may have stopped playback without going through the
        // normal session teardown. Never overlap workers or reuse a live track.
        val previousWorker = playbackThread
        if (previousWorker?.isAlive == true) {
            // Watchdog: a zombie the stop path already gave up on must not
            // block playback forever. After the grace period, proceed; the
            // generation guard below keeps the zombie from interfering.
            val zombieAge = SystemClock.elapsedRealtime() - playbackZombieSince
            if (playbackZombieSince == 0L || zombieAge < ZOMBIE_GRACE_MS) {
                AppLog.log(TAG, "playback start rejected: previous worker is still terminating")
                return false
            }
            AppLog.log(TAG, "playback start proceeding past zombie worker (age=${zombieAge}ms)")
        }
        playbackThread = null
        track?.let { previousTrack ->
            try { previousTrack.stop() } catch (_: Exception) {}
            try { previousTrack.release() } catch (_: Exception) {}
        }
        track = null

        // Playback holds audio focus too: a session can receive voice without
        // ever starting capture. Refcounted with capture's holder.
        if (!requestFocus()) {
            Log.w(TAG, "could not gain audio focus for playback")
        }

        val minBuf = AudioTrack.getMinBufferSize(
            AudioCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            abandonFocus()
            return false
        }
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
        // Keep the native capacity consistent with the five-frame (100 ms)
        // fallback queue. configurePlaybackBuffer() shrinks it to 60 ms only
        // when Android actually grants LOW_LATENCY mode.
        val requestedBufferBytes = maxOf(
            minBuf,
            AudioCodec.FRAME_BYTES_PCM * MAX_TRACKED_PLAYBACK_QUEUE_FRAMES,
        )
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
        val playbackBuffer = configurePlaybackBuffer(at)
        // Audio playback must start before we report success to SessionManager.
        // Previously the worker thread could fail at AudioTrack.play() only
        // after startPlayback() had already returned true.
        try {
            at.play()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack play failed: ${e.javaClass.simpleName}")
            try { at.release() } catch (_: Exception) {}
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
        playbackWriteWindowWaitedFrames = 0
        playbackWriteWindowTotalWaitNanos = 0L
        playbackWriteWindowMaxWaitNanos = 0L
        playbackWriteWindowDeadlineTimeouts = 0
        playbackQueueWindowFrames = 0
        playbackQueueWindowWaitedFrames = 0
        playbackQueueWindowTotalWaitNanos = 0L
        playbackQueueWindowMaxWaitNanos = 0L
        playbackQueueWindowMaxObservedFrames = 0L
        playbackRunning.set(true)
        val initialUnderruns = try { at.underrunCount } catch (_: Exception) { -1 }
        val effectiveBufferMs = at.bufferSizeInFrames * 1000.0 / at.sampleRate
        AppLog.log(
            TAG,
            "playback initialized: sampleRate=${at.sampleRate}, nativeOutputRate=$nativeOutputRate, " +
                "minBufferBytes=$minBuf, requestedBufferBytes=$requestedBufferBytes, " +
                "requestedPerformanceMode=$preferredMode, actualPerformanceMode=${at.performanceMode}, " +
                "bufferFrames=${playbackBuffer.effectiveBufferFrames}, capacityFrames=${playbackBuffer.capacityFrames}, " +
                "bufferResizeResult=${playbackBuffer.bufferResizeResult}, " +
                "startThresholdFrames=${playbackBuffer.startThresholdFrames}, " +
                "startThresholdSource=${playbackBuffer.startThresholdSource}, " +
                "queueLimitFrames=${playbackBuffer.queueLimitFrames}, " +
                "bufferLimitMs=${String.format(Locale.ROOT, "%.1f", effectiveBufferMs)}, " +
                "initialUnderruns=$initialUnderruns, frameMs=20",
        )
        playbackThread = Thread({
            val generation = playbackGeneration
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "could not set playback audio priority: ${e.javaClass.simpleName}")
            }
            // Track queued PCM rather than relying on OEM buffer capacity:
            // a large track buffer can otherwise absorb a burst and replay stale speech.
            val queueTracker = PlaybackQueueTracker().apply {
                reset(at.playbackHeadPosition)
            }
            val silence = ShortArray(AudioCodec.FRAME_SAMPLES)
            var last: ShortArray? = null
            var plcCount = 0
            var fecRecoveredFrames = 0L
            var fecFallbackFrames = 0L
            var lastHealthLogNanos = System.nanoTime()
            try {
                playback@ while (playbackRunning.get() && playbackGeneration == generation) {
                    val healthNowNanos = System.nanoTime()
                    if (healthNowNanos - lastHealthLogNanos >= PLAYBACK_DIAGNOSTIC_INTERVAL_MS * 1_000_000L) {
                        val underruns = try { at.underrunCount } catch (_: Exception) { -1 }
                        AppLog.log(
                            TAG,
                            "playback health: underruns=$underruns, " +
                                "bufferFrames=${at.bufferSizeInFrames}, capacityFrames=${at.bufferCapacityInFrames}, " +
                                "jitterLost=${buffer.lost}, jitterStaleDropped=${buffer.droppedStale}, " +
                                "jitterBuffered=${buffer.bufferedFrames()}, " +
                                "fecRecovered=$fecRecoveredFrames, fecFallbacks=$fecFallbackFrames",
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
                                if (!writePcm(at, pcm, queueTracker, playbackBuffer.queueLimitFrames)) {
                                    pcm.fill(0)
                                    reportPlaybackWriteFailure()
                                    break@playback
                                }
                                last?.fill(0)
                                last = pcm
                                plcCount = 0
                            } else {
                                val next = conceal(at, queueTracker, last, silence, plcCount, playbackBuffer.queueLimitFrames)
                                if (next == null) {
                                    reportPlaybackWriteFailure()
                                    break@playback
                                }
                                plcCount = next
                            }
                        }
                        is JitterBuffer.TakeResult.Lost -> {
                            val fecPayload = r.nextPacketForFec
                            val recovered = try {
                                fecPayload?.let { codec.decodePreviousFrameFec(it) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Opus FEC recovery failed; using concealment")
                                null
                            } finally {
                                fecPayload?.fill(0)
                            }

                            if (recovered != null) {
                                if (!writePcm(at, recovered, queueTracker, playbackBuffer.queueLimitFrames)) {
                                    recovered.fill(0)
                                    reportPlaybackWriteFailure()
                                    break@playback
                                }
                                last?.fill(0)
                                last = recovered
                                plcCount = 0
                                fecRecoveredFrames++
                            } else {
                                if (fecPayload != null) fecFallbackFrames++
                                val next = conceal(
                                    at, queueTracker, last, silence, plcCount,
                                    playbackBuffer.queueLimitFrames,
                                )
                                if (next == null) {
                                    reportPlaybackWriteFailure()
                                    break@playback
                                }
                                plcCount = next
                            }
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
            } catch (e: Exception) {
                // Do not leave isPlaying=true if a vendor AudioTrack/codec throws
                // outside the normal write-error return path.
                Log.e(TAG, "playback worker terminated unexpectedly: ${e.javaClass.simpleName}", e)
                reportPlaybackWriteFailure()
            } finally {
                playbackRunning.set(false)
                last?.fill(0)
                silence.fill(0)
                buffer.reset()
            }
        }, "HamSeda-Playback").also { it.start() }
        return true
    }

    /**
     * Keep a bounded PCM backlog in AudioTrack: three 20 ms frames when the
     * platform actually grants LOW_LATENCY mode, or five frames (100 ms) when it
     * falls back to the default mode. This gives ordinary output a little more
     * headroom for observed scheduler/HAL stalls while retaining the lower-latency
     * limit on devices that support it. Audio frames waiting here remain subject
     * to the bounded, freshness-first jitter queue.
     */
    private fun writePcm(
        at: AudioTrack,
        pcm: ShortArray,
        queueTracker: PlaybackQueueTracker,
        queueLimitFrames: Int,
    ): Boolean {
        val queueWaitStartedNanos = System.nanoTime()
        var maxObservedQueuedFrames = 0L
        var readyForWrite = false
        var queueWaitTimedOut = false
        while (playbackRunning.get()) {
            val queuedFrames = queueTracker.pendingFrames(at.playbackHeadPosition)
            maxObservedQueuedFrames = maxOf(maxObservedQueuedFrames, queuedFrames)
            if (queuedFrames + pcm.size <= queueLimitFrames) {
                readyForWrite = true
                break
            }
            if (System.nanoTime() - queueWaitStartedNanos >= MAX_PLAYBACK_QUEUE_WAIT_MS * 1_000_000L) {
                queueWaitTimedOut = true
                break
            }
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        val queueWaitNanos = (System.nanoTime() - queueWaitStartedNanos).coerceAtLeast(0L)
        recordPlaybackQueueTiming(queueWaitNanos, maxObservedQueuedFrames, queueLimitFrames)
        if (!readyForWrite) {
            if (queueWaitTimedOut && playbackRunning.get()) {
                AppLog.log(
                    TAG,
                    "AudioTrack queue pacing timed out: queuedFrames=$maxObservedQueuedFrames, " +
                        "queueLimitFrames=$queueLimitFrames, waitLimitMs=$MAX_PLAYBACK_QUEUE_WAIT_MS",
                )
            }
            return false
        }

        // Never let a blocking AudioTrack.write() pin the playback worker for
        // tens of milliseconds. NON_BLOCKING writes are retried with a small
        // bounded backoff; native-call time and submission wait are measured
        // separately so the diagnostics identify which side is stalling.
        var nativeWriteNanos = 0L
        var submissionWaitNanos = 0L
        var timedOut = false
        var succeeded = false
        try {
            val writeDeadlineNanos =
                System.nanoTime() + MAX_AUDIO_TRACK_WRITE_WAIT_MS * 1_000_000L
            var offset = 0
            while (offset < pcm.size && playbackRunning.get()) {
                val callStartedAtNanos = System.nanoTime()
                val written = try {
                    at.write(
                        pcm,
                        offset,
                        pcm.size - offset,
                        AudioTrack.WRITE_NON_BLOCKING,
                    )
                } finally {
                    nativeWriteNanos += (System.nanoTime() - callStartedAtNanos).coerceAtLeast(0L)
                }

                if (written < 0) {
                    if (playbackRunning.get()) {
                        AppLog.log(
                            TAG,
                            "AudioTrack write failed: result=$written, offset=$offset, samples=${pcm.size}",
                        )
                    }
                    return false
                }
                if (written > 0) {
                    queueTracker.recordWrittenFrames(written)
                    offset += written
                }
                // Check the deadline even if the platform keeps returning small
                // positive writes; a partial-progress loop must remain bounded too.
                if (offset < pcm.size && System.nanoTime() >= writeDeadlineNanos) {
                    timedOut = true
                    AppLog.log(
                        TAG,
                        "AudioTrack non-blocking write deadline exceeded: offset=$offset, " +
                            "samples=${pcm.size}, waitLimitMs=$MAX_AUDIO_TRACK_WRITE_WAIT_MS",
                    )
                    return false
                }
                if (offset < pcm.size) {
                    // Yield after both no-progress and partial writes so a
                    // vendor AudioTrack that returns tiny chunks cannot spin.
                    val waitStartedAtNanos = System.nanoTime()
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    } finally {
                        submissionWaitNanos += (System.nanoTime() - waitStartedAtNanos).coerceAtLeast(0L)
                    }
                }
            }
            succeeded = offset == pcm.size
            return succeeded
        } finally {
            recordPlaybackWriteTiming(nativeWriteNanos, submissionWaitNanos, timedOut, succeeded)
        }
    }

    private fun recordPlaybackQueueTiming(
        waitNanos: Long,
        maxObservedQueuedFrames: Long,
        queueLimitFrames: Int,
    ) {
        playbackQueueWindowFrames++
        if (waitNanos >= 1_000_000L) playbackQueueWindowWaitedFrames++
        playbackQueueWindowTotalWaitNanos += waitNanos.coerceAtLeast(0L)
        playbackQueueWindowMaxWaitNanos = maxOf(playbackQueueWindowMaxWaitNanos, waitNanos)
        playbackQueueWindowMaxObservedFrames =
            maxOf(playbackQueueWindowMaxObservedFrames, maxObservedQueuedFrames)
        if (playbackQueueWindowFrames < PLAYBACK_WRITE_DIAGNOSTIC_INTERVAL) return

        fun ms(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)
        AppLog.log(
            TAG,
            "AudioTrack queue pacing: frames=$playbackQueueWindowFrames, " +
                "waitedFrames=$playbackQueueWindowWaitedFrames, " +
                "avgWaitMs=" + ms(playbackQueueWindowTotalWaitNanos / playbackQueueWindowFrames) + ", " +
                "maxWaitMs=" + ms(playbackQueueWindowMaxWaitNanos) + ", " +
                "maxObservedQueuedFrames=$playbackQueueWindowMaxObservedFrames, " +
                "queueLimitFrames=$queueLimitFrames",
        )
        playbackQueueWindowFrames = 0
        playbackQueueWindowWaitedFrames = 0
        playbackQueueWindowTotalWaitNanos = 0L
        playbackQueueWindowMaxWaitNanos = 0L
        playbackQueueWindowMaxObservedFrames = 0L
    }

    private fun recordPlaybackWriteTiming(
        nativeWriteNanos: Long,
        submissionWaitNanos: Long,
        timedOut: Boolean,
        succeeded: Boolean,
    ) {
        playbackWriteWindowFrames++
        if (succeeded) playbackWriteWindowSucceeded++ else playbackWriteWindowFailed++
        playbackWriteWindowTotalNanos += nativeWriteNanos.coerceAtLeast(0L)
        playbackWriteWindowMaxNanos = maxOf(playbackWriteWindowMaxNanos, nativeWriteNanos)
        if (nativeWriteNanos >= 5_000_000L) playbackWriteWindowOver5Ms++
        if (nativeWriteNanos >= 20_000_000L) playbackWriteWindowOver20Ms++
        if (submissionWaitNanos >= 1_000_000L) playbackWriteWindowWaitedFrames++
        playbackWriteWindowTotalWaitNanos += submissionWaitNanos.coerceAtLeast(0L)
        playbackWriteWindowMaxWaitNanos = maxOf(playbackWriteWindowMaxWaitNanos, submissionWaitNanos)
        if (timedOut) playbackWriteWindowDeadlineTimeouts++
        if (playbackWriteWindowFrames < PLAYBACK_WRITE_DIAGNOSTIC_INTERVAL) return

        fun ms(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)
        AppLog.log(
            TAG,
            "AudioTrack write timing: frames=$playbackWriteWindowFrames, " +
                "succeeded=$playbackWriteWindowSucceeded, failed=$playbackWriteWindowFailed, " +
                "nativeAvgMs=" + ms(playbackWriteWindowTotalNanos / playbackWriteWindowFrames) + ", " +
                "nativeMaxMs=" + ms(playbackWriteWindowMaxNanos) + ", " +
                "nativeOver5Ms=$playbackWriteWindowOver5Ms, nativeOver20Ms=$playbackWriteWindowOver20Ms, " +
                "waitedFrames=$playbackWriteWindowWaitedFrames, " +
                "avgWaitMs=" + ms(playbackWriteWindowTotalWaitNanos / playbackWriteWindowFrames) + ", " +
                "maxWaitMs=" + ms(playbackWriteWindowMaxWaitNanos) + ", " +
                "deadlineTimeouts=$playbackWriteWindowDeadlineTimeouts",
        )
        playbackWriteWindowFrames = 0
        playbackWriteWindowSucceeded = 0
        playbackWriteWindowFailed = 0
        playbackWriteWindowOver5Ms = 0
        playbackWriteWindowOver20Ms = 0
        playbackWriteWindowTotalNanos = 0L
        playbackWriteWindowMaxNanos = 0L
        playbackWriteWindowWaitedFrames = 0
        playbackWriteWindowTotalWaitNanos = 0L
        playbackWriteWindowMaxWaitNanos = 0L
        playbackWriteWindowDeadlineTimeouts = 0
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
        queueTracker: PlaybackQueueTracker,
        last: ShortArray?,
        silence: ShortArray,
        plcCount: Int,
        queueLimitFrames: Int,
    ): Int? {
        val lastFrame = last
        val generated = lastFrame != null && plcCount < MAX_PLC_FRAMES
        // Reuse the scratch buffer instead of allocating per lost packet;
        // writePcm consumes it synchronously and the finally-block wipes it.
        val pcm = if (generated && lastFrame != null && lastFrame.size == plcScratch.size) {
            for (i in lastFrame.indices) {
                plcScratch[i] = (lastFrame[i] * PLC_DECAY).toInt().toShort()
            }
            plcScratch
        } else if (generated && lastFrame != null) {
            ShortArray(lastFrame.size) { i -> (lastFrame[i] * PLC_DECAY).toInt().toShort() }
        } else {
            silence
        }
        return try {
            if (writePcm(at, pcm, queueTracker, queueLimitFrames)) plcCount + 1 else null
        } finally {
            if (generated) pcm.fill(0)
        }
    }

    override fun stopPlayback() {
        synchronized(playbackLifecycleLock) {
            playbackRunning.set(false)
            val worker = playbackThread
            val activeTrack = track
            var interrupted = false

            // Interrupt pacing sleeps first so the worker can exit without waiting
            // for its normal polling interval. AudioTrack is released only after
            // the first join unless the worker appears stuck.
            if (worker != null && worker !== Thread.currentThread()) {
                worker.interrupt()
                try {
                    worker.join(500)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }

            if (worker?.isAlive == true && worker !== Thread.currentThread()) {
                AppLog.log(TAG, "playback worker did not stop promptly; releasing AudioTrack to unblock it")
                try { activeTrack?.stop() } catch (_: Exception) {}
                try { activeTrack?.release() } catch (_: Exception) {}
                if (track === activeTrack) track = null
                try {
                    worker.join(250)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            } else {
                try { activeTrack?.stop() } catch (_: Exception) {}
                try { activeTrack?.release() } catch (_: Exception) {}
                if (track === activeTrack) track = null
            }

            // Keep the reference if a vendor call still has the worker alive; a
            // subsequent start must not overlap it or race its final buffer reset.
            // Bump the generation so the zombie exits if its native call ever returns.
            playbackGeneration++
            if (worker == null || !worker.isAlive) {
                playbackThread = null
                playbackZombieSince = 0L
            } else {
                AppLog.log(TAG, "playback worker remains alive after forced AudioTrack release")
                playbackZombieSince = SystemClock.elapsedRealtime()
            }
            restoreAudioModeIfIdle()
            abandonFocus()
            if (interrupted) Thread.currentThread().interrupt()
        }
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
            // Bring up/tear down the SCO link to match the selected route.
            updateBluetoothSco(currentRoute == AudioRoute.BLUETOOTH_SCO)
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

    /**
     * BLUETOOTH_CONNECT is a runtime permission on API 31+. Without it,
     * setCommunicationDevice(BLUETOOTH_SCO) throws SecurityException (caught
     * by callers) and startBluetoothSco() is a no-op. Check first so the
     * BT route degrades gracefully instead of throwing.
     */
    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Establish or tear down the Bluetooth SCO audio link. Must be called
     * when the BT SCO route is selected/deselected: setCommunicationDevice
     * alone does not bring up the SCO connection on all devices.
     */
    private fun updateBluetoothSco(useSco: Boolean) {
        if (!hasBluetoothConnectPermission()) {
            if (useSco) Log.w(TAG, "BT SCO route requested without BLUETOOTH_CONNECT; skipping")
            return
        }
        try {
            if (useSco) {
                if (!audioManager.isBluetoothScoOn) {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                }
            } else {
                if (audioManager.isBluetoothScoOn) {
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "BT SCO change rejected: ${e.javaClass.simpleName}")
        }
    }

    private fun restoreAudioModeIfIdle() {
        if (captureRunning.get() || playbackRunning.get()) return
        val mode = previousAudioMode
        val speaker = previousSpeakerphoneOn
        val previousDevice = previousCommunicationDevice
        if (mode == null && speaker == null && previousDevice == null) return
        // Re-read the live state before restoring: if another app (e.g. an
        // incoming call) changed the audio mode while we were active, our
        // snapshot is stale — do not clobber their state, just drop ours.
        val currentMode: Int? = try { audioManager.mode } catch (_: Exception) { null }
        if (currentMode != null && currentMode != AudioManager.MODE_IN_COMMUNICATION) {
            Log.w(TAG, "audio mode changed externally during session; skipping restore (current=$currentMode)")
            previousAudioMode = null
            previousSpeakerphoneOn = null
            previousCommunicationDevice = null
            return
        }
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
            // Session over: tear down the SCO link we may have brought up.
            updateBluetoothSco(false)
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
        synchronized(focusLock) {
            if (focusHolders > 0) {
                focusHolders++
                return true
            }
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) {
                focusRequest = req
                focusHolders = 1
            } else {
                Log.w(TAG, "audio focus request denied")
            }
            return granted
        }
    }

    private fun abandonFocus() {
        synchronized(focusLock) {
            if (focusHolders == 0) return
            focusHolders--
            if (focusHolders == 0) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        }
    }

    fun release() {
        stopCapture()
        stopPlayback()
        try {
            context.unregisterReceiver(routeReceiver)
        } catch (_: IllegalArgumentException) {}
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            Log.w(TAG, "audio device callback unregister rejected: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "HamSedaAudio"
        private const val MAX_PLC_FRAMES = 5
        private const val PLC_DECAY = 0.85f
        private const val JITTER_DIAGNOSTIC_INTERVAL = 25L
        private const val ENCODE_DIAGNOSTIC_INTERVAL = 50
        private const val PLAYBACK_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val PLAYBACK_WRITE_DIAGNOSTIC_INTERVAL = 50
        /**
         * Grace period before a new playback may start past a zombie worker
         * thread that stopPlayback already gave up on. Bounded so a stuck
         * native AudioTrack call can never block voice forever.
         */
        private const val ZOMBIE_GRACE_MS = 10_000L
        /** Three frames only when LOW_LATENCY is actually granted by Android. */
        private const val LOW_LATENCY_PLAYBACK_QUEUE_FRAMES = 3
        /** 100 ms PCM headroom when Android declines AudioTrack LOW_LATENCY mode. */
        private const val MAX_TRACKED_PLAYBACK_QUEUE_FRAMES = 5
        /** ~200 ms of HAL capture headroom (see startCapture). */
        private const val CAPTURE_BUFFER_FRAMES = 10
        /** A stuck output head is a playback failure, not an infinite wait. */
        private const val MAX_PLAYBACK_QUEUE_WAIT_MS = 500L
        /** Bound time spent retrying a full/no-progress non-blocking AudioTrack write. */
        private const val MAX_AUDIO_TRACK_WRITE_WAIT_MS = 250L
        /** HS-09: bounded retries for transient AudioRecord.read errors. */
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10
        /** Separate, more generous budget for zero-progress reads (not hardware errors). */
        private const val MAX_CONSECUTIVE_ZERO_READS = 50
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
