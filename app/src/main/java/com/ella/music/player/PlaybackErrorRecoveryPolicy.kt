package com.ella.music.player

internal data class PlaybackQueueOccurrence(val mediaId: String, val index: Int)

internal sealed interface PlaybackErrorRecoveryAction {
    data class Advance(val index: Int) : PlaybackErrorRecoveryAction
    data object Stop : PlaybackErrorRecoveryAction
    data object Ignore : PlaybackErrorRecoveryAction
}

/** One unsuccessful pass is enough, even if a shuffle rebuild assigns fresh media IDs. */
internal class PlaybackErrorRecoveryPolicy {
    private val failed = mutableSetOf<PlaybackQueueOccurrence>()
    private var failureLimit: Int? = null
    private var stopped = false
    val hasFailures: Boolean get() = failed.isNotEmpty()

    fun reset() {
        failed.clear()
        failureLimit = null
        stopped = false
    }

    fun onFailure(
        current: PlaybackQueueOccurrence,
        queueSize: Int,
        next: PlaybackQueueOccurrence?
    ): PlaybackErrorRecoveryAction {
        if (stopped || !failed.add(current)) return PlaybackErrorRecoveryAction.Ignore
        val limit = minOf(failureLimit ?: queueSize, queueSize)
        failureLimit = limit
        if (failed.size >= limit || next == null || next.index !in 0 until queueSize || next in failed) {
            stopped = true
            return PlaybackErrorRecoveryAction.Stop
        }
        return PlaybackErrorRecoveryAction.Advance(next.index)
    }
}
