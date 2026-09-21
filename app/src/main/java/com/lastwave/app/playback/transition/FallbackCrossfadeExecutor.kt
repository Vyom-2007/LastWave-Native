package com.lastwave.app.playback.transition

import androidx.media3.common.Player
import com.lastwave.app.playback.NativeAudioEngine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Standard equal-power sine/cosine crossfade executor.
 * Mirrors the exact mathematical behavior of LastWave's default handoff.
 */
class FallbackCrossfadeExecutor : TransitionExecutor {
    override val type: TransitionType = TransitionType.FALLBACK_CROSSFADE

    override fun onStart(
        plan: TransitionPlan,
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    ) {
        incoming.volume = 0f
        outgoing.volume = 1f
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
    }
}
