package com.hamseda.walkie.audio

/**
 * Abstract voice pipeline used by [com.hamseda.walkie.session.SessionManager].
 *
 * The production implementation is [AudioEngine]; unit tests substitute a
 * fake, so session logic (handshake, floor control, framing) is verified
 * without Android audio hardware.
 */
interface AudioPipeline {
    interface PipelineListener {
        /** Transient audio focus loss (e.g. phone call) — stop TX now. */
        fun onAudioFocusLost()
        fun onCaptureError(message: String)
    }

    var pipelineListener: PipelineListener?

    /**
     * Starts microphone capture; [onFrame] receives each encoded 20 ms
     * frame plus a 0..1 voice level on the capture thread.
     */
    fun startCapture(codec: AudioCodec, onFrame: (encoded: ByteArray, level: Float) -> Unit): Boolean
    fun stopCapture()
    fun startPlayback(codec: AudioCodec, buffer: JitterBuffer): Boolean
    fun stopPlayback()
    fun setSpeakerphone(on: Boolean)
    val isCapturing: Boolean
}
