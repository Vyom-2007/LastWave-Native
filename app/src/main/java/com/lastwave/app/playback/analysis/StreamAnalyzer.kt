package com.lastwave.app.playback.analysis

import com.lastwave.app.playback.transition.TrackTransitionMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background analysis engine that consumes decoded PCM from a [PcmTapAudioProcessor],
 * accumulates a configurable window of audio samples, and produces a
 * [TrackTransitionMetadata] with estimated energy and loudness values.
 *
 * **Threading:** Runs as a `suspend` function on `Dispatchers.Default` (or any
 * background dispatcher). Polls the tap's ring buffer at ~20 Hz (50 ms intervals),
 * which is negligible CPU overhead. Automatically cancels via structured concurrency
 * when the parent Job is cancelled.
 *
 * **Fail-open:** If the analysis window hasn't filled within [timeoutMs] (e.g. due
 * to slow network buffering), returns `null`. The caller (MusicPlayer) treats null
 * as "no metadata available" and the SmartTransitionSelector falls back to the safe
 * `-8dB Bass-Cut Smooth Blend`.
 *
 * **Battery protection:** The analyzer coroutine terminates the moment the analysis
 * window completes. It does NOT run for the duration of the song.
 *
 * @param tap The PCM tap attached to the standby player's audio processor chain.
 * @param analysisWindowSeconds Duration of audio to accumulate before analysis (default: 3s).
 * @param pollIntervalMs Interval between drain attempts (default: 50ms = 20 Hz).
 * @param timeoutMs Maximum wall-clock time to wait for enough audio (default: 10s).
 */
class StreamAnalyzer(
    private val tap: PcmTapAudioProcessor,
    private val analysisWindowSeconds: Float = DEFAULT_ANALYSIS_WINDOW_SECONDS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    companion object {
        const val DEFAULT_ANALYSIS_WINDOW_SECONDS = 3.0f
        const val DEFAULT_POLL_INTERVAL_MS = 50L
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Drain buffer size: 4096 floats per poll (small, no heap pressure). */
        private const val DRAIN_BUFFER_SIZE = 4096

        /**
         * Analyzes pre-decoded PCM samples and returns metadata.
         *
         * Used by the [HeadlessStreamDecoder] path where audio is decoded
         * at CPU speed and provided as a [HeadlessStreamDecoder.DecodedAudio]
         * buffer, bypassing the [PcmTapAudioProcessor] ring buffer entirely.
         *
         * @param trackId Unique identifier for the track being analyzed.
         * @param audio Decoded audio from [HeadlessStreamDecoder].
         * @return [TrackTransitionMetadata] with computed energy, loudness, and BPM.
         */
        fun analyzeFromSamples(
            trackId: String,
            audio: HeadlessStreamDecoder.DecodedAudio,
        ): TrackTransitionMetadata {
            val samples = audio.samples
            val loudnessDb = DspMath.rmsDb(samples)
            val zcr = DspMath.zeroCrossingRate(samples)
            val spectralRatio = DspMath.spectralEnergyRatio(
                samples, audio.sampleRateHz, audio.channelCount
            )
            val energy = DspMath.estimateEnergy(zcr, spectralRatio)
            val bpm = DspMath.estimateBpm(samples, audio.sampleRateHz, audio.channelCount)

            return TrackTransitionMetadata(
                trackId = trackId,
                loudnessDb = loudnessDb,
                energy = energy,
                bpm = bpm,
                isSeekable = false, // Streaming tracks are not seekable
            )
        }
    }

    /**
     * Analyzes the incoming audio stream and returns metadata suitable for
     * the [SmartTransitionSelector].
     *
     * This function suspends until either:
     * 1. [analysisWindowSeconds] of audio has been accumulated and analyzed.
     * 2. [timeoutMs] elapses without enough data (returns `null`).
     * 3. The coroutine is cancelled (throws [CancellationException]).
     *
     * @param trackId Unique identifier for the track being analyzed (e.g. videoId).
     * @return [TrackTransitionMetadata] with energy and loudness, or `null` on timeout.
     */
    suspend fun analyze(trackId: String): TrackTransitionMetadata? {
        val sampleRate = tap.sampleRateHz
        val channelCount = tap.channelCount
        val targetSamples = (analysisWindowSeconds * sampleRate * channelCount).toInt()

        if (targetSamples <= 0) return null

        // Accumulation buffer — allocated once, grows as needed
        val accumulated = ArrayList<Float>(targetSamples)
        val drainBuffer = FloatArray(DRAIN_BUFFER_SIZE)

        val result = withTimeoutOrNull(timeoutMs) {
            while (accumulated.size < targetSamples) {
                currentCoroutineContext().ensureActive()

                val drained = tap.drainSamples(drainBuffer)
                if (drained > 0) {
                    val toAdd = (targetSamples - accumulated.size).coerceAtMost(drained)
                    for (i in 0 until toAdd) {
                        accumulated.add(drainBuffer[i])
                    }
                }

                if (accumulated.size < targetSamples) {
                    delay(pollIntervalMs)
                }
            }
            true
        }

        if (result == null || accumulated.size < targetSamples) {
            // Timeout: not enough audio arrived in time
            return null
        }

        // Convert to FloatArray for DSP processing
        val samples = accumulated.toFloatArray()

        // Run the DSP math
        val loudnessDb = DspMath.rmsDb(samples)
        val zcr = DspMath.zeroCrossingRate(samples)
        val spectralRatio = DspMath.spectralEnergyRatio(samples, sampleRate, channelCount)
        val energy = DspMath.estimateEnergy(zcr, spectralRatio)

        return TrackTransitionMetadata(
            trackId = trackId,
            loudnessDb = loudnessDb,
            energy = energy,
            isSeekable = false, // Streaming tracks are not seekable
        )
    }
}
