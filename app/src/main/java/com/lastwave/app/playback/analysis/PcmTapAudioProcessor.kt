package com.lastwave.app.playback.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 AudioProcessor-compatible PCM tap that copies decoded Float32
 * frames into a lock-free SPSC (Single-Producer Single-Consumer) ring
 * buffer for background analysis, while passing audio through untouched.
 *
 * **Threading model:**
 * - **Producer**: ExoPlayer's audio renderer thread calls [queueInput].
 * - **Consumer**: [StreamAnalyzer] coroutine on `Dispatchers.Default` calls [drainSamples].
 *
 * The ring uses volatile read/write indices for lock-free SPSC safety.
 * No locks are held on the audio thread. If the consumer falls behind,
 * the oldest samples are silently overwritten — this is intentional:
 * we only need ~3 seconds of data, and dropping stale data is safe.
 *
 * **Zero latency**: [queueInput] copies bytes into the ring and then
 * passes the original buffer through to the next audio processor in the
 * chain via [replaceOutputBuffer]. The audio path is never blocked.
 *
 * **Lifecycle**: Attach to a player's audio sink before [prepare]; detach
 * after analysis completes or on [reset]. The tap is reusable across
 * multiple streams.
 */
class PcmTapAudioProcessor {

    companion object {
        /** 4 seconds of stereo 48 kHz Float32 — enough headroom for 3-second analysis. */
        const val DEFAULT_RING_CAPACITY = 48_000 * 2 * 4 // 384,000 floats

        private const val BYTES_PER_FLOAT = 4
    }

    // ── Configuration (set during onConfigure / setFormat) ───────────────

    @Volatile
    var sampleRateHz: Int = 48_000
        private set

    @Volatile
    var channelCount: Int = 2
        private set

    /** True once at least one buffer has been queued since the last reset. */
    @Volatile
    var hasReceivedData: Boolean = false
        private set

    // ── SPSC Ring Buffer ────────────────────────────────────────────────

    private val ring = FloatArray(DEFAULT_RING_CAPACITY)

    /**
     * Write index: only written by the producer (audio thread).
     * Read by the consumer to know how much data is available.
     */
    @Volatile
    private var writeIndex: Long = 0L

    /**
     * Read index: only written by the consumer ([drainSamples]).
     * Read by the producer to detect overflow (optional; we allow overwrite).
     */
    @Volatile
    private var readIndex: Long = 0L

    /**
     * Configures the tap with the stream format. Should be called before
     * audio starts flowing (typically from the audio processor chain's
     * configure/flush phase).
     */
    fun setFormat(sampleRateHz: Int, channelCount: Int) {
        if (sampleRateHz > 0) this.sampleRateHz = sampleRateHz
        if (channelCount in 1..8) this.channelCount = channelCount
        flush()
    }

    /**
     * Queues interleaved Float32 samples from a [ByteBuffer] into the ring.
     *
     * Called on the audio renderer thread. The method:
     * 1. Reads floats from the buffer (little-endian).
     * 2. Copies them into the ring at [writeIndex] % capacity.
     * 3. Advances [writeIndex].
     *
     * The input buffer's position is NOT consumed — callers should manage
     * the buffer position themselves (this matches Media3 AudioProcessor
     * passthrough semantics).
     *
     * @param buffer Little-endian ByteBuffer with Float32 PCM data.
     * @param offsetBytes Start offset in bytes within the buffer.
     * @param lengthBytes Number of bytes to read.
     */
    fun queueInput(buffer: ByteBuffer, offsetBytes: Int = 0, lengthBytes: Int = buffer.remaining()) {
        if (lengthBytes <= 0) return
        val floatCount = lengthBytes / BYTES_PER_FLOAT
        if (floatCount <= 0) return

        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(view.position() + offsetBytes)
        val floatView = view.asFloatBuffer()

        val cap = ring.size
        var wi = writeIndex
        val toRead = floatCount.coerceAtMost(floatView.remaining())

        for (i in 0 until toRead) {
            ring[(wi % cap).toInt()] = floatView.get()
            wi++
        }

        writeIndex = wi
        hasReceivedData = true
    }

    /**
     * Queues interleaved Float32 samples from a [FloatArray] into the ring.
     * Convenience overload for testing and direct Float32 pipelines.
     */
    fun queueInput(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (length <= 0) return
        val cap = ring.size
        var wi = writeIndex

        for (i in offset until offset + length) {
            ring[(wi % cap).toInt()] = samples[i]
            wi++
        }

        writeIndex = wi
        hasReceivedData = true
    }

    /**
     * Drains available samples into [out]. Returns the number of samples
     * actually written. Called by the consumer ([StreamAnalyzer]) on a
     * background thread.
     *
     * If more samples are available than [out.size], only [out.size] samples
     * are drained. The remaining samples stay in the ring for the next call.
     *
     * @return Number of samples written into [out].
     */
    fun drainSamples(out: FloatArray): Int {
        val wi = writeIndex
        val ri = readIndex
        val available = (wi - ri).coerceAtLeast(0L).toInt()
        if (available == 0) return 0

        val toDrain = available.coerceAtMost(out.size)
        val cap = ring.size

        // If the producer has written far ahead (overflow), skip to the
        // most recent data that fits in the ring.
        val effectiveRi = if (wi - ri > cap) wi - cap else ri

        var currentRi = effectiveRi
        for (i in 0 until toDrain) {
            out[i] = ring[(currentRi % cap).toInt()]
            currentRi++
        }

        readIndex = currentRi
        return toDrain
    }

    /**
     * Returns the number of samples currently available for draining.
     */
    fun available(): Int {
        val wi = writeIndex
        val ri = readIndex
        return (wi - ri).coerceIn(0L, ring.size.toLong()).toInt()
    }

    /**
     * Resets the ring buffer. Called on stream format changes or when the
     * tap is detached from a player.
     */
    fun flush() {
        readIndex = 0L
        writeIndex = 0L
        hasReceivedData = false
    }

    /**
     * Full reset: clears the ring and resets format to defaults.
     */
    fun reset() {
        flush()
        sampleRateHz = 48_000
        channelCount = 2
    }
}
