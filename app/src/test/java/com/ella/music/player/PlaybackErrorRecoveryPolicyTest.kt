package com.ella.music.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorRecoveryPolicyTest {
    @Test fun duplicateErrorCallbacksDoNotConsumeAnotherQueueAttempt() {
        val policy = PlaybackErrorRecoveryPolicy()
        val first = PlaybackQueueOccurrence("first", 0)
        val next = PlaybackQueueOccurrence("second", 1)
        assertEquals(PlaybackErrorRecoveryAction.Advance(1), policy.onFailure(first, 2, next))
        assertEquals(PlaybackErrorRecoveryAction.Ignore, policy.onFailure(first, 2, next))
        assertEquals(PlaybackErrorRecoveryAction.Stop, policy.onFailure(next, 2, first))
    }

    @Test fun duplicateMediaIdsAtDifferentQueuePositionsRemainSeparateAttempts() {
        val policy = PlaybackErrorRecoveryPolicy()
        val first = PlaybackQueueOccurrence("same-song", 0)
        val second = PlaybackQueueOccurrence("same-song", 1)
        assertEquals(PlaybackErrorRecoveryAction.Advance(1), policy.onFailure(first, 2, second))
        assertEquals(PlaybackErrorRecoveryAction.Stop, policy.onFailure(second, 2, first))
    }

    @Test fun shuffleRebuildsWithFreshMediaIdsCannotExtendTheFailureBudget() {
        val policy = PlaybackErrorRecoveryPolicy()
        repeat(3) { attempt ->
            val current = PlaybackQueueOccurrence("generation-$attempt", attempt)
            val next = PlaybackQueueOccurrence("next-generation-$attempt", (attempt + 1) % 3)
            val expected = if (attempt == 2) PlaybackErrorRecoveryAction.Stop else PlaybackErrorRecoveryAction.Advance(next.index)
            assertEquals(expected, policy.onFailure(current, 3, next))
        }
        assertEquals(PlaybackErrorRecoveryAction.Ignore,
            policy.onFailure(PlaybackQueueOccurrence("late-error", 0), 3, PlaybackQueueOccurrence("other", 1)))
    }

    @Test fun selectedTraversalOrderIsRespectedAndFailedTargetsAreNotRetried() {
        val policy = PlaybackErrorRecoveryPolicy()
        val first = PlaybackQueueOccurrence("first", 0)
        val shuffledNext = PlaybackQueueOccurrence("third", 2)
        assertEquals(PlaybackErrorRecoveryAction.Advance(2), policy.onFailure(first, 4, shuffledNext))
        assertEquals(PlaybackErrorRecoveryAction.Stop, policy.onFailure(shuffledNext, 4, first))
    }

    @Test fun queueEndAndSingleTrackRepeatStopWithoutAnotherPrepare() {
        val current = PlaybackQueueOccurrence("only", 0)
        assertEquals(PlaybackErrorRecoveryAction.Stop, PlaybackErrorRecoveryPolicy().onFailure(current, 4, null))
        assertEquals(PlaybackErrorRecoveryAction.Stop, PlaybackErrorRecoveryPolicy().onFailure(current, 1, current))
    }

    @Test fun successOrAnExplicitRetryStartsANewFailurePass() {
        val policy = PlaybackErrorRecoveryPolicy()
        val first = PlaybackQueueOccurrence("first", 0)
        val second = PlaybackQueueOccurrence("second", 1)
        policy.onFailure(first, 2, second)
        policy.onFailure(second, 2, first)
        policy.reset()
        assertEquals(PlaybackErrorRecoveryAction.Advance(1), policy.onFailure(first, 2, second))
    }
}
