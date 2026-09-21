package com.lastwave.app.playback.transition

import androidx.media3.common.Player
import com.lastwave.app.playback.NativeAudioEngine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Smooth Blend executor.
 *
 * Combines an equal-power volume crossfade with dynamic low-frequency attenuation (-6dB to -8dB)
 * on the outgoing track's native equalizer. This clears acoustic headroom for the incoming track's
 * rhythm section and prevents bass clashing.
 */
class SmoothBlendExecutor : TransitionExecutor {
    override val type: TransitionType = TransitionType.SMOOTH_BLEND

    private var bassCutApplied = false

    override fun onStart(
        plan: TransitionPlan,
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    ) {
        incoming.volume = 0f
        outgoing.volume = 1f
        bassCutApplied = false

        if (outgoingEngine != null && plan.outgoingBassCutDb < 0f) {
            applyBassCut(outgoingEngine, plan.outgoingBassCutDb)
            bassCutApplied = true
        }
    }

    override fun onTick(
        elapsedMs: Long,
        overlapDurationMs: Long,
        plan: TransitionPlan,
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    ): Boolean {
        val totalMs = minOf(plan.durationMs, overlapDurationMs).coerceAtLeast(1L)
        val progress = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)

        if (progress >= 1f) {
            incoming.volume = 1f
            outgoing.volume = 0f
            return true
        }

        val angle = progress * (PI / 2.0)
        incoming.volume = sin(angle).toFloat()
        outgoing.volume = cos(angle).toFloat()
        outgoing.playWhenReady = incoming.isPlaying
        return false
    }

    override fun onFinish(
        incoming: Player,
        outgoing: Player?,
        outgoingEngine: NativeAudioEngine?
    ) {
        incoming.volume = 1f
        outgoing?.volume = 0f

        if (bassCutApplied && outgoingEngine != null) {
            resetBassCut(outgoingEngine)
            bassCutApplied = false
        }
    }

    private fun applyBassCut(engine: NativeAudioEngine, cutDb: Float) {
        runCatching {
            // NativeAudioEngine has 15 EQ bands. Bands 0..4 correspond to 25Hz, 40Hz, 63Hz, 100Hz, 160Hz.
            val gains = FloatArray(EQUALIZER_BAND_COUNT) { index ->
                if (index < 5) cutDb else 0f
            }
            engine.setEqualizer(enabled = true, gainsDb = gains)
        }
    }

    private fun resetBassCut(engine: NativeAudioEngine) {
        runCatching {
            val flatGains = FloatArray(EQUALIZER_BAND_COUNT) { 0f }
            engine.setEqualizer(enabled = false, gainsDb = flatGains)
        }
    }

    companion object {
        const val EQUALIZER_BAND_COUNT = 15
    }
}
