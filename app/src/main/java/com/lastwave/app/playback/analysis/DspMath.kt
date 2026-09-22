package com.lastwave.app.playback.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM-testable DSP and meter math for the playback analysis package.
 *
 * The production DSP runs in C++ over JNI and cannot execute in local unit
 * tests, so this file provides small Kotlin equivalents built from standard
 * textbook formulas (RBJ biquad cookbook, 20*log10 level conversion,
 * hard-ceiling limiting). They exist so [DspResponseTest] can verify
 * convergence, bypass exactness, limiter ceilings and meter arithmetic on
 * the JVM with no NDK or device. Nothing here is wired into the audible
 * path; it performs no audio I/O.
 */
object DspMath {

    const val FULL_SCALE_LINEAR = 1.0f
    const val CLIP_THRESHOLD_LINEAR = 1.0f
    const val MIN_DBFS = -120.0f

    /** Q used by the 15-band equalizer stages. */
    const val EQ_Q = 1.4142135623730951

    /** Center frequencies of the 15 equalizer bands in Hz. */
    val EQ_FREQUENCIES_HZ: List<Double> = listOf(
        25.0, 40.0, 63.0, 100.0, 160.0,
        250.0, 400.0, 630.0, 1000.0, 1600.0,
        2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
    )

    /** Converts a linear amplitude to dBFS, floored at [MIN_DBFS]. */
    fun amplitudeToDbfs(linear: Float): Float {
        if (!linear.isFinite() || linear <= 0f) return MIN_DBFS
        return (20.0 * log10(linear.toDouble())).toFloat().coerceAtLeast(MIN_DBFS)
    }

    /** Summary of one measurement window over interleaved Float32 samples. */
    data class WindowStats(
        val sampleCount: Long,
        val peakLinear: Float,
        val rmsLinear: Float,
        val dcOffset: Float,
        val clippedSamples: Long,
    )

