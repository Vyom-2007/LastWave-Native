package com.lastwave.app.playback.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM unit tests for [PcmTapAudioProcessor].
 *
 * Verifies the lock-free SPSC ring buffer: passthrough integrity, correct
 * drain counts, overflow safety, and flush/reset behavior.
 */
class PcmTapAudioProcessorTest {

    private fun createTap() = PcmTapAudioProcessor()

    /** Helper: Creates a little-endian Float32 ByteBuffer from a FloatArray. */
    private fun floatArrayToByteBuffer(samples: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        val floatView = buffer.asFloatBuffer()
        floatView.put(samples)
        buffer.position(0)
        buffer.limit(samples.size * 4)
        return buffer
    }

    @Test
    fun passthrough_audio_unchanged() {
        // Verify that queueing samples and draining them returns identical data
        val tap = createTap()
        val input = FloatArray(256) { it.toFloat() / 256f }

        tap.queueInput(input)

        val output = FloatArray(256)
        val drained = tap.drainSamples(output)

        assertThat(drained).isEqualTo(256)
        for (i in input.indices) {
            assertThat(output[i]).isEqualTo(input[i])
        }
    }

    @Test
    fun drain_returns_correct_sample_count() {
        val tap = createTap()

        // Queue 100 samples
        val input = FloatArray(100) { 0.5f }
        tap.queueInput(input)

        // Drain into a buffer larger than available
        val output = FloatArray(500)
        val drained = tap.drainSamples(output)

        assertThat(drained).isEqualTo(100)

        // Drain again — should return 0 (no new data)
        val drained2 = tap.drainSamples(output)
        assertThat(drained2).isEqualTo(0)
    }

    @Test
    fun overflow_does_not_crash() {
        val tap = createTap()
        val capacity = PcmTapAudioProcessor.DEFAULT_RING_CAPACITY

        // Push 2x the ring capacity — should not throw
        val bigInput = FloatArray(capacity * 2) { (it % 1000).toFloat() / 1000f }
        tap.queueInput(bigInput)

        // Should still be able to drain data (the most recent samples)
        val output = FloatArray(capacity)
        val drained = tap.drainSamples(output)

        // Should drain up to capacity (the ring wraps)
        assertThat(drained).isAtMost(capacity)
        assertThat(drained).isGreaterThan(0)
    }

    @Test
    fun format_change_resets_ring() {
        val tap = createTap()

        // Queue some data
        val input = FloatArray(200) { 1.0f }
        tap.queueInput(input)
        assertThat(tap.available()).isEqualTo(200)
        assertThat(tap.hasReceivedData).isTrue()

        // Format change triggers flush
        tap.setFormat(44_100, 2)

        assertThat(tap.available()).isEqualTo(0)
        assertThat(tap.hasReceivedData).isFalse()
        assertThat(tap.sampleRateHz).isEqualTo(44_100)

        // Drain should return 0
        val output = FloatArray(200)
        val drained = tap.drainSamples(output)
        assertThat(drained).isEqualTo(0)
    }

    @Test
    fun byteBuffer_input_works() {
        val tap = createTap()
        val input = FloatArray(128) { it.toFloat() / 128f }
        val buffer = floatArrayToByteBuffer(input)

        tap.queueInput(buffer)

        val output = FloatArray(128)
        val drained = tap.drainSamples(output)

        assertThat(drained).isEqualTo(128)
        for (i in input.indices) {
            assertThat(output[i]).isWithin(1e-6f).of(input[i])
        }
    }
}
