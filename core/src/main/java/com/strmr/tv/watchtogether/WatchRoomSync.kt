package com.strmr.tv.watchtogether

import androidx.media3.common.Player
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.app.AppLog
import com.strmr.tv.player.PlayerController
import com.strmr.tv.update.AppForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps one playback in step with its Watch Together room (the Expo `useWatchRoomPlaybackSync`):
 * - polls the room every 2 s and applies each new revision (seek when more than 1.5 s off,
 *   then play or pause), ignoring snapshots for another title (an episode change in progress);
 * - reports ready once the player has loaded while the room waits for everyone, and stays paused;
 * - checks the player every 1 s for a local pause toggle or seek and posts it at once with the
 *   revision it was based on (409: someone else got there first, taken from the next poll);
 * - heartbeats every other poll (4 s, under the server's 15 s presence window), buffering only
 *   when the playhead was stuck for the whole interval;
 * - the host seeds its resume point into the room and hands its resolved stream to web guests.
 * Nothing is sent while the app is in the background. Live TV and trailers never sync.
 * Main thread (the controller's scope).
 */
class WatchRoomSync private constructor(private val c: PlayerController, private val launch: WatchRoomLaunch) {
    private val profileId = launch.profileId
    private val roomId = launch.roomId

    private val _room = MutableStateFlow<WatchRoom?>(null)
    /** The room's latest snapshot for this title, for the presence and preparing overlays. */
    val room: StateFlow<WatchRoom?> = _room
    private val _ended = MutableStateFlow(false)
    /** The room ended: the player closes, back to the lobby. */
    val ended: StateFlow<Boolean> = _ended

    private var lastRevision = -1L
    private var suppressUntil = 0L
    private var local: LocalPlayback? = null
    private var readinessReported = false
    private var waitingForReady = false
    private var ticks = 0
    private var heartbeatPosition: Double? = null
    private var sourceBound = false
    /** The app was in the background: on return the room's state is applied again, whatever its revision. */
    private var wasBackground = false
    private val jobs = mutableListOf<Job>()
    /** The player is closing: nothing more is reported. */
    @Volatile private var closing = false

    private val player get() = c.player
    private val position: Double get() = player.currentPosition.coerceAtLeast(0) / 1000.0
    private val duration: Double get() = c.duration.value.coerceAtLeast(0) / 1000.0
    private val paused: Boolean get() = !player.playWhenReady
    private val loaded: Boolean get() = player.playbackState == Player.STATE_READY || c.duration.value > 0
    private val active: Boolean get() = AppForeground.visible && !_ended.value

    private fun start() {
        AppLog.i(LOG, "syncing ${c.request.title.titleId} with room $roomId (host=${launch.isHost})")
        jobs += c.scope.launch {
            while (isActive && !_ended.value) {
                if (!AppForeground.visible) {
                    wasBackground = true
                } else if (wasBackground) {
                    // Back from the background: anything the player did there (a phone pauses
                    // itself) isn't reported; the room's current state is applied instead.
                    wasBackground = false
                    suppressUntil = System.currentTimeMillis() + WatchSyncRules.SUPPRESS_AFTER_REMOTE_MS
                    lastRevision = Long.MIN_VALUE
                    AppLog.i(LOG, "back in the foreground: re-applying the room's state")
                }
                if (active) poll()
                delay(POLL_MS)
            }
        }
        jobs += c.scope.launch {
            while (isActive && !_ended.value) {
                delay(LOCAL_MS)
                if (active) checkLocal()
            }
        }
    }

    private suspend fun poll() {
        try {
            var room = ApiClient.watchRoom(profileId, roomId)
            if (room.ended) return end(room)
            if (room.waitingForReady && !readinessReported && loaded) {
                room = ApiClient.setWatchRoomReady(profileId, roomId, true)
                readinessReported = true
                AppLog.i(LOG, "player ready in room $roomId")
            }
            waitingForReady = room.waitingForReady
            if (!WatchRoomTitle.matches(room.itemId, c.request.title.titleId)) {
                _room.value = null
                return
            }
            if (closing) return
            val first = lastRevision == -1L
            if (WatchSyncRules.seedsHostResume(first, launch.isHost, launch.resumePosition, room)) {
                AppLog.i(LOG, "seeding host resume ${launch.resumePosition}s (room at ${room.position}s)")
                room = ApiClient.updateWatchRoomState(
                    profileId, roomId, if (room.status == WatchRoomStatus.PAUSED) WatchRoomStatus.PAUSED else WatchRoomStatus.PLAYING,
                    launch.resumePosition, maxOf(room.duration, duration), room.revision,
                )
            }
            _room.value = room
            if (room.revision != lastRevision) {
                lastRevision = room.revision
                apply(room)
            }
            bindSource()
            if (++ticks % HEARTBEAT_EVERY == 0) heartbeat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isWatchRoomGone) end(null) else AppLog.w(LOG, "poll failed: ${e.message}")
        }
    }

    /** The room's state onto the player; its echo isn't reported back. */
    private fun apply(room: WatchRoom) {
        suppressUntil = System.currentTimeMillis() + WatchSyncRules.SUPPRESS_AFTER_REMOTE_MS
        val pause = WatchSyncRules.remotePaused(room)
        if (WatchSyncRules.needsSeek(position, room)) player.seekTo((room.position * 1000).toLong())
        if (player.playWhenReady == pause) player.playWhenReady = !pause
        local = LocalPlayback(pause, room.position, System.currentTimeMillis())
    }

    private fun checkLocal() {
        val now = System.currentTimeMillis()
        if (!AppForeground.visible) wasBackground = true
        if (wasBackground || lastRevision < 0 || !WatchSyncRules.reportsLocal(AppForeground.visible, now, suppressUntil, closing)) {
            // Keep the snapshot current, so nothing done meanwhile reads as a change later.
            local = LocalPlayback(paused, position, now)
            return
        }
        val isPaused = paused
        val at = position
        val changed = WatchSyncRules.localChange(local, isPaused, at, now)
        local = LocalPlayback(isPaused, at, now)
        if (!changed) return
        // Everyone isn't ready yet: the room keeps the player paused (a start that went ahead
        // after a display-mode switch, say), and that isn't news for the room.
        if (waitingForReady && !isPaused) {
            player.playWhenReady = false
            local = LocalPlayback(true, at, now)
            return
        }
        c.scope.launch {
            try {
                val room = ApiClient.updateWatchRoomState(
                    profileId, roomId, if (isPaused) WatchRoomStatus.PAUSED else WatchRoomStatus.PLAYING, at, duration, lastRevision,
                )
                lastRevision = room.revision
                _room.value = room
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    e.isRevisionConflict -> Unit
                    e.isWatchRoomGone -> end(null)
                    else -> AppLog.w(LOG, "state update failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun heartbeat() {
        val at = position
        val buffering = WatchSyncRules.sustainedBuffering(
            player.playbackState == Player.STATE_BUFFERING, paused, heartbeatPosition, at,
        )
        heartbeatPosition = at
        try {
            ApiClient.heartbeatWatchRoom(profileId, roomId, buffering)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isWatchRoomEnded) end(null) else AppLog.w(LOG, "heartbeat failed: ${e.message}")
        }
    }

    /**
     * Host: once the stream is resolved, hands it to the room so web guests play the same file.
     * Once; a 4xx (another source bound already) isn't retried, a network or server error is.
     */
    private suspend fun bindSource() {
        if (sourceBound || !launch.isHost) return
        val source = c.session.streamPath?.takeIf { !c.session.localFile } ?: return
        sourceBound = true
        try {
            ApiClient.bindWatchPartySource(profileId, roomId, source, sourceParams())
            AppLog.i(LOG, "bound the host's stream for web guests")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? ApiError)?.statusCode
            if (status == null || status >= 500) sourceBound = false
            AppLog.w(LOG, "external source bind failed: ${e.message}")
        }
    }

    private fun sourceParams(): Map<String, String> = buildMap {
        val r = c.request
        val t = r.title
        put("mediaType", if (r.isEpisode) "episode" else "movie")
        put("title", t.name)
        put("titleId", t.titleId)
        put("displayName", r.displayTitle)
        t.year?.let { put("year", it.toString()) }
        t.imdbId?.let { put("imdbId", it) }
        t.tmdbId?.let { put("tmdbId", it) }
        t.tvdbId?.let { put("tvdbId", it) }
        t.posterUrl?.takeIf { it.startsWith("http") }?.let { put("posterUrl", it) }
        (r.backdrops.firstOrNull() ?: t.backdropUrl)?.takeIf { it.startsWith("http") }?.let { put("headerImage", it) }
        r.episode?.let { ep ->
            put("seriesTitle", t.name)
            put("seasonNumber", ep.season.toString())
            put("episodeNumber", ep.episode.toString())
            ep.name?.let { put("episodeName", it) }
        }
        if (c.session.sourceDolbyVision != null) put("dv", "true")
        if (c.session.sourceHdr10) put("hdr10", "true")
        c.session.tracks?.audio?.streamIndex?.let { put("preselectedAudioTrack", it.toString()) }
        c.session.tracks?.subtitle?.streamIndex?.let { put("preselectedSubtitleTrack", it.toString()) }
    }.filterKeys { it in WatchSyncRules.SOURCE_PARAMS }

    private fun end(room: WatchRoom?) {
        if (_ended.value) return
        AppLog.i(LOG, "room $roomId ended (${room?.endReason ?: "gone"})")
        WatchTogetherSession.forget(profileId, roomId)
        _room.value = null
        _ended.value = true
    }

    /** The player closed: stop, and withdraw ready if the room was still waiting on it. */
    fun stop() {
        closing = true
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (waitingForReady && readinessReported && !_ended.value) {
            kotlinx.coroutines.MainScope().launch { runCatching { ApiClient.setWatchRoomReady(profileId, roomId, false) } }
        }
    }

    companion object {
        private const val LOG = "WatchTogether"
        const val POLL_MS = 2_000L
        const val LOCAL_MS = 1_000L
        /** Heartbeat on every other poll: 4 s. */
        const val HEARTBEAT_EVERY = 2

        /**
         * The sync for [controller]'s playback, when it is the room's title opened from a lobby
         * ([WatchTogetherSession.launch]); null otherwise (live TV, trailers, anything else).
         */
        fun attach(controller: PlayerController): WatchRoomSync? {
            val launch = launchFor(controller.session) ?: return null
            return WatchRoomSync(controller, launch).also { it.start() }
        }

        /** The room [session] syncs with, if any (Up Next stays off for it: a room covers one title). */
        fun launchFor(session: com.strmr.tv.player.PlayerSession): WatchRoomLaunch? {
            val launch = WatchTogetherSession.launch ?: return null
            if (session.isLive || !session.reportsProgress || launch.profileId != ApiClient.profileId) return null
            if (!WatchRoomTitle.matches(launch.itemId, session.request.title.titleId)) return null
            return launch
        }
    }
}
