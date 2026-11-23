package com.strmr.tv.watchtogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSyncRulesTest {
    private fun room(status: String = WatchRoomStatus.PLAYING, position: Double = 100.0, waiting: Boolean = false) =
        WatchRoom(id = "r", status = status, position = position, waitingForReady = waiting)

    @Test fun pausedUnlessPlayingAndEveryoneReady() {
        assertFalse(WatchSyncRules.remotePaused(room()))
        assertTrue(WatchSyncRules.remotePaused(room(waiting = true)))
        assertTrue(WatchSyncRules.remotePaused(room(WatchRoomStatus.PAUSED)))
        assertTrue(WatchSyncRules.remotePaused(room(WatchRoomStatus.LOBBY)))
    }

    @Test fun seeksOnlyPastOneAndAHalfSeconds() {
        assertFalse(WatchSyncRules.needsSeek(101.4, room()))
        assertTrue(WatchSyncRules.needsSeek(101.6, room()))
        assertTrue(WatchSyncRules.needsSeek(98.0, room()))
    }

    @Test fun hostResumeSeedsOnlyTheFirstSnapshot() {
        assertTrue(WatchSyncRules.seedsHostResume(true, true, 600.0, room(position = 0.0)))
        assertFalse(WatchSyncRules.seedsHostResume(false, true, 600.0, room(position = 0.0)))
        assertFalse(WatchSyncRules.seedsHostResume(true, false, 600.0, room(position = 0.0)))
        assertFalse(WatchSyncRules.seedsHostResume(true, true, 0.0, room(position = 0.0)))
        assertFalse("already there", WatchSyncRules.seedsHostResume(true, true, 601.0, room(position = 600.0)))
    }

    @Test fun detectsPauseTogglesAndSeeksAgainstTheExtrapolatedPosition() {
        val playing = LocalPlayback(paused = false, position = 50.0, atMs = 0)
        assertFalse(WatchSyncRules.localChange(null, true, 0.0, 1_000))
        assertFalse("normal playback", WatchSyncRules.localChange(playing, false, 51.0, 1_000))
        assertFalse("a little drift", WatchSyncRules.localChange(playing, false, 53.0, 1_000))
        assertTrue("paused", WatchSyncRules.localChange(playing, true, 51.0, 1_000))
        assertTrue("seeked ahead", WatchSyncRules.localChange(playing, false, 80.0, 1_000))
        assertTrue("seeked back", WatchSyncRules.localChange(playing, false, 40.0, 1_000))
        val paused = LocalPlayback(paused = true, position = 50.0, atMs = 0)
        assertFalse("still paused", WatchSyncRules.localChange(paused, true, 50.0, 10_000))
        assertTrue("seeked while paused", WatchSyncRules.localChange(paused, true, 60.0, 10_000))
        assertEquals(60.0, WatchSyncRules.expected(playing, 10_000), 0.0)
    }

    @Test fun buffersOnlyWhenThePlayheadIsStuck() {
        assertFalse(WatchSyncRules.sustainedBuffering(true, false, null, 10.0))
        assertFalse(WatchSyncRules.sustainedBuffering(true, false, 10.0, 14.0))
        assertTrue(WatchSyncRules.sustainedBuffering(true, false, 10.0, 10.2))
        assertFalse("paused", WatchSyncRules.sustainedBuffering(true, true, 10.0, 10.0))
        assertFalse(WatchSyncRules.sustainedBuffering(false, false, 10.0, 10.0))
    }
}

class QrMatrixTest {
    @Test fun encodesAGuestLink() {
        val m = com.strmr.tv.ui.watchtogether.QrMatrix.encode("https://ms.example/watch-party/0123456789abcdef0123456789abcdef")!!
        assertTrue(m.size >= 25)
        // The finder pattern's dark corner.
        assertTrue(m[0, 0] && m[m.size - 1, 0] && m[0, m.size - 1])
    }
}
