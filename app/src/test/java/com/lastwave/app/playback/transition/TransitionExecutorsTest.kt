package com.lastwave.app.playback.transition

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Unit tests for all [TransitionExecutor] implementations and [SmartTransitionCoordinator].
 */
class TransitionExecutorsTest {

    @Test
    fun fallbackCrossfade_adjustsVolumeOverEqualPowerCurve() {
        val executor = FallbackCrossfadeExecutor()
        val incoming = mockk<Player>(relaxed = true)
        val outgoing = mockk<Player>(relaxed = true)
        val plan = TransitionPlan.fallback(durationMs = 4000L)

        executor.onStart(plan, incoming, outgoing, null)
        verify { incoming.volume = 0f }
        verify { outgoing.volume = 1f }

        // Midpoint tick: 2000ms / 4000ms = progress 0.5 -> not finished yet
        val isFinished = executor.onTick(2000L, 4000L, plan, incoming, outgoing, null)
        assertThat(isFinished).isFalse()

        // End tick: 4000ms / 4000ms = 1.0 -> finished
        val finished = executor.onTick(4000L, 4000L, plan, incoming, outgoing, null)
        assertThat(finished).isTrue()

        executor.onFinish(incoming, outgoing, null)
        verify { incoming.volume = 1f }
    }

    @Test
    fun smoothBlend_executesCurveAndCleansUp() {
        val executor = SmoothBlendExecutor()
        val incoming = mockk<Player>(relaxed = true)
        val outgoing = mockk<Player>(relaxed = true)
        val plan = TransitionPlan(
            type = TransitionType.SMOOTH_BLEND,
            durationMs = 3000L,
            outgoingBassCutDb = -8.0f
        )

        executor.onStart(plan, incoming, outgoing, null)
        verify { incoming.volume = 0f }
        verify { outgoing.volume = 1f }

        val finished = executor.onTick(3000L, 3000L, plan, incoming, outgoing, null)
        assertThat(finished).isTrue()

        executor.onFinish(incoming, outgoing, null)
        verify { incoming.volume = 1f }
    }

    @Test
    fun impactDrop_fadesOutThenHoldsSilenceThenDropsIncoming() {
        val executor = ImpactDropExecutor()
        val incoming = mockk<Player>(relaxed = true)
        val outgoing = mockk<Player>(relaxed = true)
        val plan = TransitionPlan(
            type = TransitionType.IMPACT_DROP,
            durationMs = 150L,
            silenceGapMs = 400L
        )

        executor.onStart(plan, incoming, outgoing, null)

        // Phase 1: Rapid fade out at 75ms (halfway through 150ms fade)
        val phase1Done = executor.onTick(75L, 550L, plan, incoming, outgoing, null)
        assertThat(phase1Done).isFalse()
        verify { outgoing.volume = 0.5f }
        verify { incoming.volume = 0f }

        // Phase 2: Silence gap at 300ms (between 150ms and 550ms)
        val phase2Done = executor.onTick(300L, 550L, plan, incoming, outgoing, null)
        assertThat(phase2Done).isFalse()
        verify { outgoing.volume = 0f }
        verify { incoming.volume = 0f }

        // Phase 3: Impact drop at 600ms (after 550ms)
        val phase3Done = executor.onTick(600L, 550L, plan, incoming, outgoing, null)
        assertThat(phase3Done).isTrue()
        verify { incoming.volume = 1f }
    }

    @Test
    fun hookBridge_seeksToHookAndCrossfadesTail() {
        val executor = HookBridgeExecutor()
        val incoming = mockk<Player>(relaxed = true)
        val outgoing = mockk<Player>(relaxed = true)
        val plan = TransitionPlan(
            type = TransitionType.HOOK_BRIDGE,
            durationMs = 3000L,
            hookStartMs = 45_000L,
            hookEndMs = 48_000L
        )

        executor.onStart(plan, incoming, outgoing, null)
        verify { outgoing.seekTo(45_000L) }

        // At 1000ms: Still looping hook, incoming is silent
        val midDone = executor.onTick(1000L, 3000L, plan, incoming, outgoing, null)
        assertThat(midDone).isFalse()
        verify { incoming.volume = 0f }

        // At 3000ms: Finished
        val finished = executor.onTick(3000L, 3000L, plan, incoming, outgoing, null)
        assertThat(finished).isTrue()

        executor.onFinish(incoming, outgoing, null)
        verify { incoming.volume = 1f }
    }

    @Test
    fun coordinator_lifecycleStartAndTickAndCancel() {
        val coordinator = SmartTransitionCoordinator()
        val incoming = mockk<Player>(relaxed = true)
        val outgoing = mockk<Player>(relaxed = true)

        val outgoingMeta = TrackTransitionMetadata(
            trackId = "song_1",
            bpm = 120f,
            energy = 0.7f
        )
        val incomingMeta = TrackTransitionMetadata(
            trackId = "song_2",
            bpm = 122f,
            energy = 0.72f
        )

        val plan = coordinator.startTransition(
            outgoingMeta = outgoingMeta,
            incomingMeta = incomingMeta,
            crossfadeDurationMs = 5000L,
            overlapDurationMs = 5000L,
            enabled = true,
            incoming = incoming,
            outgoing = outgoing,
            outgoingEngine = null
        )

        assertThat(coordinator.isActive).isTrue()
        assertThat(plan.type).isEqualTo(TransitionType.SMOOTH_BLEND)

        // Cancel resets coordinator state
        coordinator.cancel(incoming, outgoing, null)
        assertThat(coordinator.isActive).isFalse()
        assertThat(coordinator.activePlan).isNull()
    }
}
