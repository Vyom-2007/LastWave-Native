package com.lastwave.app.playback.analysis

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM unit tests for [StreamAnalyzer].
 *
 * Verifies end-to-end analysis pipeline: high/low energy classification,
 * timeout behavior, and clean cancellation.
 */
class StreamAnalyzerTest {

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2

        /** Short window for faster tests. */
        private const val TEST_WINDOW_SECONDS = 0.5f
        private const val TEST_TIMEOUT_MS = 2_000L

        /** Generates a mono sine wave and interleaves it to stereo. */
        private fun stereoSine(
            frequencyHz: Double,
            amplitude: Float = 0.8f,
            durationSeconds: Double = 1.0,
        ): FloatArray {
            val monoLength = (SAMPLE_RATE * durationSeconds).toInt()
            val stereo = FloatArray(monoLength * CHANNELS)
            for (i in 0 until monoLength) {
                val sample = (amplitude * sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE)).toFloat()
                stereo[i * 2] = sample
                stereo[i * 2 + 1] = sample
            }
            return stereo
        }
    }

    @Test
    fun analyze_high_energy_signal() = runBlocking {
        val tap = PcmTapAudioProcessor()
        tap.setFormat(SAMPLE_RATE, CHANNELS)

        // Pre-fill with high-frequency signal (5 kHz = bright/energetic)
        val signal = stereoSine(5000.0, durationSeconds = 1.0)
        tap.queueInput(signal)

        val analyzer = StreamAnalyzer(
            tap = tap,
            analysisWindowSeconds = TEST_WINDOW_SECONDS,
            pollIntervalMs = 10L,
            timeoutMs = TEST_TIMEOUT_MS,
        )

        val metadata = analyzer.analyze("test_high_energy")

        assertThat(metadata).isNotNull()
        assertThat(metadata!!.energy).isNotNull()
        assertThat(metadata.energy!!).isGreaterThan(0.5f)
        assertThat(metadata.loudnessDb).isNotNull()
        assertThat(metadata.isSeekable).isFalse()
        assertThat(metadata.trackId).isEqualTo("test_high_energy")
    }

    @Test
    fun analyze_low_energy_signal() = runBlocking {
        val tap = PcmTapAudioProcessor()
        tap.setFormat(SAMPLE_RATE, CHANNELS)

        // Pre-fill with low-frequency signal (100 Hz = warm/bass-heavy)
        val signal = stereoSine(100.0, durationSeconds = 1.0)
        tap.queueInput(signal)

        val analyzer = StreamAnalyzer(
            tap = tap,
            analysisWindowSeconds = TEST_WINDOW_SECONDS,
            pollIntervalMs = 10L,
            timeoutMs = TEST_TIMEOUT_MS,
        )

        val metadata = analyzer.analyze("test_low_energy")

        assertThat(metadata).isNotNull()
        assertThat(metadata!!.energy).isNotNull()
        assertThat(metadata.energy!!).isLessThan(0.3f)
    }

    @Test
    fun analyze_returns_null_on_timeout() = runBlocking {
        val tap = PcmTapAudioProcessor()
        tap.setFormat(SAMPLE_RATE, CHANNELS)

        // Don't push any data — the analyzer should timeout
        val analyzer = StreamAnalyzer(
            tap = tap,
            analysisWindowSeconds = TEST_WINDOW_SECONDS,
            pollIntervalMs = 10L,
            timeoutMs = 200L, // Very short timeout for test speed
        )

        val metadata = analyzer.analyze("test_timeout")

        assertThat(metadata).isNull()
    }

    @Test
    fun analyze_cancels_cleanly() = runBlocking {
        val tap = PcmTapAudioProcessor()
        tap.setFormat(SAMPLE_RATE, CHANNELS)

        val analyzer = StreamAnalyzer(
            tap = tap,
            analysisWindowSeconds = TEST_WINDOW_SECONDS,
            pollIntervalMs = 10L,
            timeoutMs = 5_000L,
        )

        // Launch the analyzer and cancel it before it can complete
        val job = launch {
            analyzer.analyze("test_cancel")
        }

        delay(50)
        job.cancel()
        job.join()

        // If we reach here without an uncaught exception, cancellation is clean
        assertThat(job.isCancelled).isTrue()
    }
}
