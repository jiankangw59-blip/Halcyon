package com.ella.music.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.ella.music.data.model.Song
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackErrorRecoveryTest {
    @Test fun goneFirstStreamPreparesTheSecondAndPreservesPlaybackIntent() = withQueue { player, manager, controller ->
        player.fail()
        drainUntil { player.preparedIndices == listOf(1) && controller.currentMediaItemIndex == 1 }
        assertNull(controller.playerError)
        assertTrue(controller.playWhenReady)
        player.ready()
        drainUntil { controller.isPlaying && manager.currentQueueIndex.value == 1 }
        assertEquals("Song 1", manager.currentSong.value?.title)
        assertEquals(0, player.stops)
    }

    @Test fun thirtyUnavailableStreamsStopAfterOnePassDespiteRepeatAll() = withQueue(size = 30) { player, manager, controller ->
        repeat(30) { index ->
            player.fail()
            if (index < 29) {
                drainUntil { controller.currentMediaItemIndex == index + 1 && player.preparedIndices.size == index + 1 }
            } else {
                drainUntil { player.stops == 1 && !controller.playWhenReady }
            }
        }
        assertEquals((1..29).toList(), player.preparedIndices)
        assertEquals(29, controller.currentMediaItemIndex)
        assertFalse(manager.playWhenReady.value)
        assertFalse(controller.isPlaying)
    }

    @Test fun pauseIssuedWhileTheErrorCallbackIsPendingWinsOverRecovery() = withQueue { player, manager, controller ->
        player.fail()
        manager.pause()
        drainUntil { player.preparedIndices == listOf(1) && controller.currentMediaItemIndex == 1 }
        player.ready()
        drainUntil { controller.playbackState == Player.STATE_READY }
        assertFalse(controller.playWhenReady)
        assertFalse(controller.isPlaying)
        assertFalse(manager.playWhenReady.value)
    }

    @Test fun lastSequentialItemStopsWithoutWrapping() = withQueue(
        repeatMode = Player.REPEAT_MODE_OFF, startIndex = 2
    ) { player, _, controller ->
        player.fail()
        drainUntil { player.stops == 1 && !controller.playWhenReady }
        assertTrue(player.preparedIndices.isEmpty())
        assertEquals(2, controller.currentMediaItemIndex)
    }

    @Test fun repeatOneSkipsTheBrokenOccurrenceAndStillHasABound() = withQueue(
        size = 2, repeatMode = Player.REPEAT_MODE_ONE, duplicateSongs = true
    ) { player, _, controller ->
        val firstId = controller.currentMediaItem!!.mediaId
        player.fail()
        drainUntil { player.preparedIndices == listOf(1) && controller.currentMediaItemIndex == 1 }
        assertTrue(firstId != controller.currentMediaItem!!.mediaId)
        player.fail()
        drainUntil { player.stops == 1 && !controller.playWhenReady }
        assertEquals(listOf(1), player.preparedIndices)
    }

    @Test fun successfulPlaybackResetsTheConsecutiveFailurePass() = withQueue(size = 2) { player, _, controller ->
        player.fail()
        drainUntil { player.preparedIndices == listOf(1) && controller.currentMediaItemIndex == 1 }
        player.ready()
        drainUntil { controller.isPlaying }
        player.advancePosition(2_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100L))
        player.fail()
        drainUntil { player.preparedIndices == listOf(1, 0) && controller.currentMediaItemIndex == 0 }
        player.fail()
        drainUntil { player.stops == 1 }
    }

    @Test fun fleetingReadyStatesDoNotAllowUnavailableStreamsToRepeatForever() = withQueue(size = 3) { player, _, controller ->
        player.fail()
        drainUntil { player.preparedIndices == listOf(1) && controller.currentMediaItemIndex == 1 }
        player.ready()
        drainUntil { controller.isPlaying }
        player.fail()
        drainUntil { player.preparedIndices == listOf(1, 2) && controller.currentMediaItemIndex == 2 }
        player.ready()
        drainUntil { controller.isPlaying }
        player.fail()
        drainUntil { player.stops == 1 && !controller.playWhenReady }
        assertEquals(listOf(1, 2), player.preparedIndices)
    }

    @Test fun togglingAnErrorWithPlayIntentPausesBeforeASecondTapPrepares() = withQueue(size = 1) { player, manager, controller ->
        // Hold delivery of onPlayerError to inspect the two user transport commands directly.
        val listener = field<Player.Listener>(manager, "playerListener")
        controller.removeListener(listener)
        player.fail()
        drainUntil { controller.playerError != null }
        assertTrue(controller.playWhenReady)
        manager.togglePlayPause()
        drainUntil { !controller.playWhenReady }
        assertTrue(player.preparedIndices.isEmpty())
        manager.togglePlayPause()
        drainUntil { controller.playWhenReady && player.preparedIndices == listOf(0) }
        assertNull(controller.playerError)
        controller.addListener(listener)
    }

    @Test fun manualPreviousAlsoPreparesAnIdlePlayerAndPreservesPause() = withQueue(startIndex = 2) { player, manager, controller ->
        val listener = field<Player.Listener>(manager, "playerListener")
        controller.removeListener(listener)
        manager.pause()
        drainUntil { !controller.playWhenReady }
        player.fail()
        drainUntil { controller.playerError != null }
        manager.skipToPrevious()
        drainUntil { controller.currentMediaItemIndex == 1 && player.preparedIndices == listOf(1) }
        assertNull(controller.playerError)
        assertFalse(controller.playWhenReady)
        controller.addListener(listener)
    }

    @Test fun manualSelectionCancelsAnOldErrorRecoveryAndKeepsTheSelectedPauseIntent() = withQueue { player, manager, controller ->
        val manualSelection = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                manager.playQueueIndex(2, startPlayback = false)
            }
        }
        controller.addListener(manualSelection)
        player.fail()
        drainUntil { controller.currentMediaItemIndex == 2 && player.preparedIndices.isNotEmpty() }
        controller.removeListener(manualSelection)
        player.ready()
        drainUntil { controller.playbackState == Player.STATE_READY }
        assertEquals(listOf(2), player.preparedIndices)
        assertFalse(controller.playWhenReady)
        assertEquals(0, player.stops)
    }

    @Test fun manualRetryResetsTheFailurePassBeforePlaybackHasSucceeded() = withQueue(size = 2) { player, manager, controller ->
        player.fail()
        drainUntil { player.preparedIndices == listOf(1) && controller.currentMediaItemIndex == 1 }
        manager.playQueueIndex(0)
        drainUntil { controller.currentMediaItemIndex == 0 }
        player.fail()
        drainUntil { player.preparedIndices == listOf(1, 1) && controller.currentMediaItemIndex == 1 }
        assertEquals(0, player.stops)
    }

    @Test fun aNewQueueCanRecoverAfterThePreviousQueueExhaustedItsBudget() = withQueue(size = 2) { player, manager, controller ->
        player.fail()
        drainUntil { player.preparedIndices.size == 1 && controller.currentMediaItemIndex == 1 }
        player.fail()
        drainUntil { player.stops == 1 && !controller.playWhenReady }
        val songs = field<MutableList<Song>>(manager, "playlist").toList()
        manager.setPlaylist(songs, 0)
        drainUntil { player.preparedIndices == listOf(1, 0) && controller.currentMediaItemIndex == 0 }
        player.fail()
        drainUntil { player.preparedIndices == listOf(1, 0, 1) && controller.currentMediaItemIndex == 1 }
        assertTrue(controller.playWhenReady)
        assertEquals(1, player.stops)
    }

    private fun withQueue(
        size: Int = 3,
        repeatMode: Int = Player.REPEAT_MODE_ALL,
        startIndex: Int = 0,
        duplicateSongs: Boolean = false,
        block: (FailingQueuePlayer, ExoPlayerManager, MediaController) -> Unit
    ) {
        val context = RuntimeEnvironment.getApplication()
        val songs = (0 until size).map { index ->
            val id = if (duplicateSongs) 0 else index
            Song(9500L + id, "Song $id", "Artist", "Album", 0L, 180_000L,
                "/music/$id.flac", "$id.flac")
        }
        val items = songs.mapIndexed { index, song ->
            MediaItem.Builder().setMediaId("queue:${song.id}:$index").setUri(song.path)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setExtras(song.toMediaItemExtras()).build()).build()
        }
        val player = FailingQueuePlayer(items, repeatMode, startIndex)
        val session = MediaSession.Builder(context, player).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        drainUntil { future.isDone }
        val controller = future.get()
        val manager = ExoPlayerManager(context)
        try {
            drainUntil { field<CompletableDeferred<Unit>>(manager, "initialRestore").isCompleted }
            setField(manager, "mediaController", controller)
            setField(manager, "playlist", songs.toMutableList())
            ExoPlayerManager::class.java.getDeclaredMethod("setupListener").apply { isAccessible = true }.invoke(manager)
            block(player, manager, controller)
        } finally {
            manager.disconnect()
            field<CoroutineScope>(manager, "commandScope").cancel()
            field<CoroutineScope>(manager, "persistenceScope").cancel()
            controller.release()
            session.release()
            player.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "Timed out waiting for playback recovery" }
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target) as T
    private fun setField(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)

    private class FailingQueuePlayer(items: List<MediaItem>, repeatMode: Int, startIndex: Int) : SimpleBasePlayer(Looper.getMainLooper()) {
        val preparedIndices = mutableListOf<Int>()
        var stops = 0
        private var snapshot = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.map(::data)).setCurrentMediaItemIndex(startIndex).setContentPositionMs(0)
            .setPlaybackState(Player.STATE_READY).setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeatMode).build()

        override fun getState(): State = snapshot

        fun fail() {
            snapshot = snapshot.buildUpon().setPlaybackState(Player.STATE_IDLE).setPlayerError(
                PlaybackException("Source error", IOException("HTTP 410 Gone"), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            ).build()
            invalidateState()
        }

        fun ready() {
            snapshot = snapshot.buildUpon().setPlayerError(null).setPlaybackState(Player.STATE_READY).build()
            invalidateState()
        }

        fun advancePosition(positionMs: Long) {
            snapshot = snapshot.buildUpon().setContentPositionMs(positionMs).build()
            invalidateState()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            preparedIndices += snapshot.currentMediaItemIndex
            snapshot = snapshot.buildUpon().setPlayerError(null).setPlaybackState(Player.STATE_BUFFERING).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            stops++
            snapshot = snapshot.buildUpon().setPlaybackState(Player.STATE_IDLE).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            // Match ExoPlayer's fatal-error state: seeking alone cannot restart loading.
            snapshot = snapshot.buildUpon().setCurrentMediaItemIndex(mediaItemIndex).setContentPositionMs(positionMs).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlaylist(items.map(::data)).setCurrentMediaItemIndex(startIndex)
                .setContentPositionMs(startPositionMs).setPlayerError(null).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setRepeatMode(repeatMode).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetShuffleModeEnabled(enabled: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
        private fun data(item: MediaItem): MediaItemData = MediaItemData.Builder(item.mediaId).setMediaItem(item)
            .setDurationUs(180_000_000).build()
    }
}
