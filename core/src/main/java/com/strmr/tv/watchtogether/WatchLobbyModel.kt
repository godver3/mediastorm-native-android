package com.strmr.tv.watchtogether

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.StartPoint
import com.strmr.tv.app.AppLog
import com.strmr.tv.player.PlaybackCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A room's lobby (the Expo `watch-room` screen), shared by the TV and phone pages: polls the
 * room every 3 s with a heartbeat, joins it, and once the host starts opens the player for
 * everyone. The host also sees pending account invitations (every 5 s) and the external guest
 * link. [accountInviteId]: opened from an invitation to this account, which is accepted (with
 * the current profile) or declined before anything else. Main thread.
 */
class WatchLobbyModel(
    val roomId: String,
    private val accountInviteId: String?,
    private val scope: CoroutineScope,
) {
    val profileId: String = ApiClient.profileId

    var room by mutableStateOf<WatchRoom?>(null)
        private set
    /** The account invitation waiting on Accept / Decline; null once answered (or none). */
    var accountInvitation by mutableStateOf<WatchRoomAccountInvitation?>(null)
        private set
    var pendingInvites by mutableStateOf<List<WatchRoomAccountInvitation>>(emptyList())
        private set
    var external by mutableStateOf<WatchPartyInvitation?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(true)
        private set
    /** The page should close (left, ended by this host, or an invitation declined). */
    var closed by mutableStateOf(false)
        private set
    /** This device already opened the player for the room (returning from it doesn't reopen it). */
    var playbackOpened by mutableStateOf(false)
        private set

    /** This lobby opened the player before: a rejoin never seeds the host's resume point again. */
    private var openedBefore = false
    /** The room's episode when this lobby last opened the player: a new one from the host reopens it. */
    private var openedEpisode: WatchRoomEpisode? = null
    private var accepted = accountInviteId == null
    private var joined = false
    private var terminal = false

    val isHost: Boolean get() = room?.isHost(profileId) == true
    val me: WatchRoomMember? get() = room?.member(profileId)
    val ended: Boolean get() = room?.ended == true

    /** The 3 s lobby poll (and the 5 s pending-invite refresh for the host); cancel to stop. */
    suspend fun run() {
        if (accountInviteId != null) loadAccountInvitation()
        scope.launch { pollPendingInvites() }
        while (true) {
            // The player syncs and heartbeats on its own while it is up.
            if (!PlaybackCoordinator.playerOpen) refresh()
            delay(POLL_MS)
        }
    }

    private suspend fun loadAccountInvitation() {
        try {
            val invite = ApiClient.myAccountInvitations().firstOrNull { it.id == accountInviteId }
            accountInvitation = invite
            if (invite == null) {
                // Answered already (on another device): it may be ours to join.
                accepted = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this invitation."
        } finally {
            busy = false
        }
    }

    suspend fun refresh() {
        if (!accepted || terminal) return
        try {
            var next = ApiClient.watchRoom(profileId, roomId)
            if (next.ended) {
                terminal = true
                WatchTogetherSession.forget(profileId, roomId)
            } else if (!joined) {
                // Idempotent; refreshes this device's client id and capabilities.
                next = ApiClient.joinWatchRoom(profileId, roomId)
            }
            joined = !next.ended
            room = next
            error = null
            if (!next.ended) {
                WatchTogetherSession.remember(profileId, next)
                try {
                    ApiClient.heartbeatWatchRoom(profileId, roomId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isWatchRoomEnded) markEnded()
                }
                // A guest back here (its episode ended, say) follows the host's next episode.
                val newEpisode = next.episode != null && !next.episode.same(openedEpisode) && !next.isHost(profileId)
                if (next.isPlaying && (!playbackOpened || newEpisode) && !PlaybackCoordinator.playerOpen) openPlayback()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isWatchRoomGone) {
                markEnded()
                error = if (e.isWatchRoomEnded) null else "This watch room is no longer available."
            } else {
                error = e.message ?: "Couldn't reach the watch room."
            }
        } finally {
            busy = false
        }
    }

    private fun markEnded() {
        terminal = true
        WatchTogetherSession.forget(profileId, roomId)
        room = room?.copy(status = WatchRoomStatus.ENDED) ?: room
    }

    private suspend fun pollPendingInvites() {
        while (true) {
            if (isHost && !ended) {
                runCatching { ApiClient.roomAccountInvitations(profileId, roomId) }.getOrNull()?.let { list ->
                    pendingInvites = list.filter { it.isPending }
                }
            }
            delay(INVITES_POLL_MS)
        }
    }

    private fun act(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(LOG, "$label failed: ${e.message}")
                error = e.message ?: "Couldn't $label."
            } finally {
                busy = false
            }
        }
    }

    fun acceptInvitation() = act("accept the invitation") {
        val invite = accountInvitation ?: return@act
        val accepted = ApiClient.acceptAccountInvitation(profileId, invite.id)
        this.accepted = true
        accountInvitation = null
        WatchInvitations.dropAccountInvitation(invite.id)
        room = ApiClient.joinWatchRoom(profileId, accepted.id)
        joined = true
        room?.let { WatchTogetherSession.remember(profileId, it) }
    }

    fun declineInvitation() = act("decline the invitation") {
        val invite = accountInvitation ?: return@act
        ApiClient.declineAccountInvitation(invite.id)
        WatchInvitations.dropAccountInvitation(invite.id)
        closed = true
    }

    fun toggleReady() = act("change ready") {
        val ready = me?.ready != true
        room = ApiClient.setWatchRoomReady(profileId, roomId, ready)
    }

    /** Host: starts the room at its position; every lobby (this one included) then opens the player. */
    fun start() = act("start the room") {
        val current = room ?: return@act
        room = ApiClient.updateWatchRoomState(profileId, roomId, WatchRoomStatus.PLAYING, current.position)
        if (room?.isPlaying == true && !playbackOpened) openPlayback()
    }

    /** Back in the lobby while the room plays: opens the player again. */
    fun rejoinPlayback() {
        if (room?.ended == false) openPlayback()
    }

    /** Host Back ends the room; anyone else leaves it. */
    fun exit() = act(if (isHost) "end the room" else "leave the room") {
        if (room?.ended != true && accepted) {
            if (isHost) ApiClient.endWatchRoom(profileId, roomId) else ApiClient.leaveWatchRoom(profileId, roomId)
        }
        WatchTogetherSession.forget(profileId, roomId)
        WatchInvitations.dropRoom(roomId)
        terminal = true
        closed = true
    }

    fun inviteAccount(username: String) = act("invite ${username.trim()}") {
        if (username.isBlank()) return@act
        val invite = ApiClient.inviteAccountToWatchRoom(profileId, roomId, username)
        pendingInvites = pendingInvites.filterNot { it.id == invite.id } + invite
    }

    fun revokeInvite(id: String) = act("revoke the invitation") {
        ApiClient.revokeAccountInvitation(profileId, roomId, id)
        pendingInvites = pendingInvites.filterNot { it.id == id }
    }

    fun createExternal() = act("create a guest link") {
        external = ApiClient.createExternalInvitation(profileId, roomId)
    }

    fun revokeExternal() = act("turn off the guest link") {
        ApiClient.revokeExternalInvitation(profileId, roomId)
        external = null
    }

    /**
     * Opens the player on the room's title: its details are loaded for what Play would play (a
     * series' up-next episode, a library copy), or the room's episode when the host shared one,
     * started at the room's position. The host starts at its own resume point when the room is
     * at the start, and seeds the room with it ([WatchRoomSync]).
     */
    private fun openPlayback() {
        val current = room ?: return
        playbackOpened = true
        openedEpisode = current.episode
        scope.launch {
            try {
                val prepared = WatchRoomPlayback.prepare(current)
                val host = current.isHost(profileId)
                val seed = WatchSyncRules.hostSeedPosition(host, firstOpen = !openedBefore, roomPosition = current.position, ownResume = prepared.ownResume)
                openedBefore = true
                val start = when {
                    seed > 0 -> StartPoint.Seconds(seed)
                    current.position >= 1.0 -> StartPoint.Seconds(current.position)
                    else -> null
                }
                val request = prepared.request(start)
                WatchTogetherSession.launch = WatchRoomLaunch(
                    roomId, profileId, current.itemId, isHost = host,
                    resumePosition = seed, episode = WatchRoomTitle.episode(request.episode),
                )
                AppLog.i(LOG, "opening playback for room $roomId ${request.episode?.code ?: ""} at ${(start as? StartPoint.Seconds)?.value ?: 0.0}s (host=$host)")
                PlaybackCoordinator.play(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not retried by the poll: Rejoin Playback tries again.
                error = e.message ?: "Couldn't start playback."
            }
        }
    }

    companion object {
        const val POLL_MS = 3_000L
        const val INVITES_POLL_MS = 5_000L
        private const val LOG = "WatchTogether"
    }
}
