package com.lastwave.app.playback.transition

import androidx.media3.common.Player
import com.lastwave.app.playback.NativeAudioEngine

/**
 * Hook Bridge executor.
 *
 * Loops the current track's hook/chorus section for 2-4 seconds to build anticipation,
 * then cleanly transitions into the incoming track.
 *
 * Safety Invariant:
 * Only chosen if the outgoing track is confirmed seekable (downloaded/local).
 * If any seek anomaly occurs, fails gracefully by completing the handoff cleanly.
 */
class HookBridgeExecutor : TransitionExecutor {
    override val type: TransitionType = TransitionType.HOOK_BRIDGE

    private val crossfadeTailMs = 500L

    override fun onStart(
        plan: TransitionPlan,
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    ) {
        incoming.volume = 0f
        outgoing.volume = 1f

        plan.hookStartMs?.let { hookPos ->
            runCatching {
                outgoing.seekTo(hookPos)
            }
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
        val totalMs = plan.durationMs.coerceAtLeast(1000L)

        if (elapsedMs >= totalMs) {
            incoming.volume = 1f
            outgoing.volume = 0f
            return true
        }

        val tailStartMs = (totalMs - crossfadeTailMs).coerceAtLeast(0L)
        if (elapsedMs >= tailStartMs) {
            val tailProgress = ((elapsedMs - tailStartMs).toFloat() / crossfadeTailMs).coerceIn(0f, 1f)
            incoming.volume = tailProgress
            outgoing.volume = (1f - tailProgress).coerceIn(0f, 1f)
        } else {
            incoming.volume = 0f
            outgoing.volume = 1f
        }

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