    /**
     * Batch analysis over [samples]. Read-only: the input array is never
     * written to. Non-finite samples are counted as clipped and excluded
     * from the sums so one bad value cannot poison the window.
     */
    fun analyzeWindow(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): WindowStats {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "Window [$offset, ${offset + length}) is outside ${samples.size} samples"
        }
        if (length == 0) return WindowStats(0L, 0f, 0f, 0f, 0L)
        var peak = 0f
        var sum = 0.0
        var sumSquares = 0.0
        var finiteCount = 0L
        var clips = 0L
        for (index in offset until offset + length) {
            val value = samples[index]
            if (!value.isFinite()) {
                clips++
                continue
            }
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
            sum += value
            sumSquares += value.toDouble() * value.toDouble()
            finiteCount++
            if (magnitude >= CLIP_THRESHOLD_LINEAR) clips++
        }
        if (finiteCount == 0L) return WindowStats(length.toLong(), 0f, 0f, 0f, clips)
        val rms = sqrt(sumSquares / finiteCount.toDouble()).toFloat()
        val dc = (sum / finiteCount.toDouble()).toFloat()
        return WindowStats(
            sampleCount = length.toLong(),
            peakLinear = peak,
            rmsLinear = rms,
            dcOffset = dc,
            clippedSamples = clips,
        )
    }

    /** Decibel gain to linear multiplier. Non-finite input maps to unity. */
    fun dbToGain(gainDb: Double): Double {
        if (!gainDb.isFinite()) return 1.0
        return 10.0.pow(gainDb / 20.0)
    }

    /** Linear multiplier to decibels. Non-positive input maps to -infinity. */
    fun gainToDb(gain: Double): Double {
        if (!gain.isFinite() || gain <= 0.0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(gain)
    }

    /**
     * Applies a ReplayGain-style adjustment and returns a new array. The
     * input is never mutated. Non-finite input samples become silence so
     * they cannot propagate downstream.
     */
    fun applyGainDb(samples: FloatArray, gainDb: Double): FloatArray {
        val gain = dbToGain(gainDb).toFloat()
        return FloatArray(samples.size) { index ->
            val value = samples[index]
            if (!value.isFinite()) 0f else value * gain
        }
    }

    /** Second-order IIR section with per-channel state for mono/stereo use. */
    class JvmBiquad private constructor(
        private var b0: Double,
        private var b1: Double,
        private var b2: Double,
        private var a1: Double,
        private var a2: Double,
    ) {
        private val z1 = DoubleArray(2)
        private val z2 = DoubleArray(2)

        fun reset() {
            z1.fill(0.0)
            z2.fill(0.0)
        }

        /**
         * Filters one sample on [channel] (0 or 1). Guards against
         * denormals and non-finite state so a hostile input cannot wedge
         * the section; on non-finite output the state resets and 0 is
         * returned.
         */
        fun tick(input: Float, channel: Int = 0): Float {
            require(channel == 0 || channel == 1) { "channel must be 0 or 1" }
            val output = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * output + z2[channel]
            z2[channel] = b2 * input - a2 * output
            if (abs(z1[channel]) < 1.0e-20) z1[channel] = 0.0
            if (abs(z2[channel]) < 1.0e-20) z2[channel] = 0.0
            if (!output.isFinite() || !z1[channel].isFinite() || !z2[channel].isFinite()) {
                z1[channel] = 0.0
                z2[channel] = 0.0
                return 0f
            }
            return output.toFloat()
        }

        /** Processes a mono buffer into a new array; input is untouched. */
        fun processMono(input: FloatArray): FloatArray {
            val output = FloatArray(input.size)
            for (index in input.indices) {
                output[index] = tick(input[index], 0)
            }
            return output
        }

        /** Steady-state magnitude response at [frequencyHz]. */
        fun magnitude(sampleRateHz: Double, frequencyHz: Double): Double {
            val safeRate = sampleRateHz.coerceAtLeast(1.0)
            val clamped = frequencyHz.coerceIn(1.0, safeRate * 0.45)
            val omega = 2.0 * PI * clamped / safeRate
            val cos1 = cos(omega)
            val sin1 = sin(omega)
            val cos2 = cos(2.0 * omega)
            val sin2 = sin(2.0 * omega)
            val numReal = b0 + b1 * cos1 + b2 * cos2
            val numImag = -b1 * sin1 - b2 * sin2
            val denReal = 1.0 + a1 * cos1 + a2 * cos2
            val denImag = -a1 * sin1 - a2 * sin2
            val numPower = numReal * numReal + numImag * numImag
            val denPower = denReal * denReal + denImag * denImag
            return sqrt(numPower / denPower.coerceAtLeast(1.0e-24))
        }

        companion object {
            private fun clampRate(sampleRateHz: Double): Double =
                sampleRateHz.coerceAtLeast(8000.0)

            private fun clampFrequency(sampleRateHz: Double, frequencyHz: Double): Double =
                frequencyHz.coerceIn(1.0, sampleRateHz * 0.45)

            fun highPass(sampleRateHz: Double, frequencyHz: Double, q: Double): JvmBiquad {
                val rate = clampRate(sampleRateHz)
                val frequency = clampFrequency(rate, frequencyHz)
                val safeQ = q.coerceAtLeast(0.01)
                val omega = 2.0 * PI * frequency / rate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * safeQ)
                val a0 = 1.0 + alpha
                return JvmBiquad(
                    b0 = ((1.0 + cosine) * 0.5) / a0,
                    b1 = -(1.0 + cosine) / a0,
                    b2 = ((1.0 + cosine) * 0.5) / a0,
                    a1 = (-2.0 * cosine) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun peaking(
                sampleRateHz: Double,
                frequencyHz: Double,
                q: Double,
                gainDb: Double,
            ): JvmBiquad {
                val rate = clampRate(sampleRateHz)
                val frequency = clampFrequency(rate, frequencyHz)
                val safeQ = q.coerceAtLeast(0.01)
                val safeGain = if (gainDb.isFinite()) gainDb else 0.0
                val omega = 2.0 * PI * frequency / rate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * safeQ)
                val amplitude = 10.0.pow(safeGain / 40.0)
                val a0 = 1.0 + alpha / amplitude
                val b0 = (1.0 + alpha * amplitude) / a0
                val b1 = (-2.0 * cosine) / a0
                val b2 = (1.0 - alpha * amplitude) / a0
                return JvmBiquad(
                    b0 = b0,
                    b1 = b1,
                    b2 = b2,
                    a1 = b1,
                    a2 = (1.0 - alpha / amplitude) / a0,
                )
            }

            fun highShelf(
                sampleRateHz: Double,
                frequencyHz: Double,
                slope: Double,
                gainDb: Double,
            ): JvmBiquad {
                val rate = clampRate(sampleRateHz)
                val frequency = clampFrequency(rate, frequencyHz)
                val safeGain = if (gainDb.isFinite()) gainDb else 0.0
                val safeSlope = slope.coerceIn(0.1, 1.0)
                val omega = 2.0 * PI * frequency / rate
                val cosine = cos(omega)
                val sine = sin(omega)
                val amplitude = 10.0.pow(safeGain / 40.0)
                val alpha = (sine * 0.5) * sqrt(
                    (amplitude + 1.0 / amplitude) * (1.0 / safeSlope - 1.0) + 2.0,
                )
                val beta = 2.0 * sqrt(amplitude) * alpha
                val a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta
                return JvmBiquad(
                    b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta) / a0,
                    b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine) / a0,
                    b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta) / a0,
                    a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine) / a0,
                    a2 = ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta) / a0,
                )
            }

            /** Unity section used to model a bypassed stage. */
            fun passthrough(): JvmBiquad = JvmBiquad(1.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** One named filter stage in the test chain. */
    data class NamedStage(val name: String, val filter: JvmBiquad)

    /**
     * The fixed clarity-chain stage list evaluated at [sampleRateHz].
     * Parameters match the production chain layout so the JVM test covers
     * the same stage shapes (two high-pass, five peaking/shelf stages plus
     * the mono-bass and exciter high-pass pair).
     */
    fun clarityChain(sampleRateHz: Double): List<NamedStage> = listOf(
        NamedStage("subBassHighPass", JvmBiquad.highPass(sampleRateHz, 24.0, 0.7071067811865476)),
        NamedStage("bassFoundation", JvmBiquad.peaking(sampleRateHz, 72.0, 0.80, 3.2)),
        NamedStage("lowMidSeparation", JvmBiquad.peaking(sampleRateHz, 280.0, 0.90, -3.0)),
        NamedStage("boxinessControl", JvmBiquad.peaking(sampleRateHz, 750.0, 0.85, -1.4)),
        NamedStage("presenceDetail", JvmBiquad.peaking(sampleRateHz, 3400.0, 0.85, 3.8)),
        NamedStage("airDetail", JvmBiquad.highShelf(sampleRateHz, 10500.0, 0.85, 4.8)),
        NamedStage("monoBassFilter", JvmBiquad.highPass(sampleRateHz, 130.0, 0.7071067811865476)),
        NamedStage("airExciterFilter", JvmBiquad.highPass(sampleRateHz, 6000.0, 0.7071067811865476)),
    )

    /** One equalizer band stage for the convergence test. */
    fun eqBand(sampleRateHz: Double, frequencyHz: Double, gainDb: Double): JvmBiquad =
        JvmBiquad.peaking(sampleRateHz, frequencyHz, EQ_Q, gainDb)

    /** Sample-exact bypass: copies input to a new array, never transforms. */
    object BitPerfectBypass {
        fun copy(input: FloatArray): FloatArray = input.copyOf()

        fun copyInto(input: FloatArray, output: FloatArray) {
            require(output.size >= input.size) { "Output must hold ${input.size} samples" }
            input.copyInto(output, endIndex = input.size)
        }
    }

    /**
     * Brick-wall ceiling used by tests to model the limiter guarantee:
     * output never exceeds 0 dBFS. Returns a new array; input untouched.
     */
    object JvmLimiter {
        const val CEILING_LINEAR = 1.0f

        fun limit(samples: FloatArray, ceiling: Float = CEILING_LINEAR): FloatArray {
            require(ceiling.isFinite() && ceiling > 0f) { "ceiling must be finite and positive" }
            return FloatArray(samples.size) { index ->
                val value = samples[index]
                if (!value.isFinite()) 0f else value.coerceIn(-ceiling, ceiling)
            }
        }
    }

    // ── Phase 2: Streaming Analysis DSP ─────────────────────────────────

    /** Default high-pass cutoff frequency separating "low" from "high" bands. */
    const val SPECTRAL_SPLIT_HZ = 300.0

    /** Weight of ZCR in the combined energy estimate. */
    const val ENERGY_WEIGHT_ZCR = 0.4f

    /** Weight of spectral ratio in the combined energy estimate. */
    const val ENERGY_WEIGHT_SPECTRAL = 0.6f

    /**
     * Root Mean Square of a window of interleaved Float32 samples.
     * Non-finite samples are skipped. Returns 0 if no finite samples exist.
     * Read-only: input array is never written to.
     */
    fun rms(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): Float {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "Window [$offset, ${offset + length}) is outside ${samples.size} samples"
        }
        if (length == 0) return 0f
        var sumSquares = 0.0
        var count = 0L
        for (i in offset until offset + length) {
            val v = samples[i]
            if (!v.isFinite()) continue
            sumSquares += v.toDouble() * v.toDouble()
            count++
        }
        if (count == 0L) return 0f
        return sqrt(sumSquares / count.toDouble()).toFloat()
    }

    /**
     * Convenience: RMS in dBFS, floored at [MIN_DBFS].
     */
    fun rmsDb(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): Float = amplitudeToDbfs(rms(samples, offset, length))

    /**
     * Zero-Crossing Rate: fraction of consecutive sample pairs where the sign
     * changes. Normalized to [0, 1]. High ZCR → bright/percussive signal.
     * Low ZCR → warm/smooth signal. Non-finite samples are treated as zero.
     * Read-only: input array is never written to.
     */
    fun zeroCrossingRate(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): Float {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "Window [$offset, ${offset + length}) is outside ${samples.size} samples"
        }
        if (length < 2) return 0f
        var crossings = 0L
        var prevPositive = samples[offset].let { if (it.isFinite()) it >= 0f else true }
        for (i in offset + 1 until offset + length) {
            val v = samples[i]
            val currentPositive = if (v.isFinite()) v >= 0f else true
            if (currentPositive != prevPositive) crossings++
            prevPositive = currentPositive
        }
        return (crossings.toFloat() / (length - 1).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Spectral energy ratio: measures the proportion of energy above
     * [SPECTRAL_SPLIT_HZ] relative to the total signal energy. Uses a
     * 2nd-order Butterworth high-pass filter ([JvmBiquad.highPass]) to
     * isolate the high-frequency band.
     *
     * Returns a value in [0, 1]:
     * - High ratio → bright, energetic signal (EDM, rock, hip-hop).
     * - Low ratio → bass-heavy, warm signal (lo-fi, acoustic, ballad).
     *
     * If the total RMS is negligible (< 1e-10), returns 0.5 (neutral).
     * Read-only: input array is never written to; the filter operates on
     * an internal copy.
     *
     * @param samples Interleaved Float32 PCM.
     * @param sampleRateHz Sample rate in Hz (e.g. 48000).
     * @param channelCount Number of interleaved channels (1 or 2).
     */
    fun spectralEnergyRatio(
        samples: FloatArray,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 2,
    ): Float {
        if (samples.isEmpty()) return 0.5f

        // De-interleave to mono for analysis (average channels).
        val monoLength = samples.size / channelCount.coerceAtLeast(1)
        if (monoLength == 0) return 0.5f
        val mono = FloatArray(monoLength)
        if (channelCount <= 1) {
            samples.copyInto(mono, endIndex = monoLength.coerceAtMost(samples.size))
        } else {
            for (i in 0 until monoLength) {
                var sum = 0f
                for (ch in 0 until channelCount) {
                    val idx = i * channelCount + ch
                    if (idx < samples.size) {
                        val v = samples[idx]
                        sum += if (v.isFinite()) v else 0f
                    }
                }
                mono[i] = sum / channelCount
            }
        }

        // Total RMS
        val totalRms = rms(mono).toDouble()
        if (totalRms < 1e-10) return 0.5f

        // High-pass filter to isolate above SPECTRAL_SPLIT_HZ
        val hp = JvmBiquad.highPass(sampleRateHz.toDouble(), SPECTRAL_SPLIT_HZ, 0.7071067811865476)
        val highBand = hp.processMono(mono)
        val highRms = rms(highBand).toDouble()

        return (highRms / totalRms).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Combined energy estimate from Zero-Crossing Rate and spectral ratio.
     * Returns a value in [0, 1] suitable for [TrackTransitionMetadata.energy].
     *
     * Formula: `(ENERGY_WEIGHT_ZCR * zcr + ENERGY_WEIGHT_SPECTRAL * spectralRatio)`
     */
    fun estimateEnergy(zcr: Float, spectralRatio: Float): Float {
        val safeZcr = if (zcr.isFinite()) zcr.coerceIn(0f, 1f) else 0f
        val safeRatio = if (spectralRatio.isFinite()) spectralRatio.coerceIn(0f, 1f) else 0.5f
        return (ENERGY_WEIGHT_ZCR * safeZcr + ENERGY_WEIGHT_SPECTRAL * safeRatio).coerceIn(0f, 1f)
    }

    // ── Phase 3: BPM Detection DSP ──────────────────────────────────────

    /** Minimum detectable BPM (60 = 1 beat per second). */
    const val BPM_MIN = 60.0f

    /** Maximum detectable BPM (200 = fast EDM / drum & bass). */
    const val BPM_MAX = 200.0f

    /** Hop size for onset envelope computation (~10 ms at 48 kHz). */
    const val BPM_HOP_SIZE = 512

    /**
     * Minimum onset envelope peak-to-mean ratio required for a "confident"
     * BPM estimate. Below this threshold the signal lacks clear beats
     * (ambient, spoken word, etc.) and `null` is returned.
     */
    private const val BPM_CONFIDENCE_THRESHOLD = 1.3f

    /**
     * Estimates BPM from interleaved Float32 PCM samples using onset
     * envelope auto-correlation.
     *
     * **Algorithm:**
     * 1. De-interleave to mono (channel average).
     * 2. Compute short-term energy envelope in [BPM_HOP_SIZE] windows.
     * 3. Differentiate + half-wave rectify → onset strength signal.
     * 4. Auto-correlate the onset signal over the BPM range.
     * 5. Peak-pick the dominant periodicity → convert lag to BPM.
     *
     * Returns `null` if the signal is too quiet, too short, or has no
     * clear rhythmic periodicity (e.g. ambient pads, speech).
     *
     * **Computational cost:** ~2-5 ms for 3 seconds of stereo 48 kHz audio.
     *
     * @param samples Interleaved Float32 PCM.
     * @param sampleRateHz Sample rate (e.g. 48000).
     * @param channelCount Interleaved channels (1 or 2).
     * @return Estimated BPM in [BPM_MIN]..[BPM_MAX], or null.
     */
    fun estimateBpm(
        samples: FloatArray,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 2,
    ): Float? {
        if (samples.isEmpty() || sampleRateHz <= 0 || channelCount <= 0) return null

        // De-interleave to mono
        val monoLength = samples.size / channelCount
        if (monoLength < BPM_HOP_SIZE * 4) return null // Need at least ~40ms of audio
        val mono = FloatArray(monoLength)
        if (channelCount <= 1) {
            samples.copyInto(mono, endIndex = monoLength.coerceAtMost(samples.size))
        } else {
            for (i in 0 until monoLength) {
                var sum = 0f
                for (ch in 0 until channelCount) {
                    val idx = i * channelCount + ch
                    if (idx < samples.size) {
                        val v = samples[idx]
                        sum += if (v.isFinite()) v else 0f
                    }
                }
                mono[i] = sum / channelCount
            }
        }

        // Compute onset envelope
        val envelope = onsetEnvelope(mono, BPM_HOP_SIZE)
        if (envelope.size < 4) return null

        // Gate: if onset strength is negligible, there are no detectable beats
        // (silence, continuous tone, ambient). Real click/beat onsets produce
        // values of 0.3–0.5; continuous tones produce ~0.01 from phase jitter.
        val maxOnset = envelope.max()
        if (maxOnset < 0.02f) return null

        // Convert BPM range to lag range (in onset frames)
        val framesPerSecond = sampleRateHz.toFloat() / BPM_HOP_SIZE
        val minLag = (framesPerSecond * 60f / BPM_MAX).toInt().coerceAtLeast(1)
        val maxLag = (framesPerSecond * 60f / BPM_MIN).toInt().coerceAtMost(envelope.size - 1)

        if (minLag >= maxLag || maxLag >= envelope.size) return null

        // Auto-correlate
        val correlation = autoCorrelate(envelope, minLag, maxLag)

        // Peak-pick: find the lag with maximum correlation
        var bestLag = minLag
        var bestVal = Float.NEGATIVE_INFINITY
        for (lag in correlation.indices) {
            if (correlation[lag] > bestVal) {
                bestVal = correlation[lag]
                bestLag = lag + minLag
            }
        }

        // Confidence check: peak must be significantly above mean
        val mean = correlation.average().toFloat()
        if (mean <= 0f || bestVal / mean < BPM_CONFIDENCE_THRESHOLD) return null

        // Convert lag (in onset frames) to BPM
        val bpm = 60f * framesPerSecond / bestLag
        return if (bpm in BPM_MIN..BPM_MAX) bpm else null
    }

    /**
     * Computes an onset strength envelope from mono samples.
     *
     * Each frame is the short-term RMS energy over [hopSize] samples.
     * The envelope is then differentiated (first difference) and half-wave
     * rectified: only energy *increases* count as onsets.
     *
     * @param mono Mono Float32 samples.
     * @param hopSize Samples per frame (~10ms at 48kHz with 512).
     * @return Onset strength envelope (one value per frame).
     */
    internal fun onsetEnvelope(mono: FloatArray, hopSize: Int): FloatArray {
        val numFrames = mono.size / hopSize
        if (numFrames < 2) return FloatArray(0)

        // Short-term energy per frame
        val energy = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val start = f * hopSize
            val end = (start + hopSize).coerceAtMost(mono.size)
            var sumSq = 0.0
            for (i in start until end) {
                val v = mono[i]
                if (v.isFinite()) sumSq += v.toDouble() * v.toDouble()
            }
            energy[f] = sqrt(sumSq / (end - start).coerceAtLeast(1).toDouble()).toFloat()
        }

        // First difference + half-wave rectification (only increases = onsets)
        val onset = FloatArray(numFrames - 1)
        for (f in 0 until numFrames - 1) {
            val diff = energy[f + 1] - energy[f]
            onset[f] = if (diff > 0f) diff else 0f
        }
        return onset
    }

    /**
     * Auto-correlation of the onset envelope over the range
     * [minLag]..[maxLag]. Returns an array of correlation values
     * indexed as `[0] = correlation at minLag`.
     *
     * Uses normalized unbiased auto-correlation:
     * `R(lag) = sum(onset[i] * onset[i+lag]) / (N - lag)`
     *
     * @param envelope Onset strength signal.
     * @param minLag Minimum lag (inclusive).
     * @param maxLag Maximum lag (inclusive).
     * @return Correlation values for each lag in range.
     */
    internal fun autoCorrelate(envelope: FloatArray, minLag: Int, maxLag: Int): FloatArray {
        val n = envelope.size
        val result = FloatArray(maxLag - minLag + 1)
        for (lagOffset in result.indices) {
            val lag = lagOffset + minLag
            var sum = 0.0
            val count = n - lag
            if (count <= 0) continue
            for (i in 0 until count) {
                sum += envelope[i].toDouble() * envelope[i + lag].toDouble()
            }
            result[lagOffset] = (sum / count).toFloat()
        }
        return result
    }
}
