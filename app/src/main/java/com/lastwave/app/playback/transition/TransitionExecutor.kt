package com.lastwave.app.playback.transition

import androidx.media3.common.Player
import com.lastwave.app.playback.NativeAudioEngine

/**
 * Common contract for executing a specific transition mode between two ExoPlayers.
 */
interface TransitionExecutor {
    val type: TransitionType

    /**
     * Initializes playback and DSP state when the transition handoff begins.
     *
     * @param plan The active transition plan.
     * @param incoming The player for the incoming track (prepared, initially silent).
     * @param outgoing The player for the outgoing track (active, currently playing).
     * @param outgoingEngine Optional native audio engine for the outgoing player.
     */
    fun onStart(
        plan: TransitionPlan,
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    )

    /**
     * Updates volume envelopes, EQ curves, or seek offsets on each playback frame/tick.
     *
     * @param elapsedMs Elapsed duration in milliseconds since transition start.
     * @param overlapDurationMs Maximum allowed overlap duration in milliseconds.
     * @param plan The active transition plan.
     * @param incoming The incoming track player.
     * @param outgoing The outgoing track player.
     * @param outgoingEngine Optional native audio engine for the outgoing player.
     * @return true if the transition has reached its natural conclusion.
     */
    fun onTick(
        elapsedMs: Long,
        overlapDurationMs: Long,
        plan: TransitionPlan,
        incoming: Player,
        outgoing: Player,
        outgoingEngine: NativeAudioEngine?
    ): Boolean

    /**
     * Restores players and engines to clean non-transition states upon completion or cancellation.
     *
     * @param incoming The incoming track player.
     * @param outgoing The outgoing track player (null if already cleared/released).
     * @param outgoingEngine Optional native audio engine for the outgoing player.
     */
    fun onFinish(
        incoming: Player,
        outgoing: Player?,
        outgoingEngine: NativeAudioEngine?
    )
}
