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

    @Test fun noLocalReportsInTheBackgroundOrJustAfter() {
        assertFalse("backgrounded", WatchSyncRules.reportsLocal(foreground = false, nowMs = 10_000, suppressUntilMs = 0))
        // Back in the foreground at 10 s: suppressed for 1.5 s while the room's state is re-applied.
        val until = 10_000 + WatchSyncRules.SUPPRESS_AFTER_REMOTE_MS
        assertFalse(WatchSyncRules.reportsLocal(true, 10_500, until))
        assertTrue(WatchSyncRules.reportsLocal(true, 11_500, until))
    }

    @Test fun closingIsNeverReported() {
        assertFalse(WatchSyncRules.reportsLocal(foreground = true, nowMs = 10_000, suppressUntilMs = 0, closing = true))
        assertTrue(WatchSyncRules.reportsLocal(foreground = true, nowMs = 10_000, suppressUntilMs = 0, closing = false))
    }

    @Test fun hostSeedsItsResumeOnlyOnTheFirstStart() {
        assertEquals(600.0, WatchSyncRules.hostSeedPosition(true, firstOpen = true, roomPosition = 0.0, ownResume = 600.0), 0.0)
        assertEquals("rejoin", 0.0, WatchSyncRules.hostSeedPosition(true, firstOpen = false, roomPosition = 0.0, ownResume = 600.0), 0.0)
        assertEquals("room already moved", 0.0, WatchSyncRules.hostSeedPosition(true, true, roomPosition = 900.0, ownResume = 600.0), 0.0)
        assertEquals("guest", 0.0, WatchSyncRules.hostSeedPosition(false, true, 0.0, 600.0), 0.0)
    }

    @Test fun presenceShowsAfterChangesAndWhileWaitingOrBuffering() {
        val m = WatchRoomMember("a", joined = true, present = true)
        val calm = WatchRoom(id = "r", status = WatchRoomStatus.PLAYING, members = listOf(m))
        assertTrue(WatchSyncRules.presenceVisible(calm, changedAtMs = 0, nowMs = 7_000))
        assertFalse(WatchSyncRules.presenceVisible(calm, 0, 9_000))
        assertTrue(WatchSyncRules.presenceVisible(calm.copy(waitingForReady = true), 0, 60_000))
        assertTrue(WatchSyncRules.presenceVisible(calm.copy(members = listOf(m.copy(buffering = true))), 0, 60_000))
        assertFalse(WatchSyncRules.presenceKey(calm) == WatchSyncRules.presenceKey(calm.copy(members = listOf(m.copy(ready = true)))))
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

    @Test fun hostAdvancePostsTheNewEpisode() {
        val e1 = WatchRoomEpisode(1, 1)
        val e2 = WatchRoomEpisode(1, 2, "id-2")
        val r = WatchRoom(id = "r", status = WatchRoomStatus.PLAYING).copy(episode = e1)
        assertTrue(WatchSyncRules.hostAnnouncesEpisode(isHost = true, room = r, playing = e2))
        assertFalse(WatchSyncRules.hostAnnouncesEpisode(isHost = true, room = r, playing = WatchRoomEpisode(1, 1, "other-id")))
        assertFalse(WatchSyncRules.hostAnnouncesEpisode(isHost = false, room = r, playing = e2))
        assertFalse(WatchSyncRules.hostAnnouncesEpisode(isHost = true, room = r, playing = null))
        // A room from before episodes: the host names its episode.
        assertTrue(WatchSyncRules.hostAnnouncesEpisode(isHost = true, room = WatchRoom(id = "r", status = WatchRoomStatus.PLAYING), playing = e1))
        // The launch's episode keeps the seeded resume; an advance posts where the player is.
        assertEquals(600.0, WatchSyncRules.hostEpisodePosition(e1, e1, 600.0, 3.0), 0.0)
        assertEquals(0.0, WatchSyncRules.hostEpisodePosition(e2, e1, 600.0, 0.0), 0.0)
    }

    @Test fun guestSwitchesToTheHostsEpisode() {
        val r = WatchRoom(id = "r", status = WatchRoomStatus.PLAYING).copy(episode = WatchRoomEpisode(2, 5))
        assertEquals(WatchRoomEpisode(2, 5), WatchSyncRules.guestSwitchTarget(false, r, WatchRoomEpisode(2, 4)))
        assertEquals(WatchRoomEpisode(2, 5), WatchSyncRules.guestSwitchTarget(false, r, null))
        assertEquals(null, WatchSyncRules.guestSwitchTarget(true, r, WatchRoomEpisode(2, 4)))
    }

    @Test fun sameEpisodeDoesNotSwitch() {
        val r = WatchRoom(id = "r", status = WatchRoomStatus.PLAYING).copy(episode = WatchRoomEpisode(2, 5, "a"))
        assertEquals(null, WatchSyncRules.guestSwitchTarget(false, r, WatchRoomEpisode(2, 5, "b")))
    }

    @Test fun noRoomEpisodeKeepsTheLegacyBehaviour() {
        assertEquals(null, WatchSyncRules.guestSwitchTarget(false, WatchRoom(id = "r", status = WatchRoomStatus.PLAYING), WatchRoomEpisode(1, 1)))
        assertFalse(WatchSyncRules.hostAnnouncesEpisode(isHost = false, room = WatchRoom(id = "r", status = WatchRoomStatus.PLAYING), playing = WatchRoomEpisode(1, 1)))
        assertTrue(WatchSyncRules.upNextAllowed(inRoom = false, isHost = false))
        assertTrue(WatchSyncRules.upNextAllowed(inRoom = true, isHost = true))
        assertFalse(WatchSyncRules.upNextAllowed(inRoom = true, isHost = false))
    }
}
