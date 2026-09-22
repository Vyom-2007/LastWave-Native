package com.lastwave.app.playback.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM unit tests for Phase 2 streaming analysis DSP functions in [DspMath].
 *
 * Uses synthetic signals (sine waves, silence, white noise) to verify that
 * RMS, Zero-Crossing Rate, spectral energy ratio, and combined energy
 * estimation produce correct and stable results without any audio hardware.
 */
class StreamAnalyzerDspTest {

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val TOLERANCE = 0.02f

        /** Generates a mono sine wave at [frequencyHz] for [durationSeconds]. */
        private fun sineWave(
            frequencyHz: Double,
            amplitude: Float = 1.0f,
            sampleRate: Int = SAMPLE_RATE,
            durationSeconds: Double = 0.5,
        ): FloatArray {
            val length = (sampleRate * durationSeconds).toInt()
            return FloatArray(length) { i ->
                (amplitude * sin(2.0 * PI * frequencyHz * i / sampleRate)).toFloat()
            }
        }

        /** Generates interleaved stereo from a mono signal (duplicate channels). */
        private fun stereoInterleave(mono: FloatArray): FloatArray {
            val stereo = FloatArray(mono.size * 2)
            for (i in mono.indices) {
                stereo[i * 2] = mono[i]
                stereo[i * 2 + 1] = mono[i]
            }
            return stereo
        }
    }

    // ── RMS ──────────────────────────────────────────────────────────────

    @Test
    fun rms_of_silence_is_zero() {
        val silence = FloatArray(1024) { 0f }
        assertThat(DspMath.rms(silence)).isEqualTo(0f)
    }

    @Test
    fun rms_of_full_scale_sine() {
        // RMS of a full-scale sine wave = amplitude / sqrt(2) ≈ 0.7071
        val sine = sineWave(1000.0, amplitude = 1.0f, durationSeconds = 1.0)
        val rms = DspMath.rms(sine)
        assertThat(rms).isWithin(TOLERANCE).of(0.7071f)
    }

    // ── Zero-Crossing Rate ──────────────────────────────────────────────

    @Test
    fun zcr_of_high_freq_sine() {
        // 10 kHz sine at 48 kHz: ~2 crossings per cycle, ~10000 cycles/sec
        // ZCR ≈ 2 * 10000 / 48000 ≈ 0.4167
        val sine = sineWave(10_000.0, durationSeconds = 0.5)
        val zcr = DspMath.zeroCrossingRate(sine)
        assertThat(zcr).isGreaterThan(0.3f)
    }

    @Test
    fun zcr_of_low_freq_sine() {
        // 100 Hz sine at 48 kHz: ~2 crossings per cycle, ~100 cycles/sec
        // ZCR ≈ 2 * 100 / 48000 ≈ 0.0042
        val sine = sineWave(100.0, durationSeconds = 0.5)
        val zcr = DspMath.zeroCrossingRate(sine)
        assertThat(zcr).isLessThan(0.02f)
    }

    @Test
    fun zcr_of_silence_is_zero() {
        val silence = FloatArray(1024) { 0f }
        val zcr = DspMath.zeroCrossingRate(silence)
        assertThat(zcr).isEqualTo(0f)
    }

    // ── Spectral Energy Ratio ───────────────────────────────────────────

    @Test
    fun spectral_ratio_bright_vs_warm() {
        // An 8 kHz sine (well above 300 Hz split) should have a much higher
        // spectral ratio than an 80 Hz sine (well below 300 Hz split).
        val bright = stereoInterleave(sineWave(8000.0, durationSeconds = 0.5))
        val warm = stereoInterleave(sineWave(80.0, durationSeconds = 0.5))

        val ratioBright = DspMath.spectralEnergyRatio(bright, SAMPLE_RATE, CHANNELS)
        val ratioWarm = DspMath.spectralEnergyRatio(warm, SAMPLE_RATE, CHANNELS)

        // The 8 kHz signal should pass almost entirely through the 300 Hz high-pass
        assertThat(ratioBright).isGreaterThan(0.8f)
        // The 80 Hz signal should be heavily attenuated by the 300 Hz high-pass
        assertThat(ratioWarm).isLessThan(0.3f)
        // The bright signal must have a clearly higher ratio
        assertThat(ratioBright).isGreaterThan(ratioWarm)
    }

    // ── Combined Energy Estimate ────────────────────────────────────────

    @Test
    fun estimateEnergy_high_values_yield_high() {
        // High ZCR + high spectral ratio → high energy
        val energy = DspMath.estimateEnergy(zcr = 0.5f, spectralRatio = 0.8f)
        assertThat(energy).isAtLeast(0.6f)
    }

    @Test
    fun estimateEnergy_low_values_yield_low() {
        // Low ZCR + low spectral ratio → low energy
        val energy = DspMath.estimateEnergy(zcr = 0.02f, spectralRatio = 0.1f)
        assertThat(energy).isAtMost(0.2f)
    }
}
