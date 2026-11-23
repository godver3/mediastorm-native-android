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

    /** The params the server keeps for web guests (handlers/watch_party.go `watchPartySourceAllowedParams`). */
    val SOURCE_PARAMS = setOf(
        "mediaType", "title", "seriesTitle", "displayName", "year", "seasonNumber", "episodeNumber", "episodeName",
        "imdbId", "tvdbId", "tmdbId", "titleId", "headerImage", "posterUrl", "dv", "hdr10", "dvProfile", "forceAAC",
        "preselectedAudioTrack", "preselectedSubtitleTrack",
    )
}
