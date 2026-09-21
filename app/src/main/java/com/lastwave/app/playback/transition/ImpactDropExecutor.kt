package com.lastwave.app.playback.transition

import androidx.media3.common.Player
import com.lastwave.app.playback.NativeAudioEngine

/**
 * Impact Drop executor.
 *
 * Executes a dramatic track handoff suited for significant tempo or genre shifts:
 * 1. Rapid fade out: Outgoing track volume drops to 0 within [TransitionPlan.durationMs] (typically 150ms).
 * 2. Dramatic silence gap: Both players remain silent for [TransitionPlan.silenceGapMs] (typically 300-500ms).
 * 3. Impact drop: Incoming track drops cleanly at full volume (1.0f) right on beat 1.
 */
class ImpactDropExecutor : TransitionExecutor {
    override val type: TransitionType = TransitionType.IMPACT_DROP

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
        val cutDurationMs = plan.durationMs.coerceAtLeast(1L)
        val gapDurationMs = plan.silenceGapMs.coerceAtLeast(0L)
        val totalDropMs = cutDurationMs + gapDurationMs

        return when {
            elapsedMs < cutDurationMs -> {
                // Phase 1: Rapid cut of outgoing track
                val fadeProgress = (elapsedMs.toFloat() / cutDurationMs).coerceIn(0f, 1f)
                outgoing.volume = (1f - fadeProgress).coerceIn(0f, 1f)
                incoming.volume = 0f
                outgoing.playWhenReady = true
                false
            }
            elapsedMs < totalDropMs -> {
                // Phase 2: Intentional silence gap
                outgoing.volume = 0f
                incoming.volume = 0f
                false
            }
            else -> {
                // Phase 3: Incoming track drops with full impact
                outgoing.volume = 0f
                incoming.volume = 1f
                true
            }
        }
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
