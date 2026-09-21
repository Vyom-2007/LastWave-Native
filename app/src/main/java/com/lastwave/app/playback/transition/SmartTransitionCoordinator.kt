package com.lastwave.app.playback.transition

import androidx.media3.common.Player
import com.lastwave.app.playback.NativeAudioEngine

/**
 * Coordinates transition selection, execution, tick progress, and cancellation
 * between [com.lastwave.app.playback.MusicPlayer] and the appropriate [TransitionExecutor].
 */
class SmartTransitionCoordinator(
    private val executors: Map<TransitionType, TransitionExecutor> = mapOf(
        TransitionType.FALLBACK_CROSSFADE to FallbackCrossfadeExecutor(),
        TransitionType.SMOOTH_BLEND to SmoothBlendExecutor(),
        TransitionType.IMPACT_DROP to ImpactDropExecutor(),
        TransitionType.HOOK_BRIDGE to HookBridgeExecutor()
    )
) {
    var activePlan: TransitionPlan? = null
        private set

    private var activeExecutor: TransitionExecutor? = null
    private var transitionStartTimeMs: Long = 0L
    private var activeOverlapMs: Long = 0L

    val isActive: Boolean
        get() = activePlan != null

    /**
     * Selects and initiates a transition handoff.
     */
    fun startTransition(
        outgoingMeta: TrackTransitionMetadata?,
        incomingMeta: TrackTransitionMetadata?,
        crossfadeDurationMs: Long,
        overlapDurationMs: Long,
        enabled: Boolean,
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    ): TransitionPlan {
        val plan = runCatching {
            SmartTransitionSelector.choose(
                outgoing = outgoingMeta,
                incoming = incomingMeta,
                crossfadeDurationMs = crossfadeDurationMs,
                enabled = enabled
            )
        }.getOrDefault(TransitionPlan.fallback(crossfadeDurationMs))

        val executor = executors[plan.type] ?: executors.getValue(TransitionType.FALLBACK_CROSSFADE)

        activePlan = plan
        activeExecutor = executor
        transitionStartTimeMs = System.currentTimeMillis()
        activeOverlapMs = overlapDurationMs

        runCatching {
            executor.onStart(plan, incoming, outgoing, outgoingEngine)
        }.onFailure {
            // If starting fails, fall back to safe crossfade
            val fallback = executors.getValue(TransitionType.FALLBACK_CROSSFADE)
            activePlan = TransitionPlan.fallback(crossfadeDurationMs)
            activeExecutor = fallback
            fallback.onStart(activePlan!!, incoming, outgoing, outgoingEngine)
        }

        return activePlan!!
    }

    /**
     * Ticks the active transition lifecycle.
     *
     * @return true if the transition completed its lifecycle and should end.
     */
    fun onTick(
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    ): Boolean {
        val plan = activePlan ?: return true
        val executor = activeExecutor ?: return true

        val elapsedMs = (System.currentTimeMillis() - transitionStartTimeMs).coerceAtLeast(0L)

        val isComplete = runCatching {
            executor.onTick(
                elapsedMs = elapsedMs,
                overlapDurationMs = activeOverlapMs,
                plan = plan,
                incoming = incoming,
                outgoing = outgoing,
                outgoingEngine = outgoingEngine
            )
        }.getOrDefault(true)

        if (isComplete) {
            cancel(incoming, outgoing, outgoingEngine)
            return true
        }

        return false
    }

    /**
     * Cancels or completes the active transition and cleans up all audio state.
     */
    fun cancel(
        incoming: Player,
        outgoing: Player?,
        outgoingEngine: NativeAudioEngine?
    ) {
        val executor = activeExecutor
        activePlan = null
        activeExecutor = null
        transitionStartTimeMs = 0L
        activeOverlapMs = 0L

        runCatching {
            executor?.onFinish(incoming, outgoing, outgoingEngine)
        }
    }
}
