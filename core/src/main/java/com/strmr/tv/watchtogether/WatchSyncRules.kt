package com.strmr.tv.watchtogether

import kotlin.math.abs

/** The player's last known state, for spotting a local pause toggle or seek. */
data class LocalPlayback(val paused: Boolean, val position: Double, val atMs: Long)

/**
 * The sync loop's decisions (the Expo `useWatchRoomPlaybackSync`), kept free of the player so
 * they can be tested. Positions are seconds.
 */
object WatchSyncRules {
    /** Applying a remote state seeks only when this far off. */
    const val REMOTE_SEEK_THRESHOLD = 1.5
    /** A local position this far from where playback should be counts as a seek. */
    const val LOCAL_SEEK_THRESHOLD = 2.5
    /** Local changes aren't reported this long after applying a remote state (its own echo). */
    const val SUPPRESS_AFTER_REMOTE_MS = 1_500L
    /** The playhead moving less than this between heartbeats while playing counts as stuck. */
    const val MIN_PROGRESS = 0.75

    /**
     * Local changes aren't reported while the app is in the background (a phone pausing itself
     * there isn't the viewer's choice) nor until [suppressUntilMs] (a remote apply's echo, or the
     * moments after coming back while the room's state is re-applied), nor once the player is
     * closing (its stop isn't a pause for the room).
     */
    fun reportsLocal(foreground: Boolean, nowMs: Long, suppressUntilMs: Long, closing: Boolean = false) =
        !closing && foreground && nowMs >= suppressUntilMs

    /**
     * The resume point the host's player seeds into the room: only when the host opens the
     * player for the first time and the room is still at its start. A rejoin plays the room's
     * position as everyone else does (never a stale lobby position or the host's old resume).
     */
    fun hostSeedPosition(isHost: Boolean, firstOpen: Boolean, roomPosition: Double, ownResume: Double): Double =
        if (isHost && firstOpen && roomPosition < 1.0 && ownResume > 0) ownResume else 0.0

    /**
     * The presence strip shows with the player's controls, for [PRESENCE_SHOW_MS] after who's
     * there (or their state) changes, and while the room waits for players or anyone is buffering.
     */
    fun presenceVisible(room: WatchRoom, changedAtMs: Long, nowMs: Long, controlsVisible: Boolean = false): Boolean =
        controlsVisible || room.waitingForReady || room.members.any { it.joined && it.buffering } || nowMs - changedAtMs < PRESENCE_SHOW_MS

    /** What the presence strip shows: a change restarts its timer. */
    fun presenceKey(room: WatchRoom): String =
        room.members.joinToString("|") { "${it.profileId}:${it.joined}:${it.present}:${it.ready}:${it.buffering}" } + "#" + room.status

    const val PRESENCE_SHOW_MS = 8_000L

    /** The room wants the player paused: everyone isn't ready yet, or it isn't playing. */
    fun remotePaused(room: WatchRoom) = room.waitingForReady || room.status != WatchRoomStatus.PLAYING

    fun needsSeek(localPosition: Double, room: WatchRoom) = abs(localPosition - room.position) > REMOTE_SEEK_THRESHOLD

    /**
     * The host opened the player at its own resume point (the first snapshot): seed the room
     * with it when the room is somewhere else.
     */
    fun seedsHostResume(firstSnapshot: Boolean, isHost: Boolean, resumePosition: Double, room: WatchRoom) =
        firstSnapshot && isHost && resumePosition > 0 && abs(room.position - resumePosition) > LOCAL_SEEK_THRESHOLD

    /** Where [previous] says playback should be now: still if paused, else moving in real time. */
    fun expected(previous: LocalPlayback, nowMs: Long): Double =
        if (previous.paused) previous.position else previous.position + (nowMs - previous.atMs) / 1000.0

    /** A pause toggle, or a jump of more than [LOCAL_SEEK_THRESHOLD] from [expected]. */
    fun localChange(previous: LocalPlayback?, paused: Boolean, position: Double, nowMs: Long): Boolean {
        previous ?: return false
        return paused != previous.paused || abs(position - expected(previous, nowMs)) > LOCAL_SEEK_THRESHOLD
    }

    /**
     * Buffering is reported only when it lasts: the player says so, it should be playing, and
     * the playhead didn't advance over the whole interval since the last heartbeat.
     */
    fun sustainedBuffering(rawBuffering: Boolean, paused: Boolean, previousPosition: Double?, position: Double): Boolean {
        if (!rawBuffering || paused || previousPosition == null) return false
        return position - previousPosition < MIN_PROGRESS
    }

    // Episodes: the host's episode is the room's; everyone else follows it.

    /**
     * The host plays an episode the room doesn't name (it advanced, or the room predates
     * episodes): post it, which has the server wait for everyone to load it.
     */
    fun hostAnnouncesEpisode(isHost: Boolean, room: WatchRoom, playing: WatchRoomEpisode?): Boolean =
        isHost && playing != null && !playing.same(room.episode)

    /**
     * The episode a guest switches to: the room names one and it isn't the one playing. Null
     * for the host, a room without an episode (as before) and the same episode.
     */
    fun guestSwitchTarget(isHost: Boolean, room: WatchRoom, playing: WatchRoomEpisode?): WatchRoomEpisode? {
        val target = room.episode ?: return null
        return if (isHost || target.same(playing)) null else target
    }

    /** Up Next (and next-episode autoplay) in a room: the host's only; guests follow it. */
    fun upNextAllowed(inRoom: Boolean, isHost: Boolean) = !inRoom || isHost

    /**
     * Where the host's announced episode starts: its own seeded resume point when it is the
     * episode the lobby opened, else where the player is (an advance: the start).
     */
    fun hostEpisodePosition(playing: WatchRoomEpisode, launchEpisode: WatchRoomEpisode?, seed: Double, position: Double): Double =
        if (seed > 0 && playing.same(launchEpisode)) seed else position

    /** The params the server keeps for web guests (handlers/watch_party.go `watchPartySourceAllowedParams`). */
    val SOURCE_PARAMS = setOf(
        "mediaType", "title", "seriesTitle", "displayName", "year", "seasonNumber", "episodeNumber", "episodeName",
        "imdbId", "tvdbId", "tmdbId", "titleId", "headerImage", "posterUrl", "dv", "hdr10", "dvProfile", "forceAAC",
        "preselectedAudioTrack", "preselectedSubtitleTrack",
    )
}
