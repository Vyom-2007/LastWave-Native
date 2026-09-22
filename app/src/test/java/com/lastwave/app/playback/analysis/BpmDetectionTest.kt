package com.lastwave.app.playback.analysis

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Unit tests for BPM detection via onset auto-correlation.
 *
 * Tests use synthetic "click track" signals: periodic impulses at known BPMs.
 * A click track has sharp energy spikes at regular intervals, making it the
 * ideal test signal for onset-based BPM detection.
 */
class BpmDetectionTest {

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val DURATION_SECONDS = 3.0f

        /** BPM tolerance for assertions (±5 BPM accounts for quantization). */
        private const val BPM_TOLERANCE = 5.0f

        /**
         * Generates a stereo click track: short bursts of noise at [bpm] intervals.
         * Each click is a 5ms burst of a 1kHz sine wave at 0.8 amplitude.
         */
        private fun generateClickTrack(
            bpm: Float,
            sampleRate: Int = SAMPLE_RATE,
            channels: Int = CHANNELS,
            durationSeconds: Float = DURATION_SECONDS,
        ): FloatArray {
            val totalMonoSamples = (sampleRate * durationSeconds).toInt()
            val totalSamples = totalMonoSamples * channels
            val samplesPerBeat = (sampleRate * 60.0f / bpm).toInt()
            val clickLengthSamples = (sampleRate * 0.005f).toInt() // 5ms click

            val samples = FloatArray(totalSamples)
            for (monoIdx in 0 until totalMonoSamples) {
                val posInBeat = monoIdx % samplesPerBeat
                val value = if (posInBeat < clickLengthSamples) {
                    // Short burst of 1kHz sine
                    (0.8f * sin(2.0 * PI * 1000.0 * monoIdx / sampleRate)).toFloat()
                } else {
                    0f
                }
                for (ch in 0 until channels) {
                    samples[monoIdx * channels + ch] = value
                }
            }
            return samples
        }

        /**
         * Generates a "silent" signal with very low random noise.
         * Should produce null BPM (no detectable rhythm).
         */
        private fun generateSilence(
            sampleRate: Int = SAMPLE_RATE,
            channels: Int = CHANNELS,
            durationSeconds: Float = DURATION_SECONDS,
        ): FloatArray {
            val totalSamples = (sampleRate * durationSeconds * channels).toInt()
            return FloatArray(totalSamples) { (Math.random() * 0.001f).toFloat() }
        }

        /**
         * Generates a continuous sine wave (no rhythmic content).
         * Should produce null BPM (no clear beat periodicity).
         */
        private fun generateContinuousTone(
            frequencyHz: Double = 440.0,
            sampleRate: Int = SAMPLE_RATE,
            channels: Int = CHANNELS,
            durationSeconds: Float = DURATION_SECONDS,
        ): FloatArray {
            val totalMonoSamples = (sampleRate * durationSeconds).toInt()
            val totalSamples = totalMonoSamples * channels
            val samples = FloatArray(totalSamples)
            for (monoIdx in 0 until totalMonoSamples) {
                val value = (0.5f * sin(2.0 * PI * frequencyHz * monoIdx / sampleRate)).toFloat()
                for (ch in 0 until channels) {
                    samples[monoIdx * channels + ch] = value
                }
            }
            return samples
        }
    }

    @Test
    fun `120 BPM click track detected correctly`() {
        val samples = generateClickTrack(120f)
        val bpm = DspMath.estimateBpm(samples, SAMPLE_RATE, CHANNELS)
        assertNotNull("BPM should be detected for 120 BPM click track", bpm)
        assertTrue(
            "Expected ~120 BPM but got $bpm",
            abs(bpm!! - 120f) < BPM_TOLERANCE
        )
    }

    @Test
    fun `90 BPM click track detected correctly`() {
        val samples = generateClickTrack(90f)
        val bpm = DspMath.estimateBpm(samples, SAMPLE_RATE, CHANNELS)
        assertNotNull("BPM should be detected for 90 BPM click track", bpm)
        assertTrue(
            "Expected ~90 BPM but got $bpm",
            abs(bpm!! - 90f) < BPM_TOLERANCE
        )
    }

    @Test
    fun `140 BPM click track detected correctly`() {
        val samples = generateClickTrack(140f)
        val bpm = DspMath.estimateBpm(samples, SAMPLE_RATE, CHANNELS)
        assertNotNull("BPM should be detected for 140 BPM click track", bpm)
        assertTrue(
            "Expected ~140 BPM but got $bpm",
            abs(bpm!! - 140f) < BPM_TOLERANCE
        )
    }

    @Test
    fun `silence returns null BPM`() {
        val samples = generateSilence()
        val bpm = DspMath.estimateBpm(samples, SAMPLE_RATE, CHANNELS)
        assertNull("Silence should return null BPM", bpm)
    }

    @Test
    fun `continuous tone returns null BPM`() {
        val samples = generateContinuousTone()
        val bpm = DspMath.estimateBpm(samples, SAMPLE_RATE, CHANNELS)
        assertNull("Continuous tone should return null BPM (no beat periodicity)", bpm)
    }

    @Test
    fun `empty samples returns null`() {
        assertNull(DspMath.estimateBpm(FloatArray(0), SAMPLE_RATE, CHANNELS))
    }

    @Test
    fun `mono click track works`() {
        val samples = generateClickTrack(120f, channels = 1)
        val bpm = DspMath.estimateBpm(samples, SAMPLE_RATE, 1)
        assertNotNull("BPM should be detected for mono 120 BPM click track", bpm)
        assertTrue(
            "Expected ~120 BPM but got $bpm",
            abs(bpm!! - 120f) < BPM_TOLERANCE
        )
    }

    @Test
    fun `onset envelope has correct frame count`() {
        val monoLength = SAMPLE_RATE * 3 // 3 seconds mono
        val mono = FloatArray(monoLength)
        val envelope = DspMath.onsetEnvelope(mono, DspMath.BPM_HOP_SIZE)
        val expectedFrames = monoLength / DspMath.BPM_HOP_SIZE
        // Onset envelope has numFrames - 1 entries (first difference)
        assertEquals(expectedFrames - 1, envelope.size)
    }

    @Test
    fun `auto-correlate output length matches lag range`() {
        val envelope = FloatArray(100) { 1f }
        val result = DspMath.autoCorrelate(envelope, 10, 50)
        assertEquals(41, result.size) // 50 - 10 + 1
    }
}
