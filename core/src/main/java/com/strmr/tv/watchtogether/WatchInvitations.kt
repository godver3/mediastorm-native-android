package com.strmr.tv.watchtogether

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.AppLog
import com.strmr.tv.home.HomeCard
import com.strmr.tv.home.HomeLink
import com.strmr.tv.update.AppForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * What the Home `watch-together` shelf shows: rooms this profile was invited to, invitations
 * to this account from other accounts, and the room this profile is in. Polled every 15 s
 * while Home is on screen and the app is in the foreground ([poll]). Main thread.
 */
object WatchInvitations {
    const val POLL_MS = 15_000L

    var rooms by mutableStateOf<List<WatchRoom>>(emptyList())
        private set
    var accountInvitations by mutableStateOf<List<WatchRoomAccountInvitation>>(emptyList())
        private set
    /** The active room, when it isn't one of [rooms] (this profile is its host, say). */
    var activeRoom by mutableStateOf<WatchRoom?>(null)
        private set
    /** Bumped when what the shelf shows changes, so Home reloads the row. */
    var version by mutableIntStateOf(0)
        private set
    private var loadedFor: String? = null

    val loaded: Boolean get() = loadedFor == ApiClient.profileId

    /** Fetches all three; a failed part keeps what it had (a network blip doesn't empty the row). */
    suspend fun refresh() {
        val profileId = ApiClient.profileId
        if (loadedFor != profileId) {
            rooms = emptyList(); accountInvitations = emptyList(); activeRoom = null
        }
        coroutineScope {
            val r = async { runCatching { ApiClient.watchRoomInvitations(profileId) } }
            val a = async { runCatching { ApiClient.myAccountInvitations() } }
            val active = WatchTogetherSession.active.value
            val act = async {
                active?.let { saved -> runCatching { ApiClient.watchRoom(profileId, saved.roomId) } }
            }
            if (profileId != ApiClient.profileId) return@coroutineScope
            val newRooms = r.await().getOrNull()?.filter { !it.ended } ?: rooms
            val newInvites = a.await().getOrNull()?.filter { it.isPending } ?: accountInvitations
            val activeResult = act.await()
            val newActive = when {
                active == null -> null
                activeResult == null -> activeRoom
                activeResult.isSuccess -> activeResult.getOrNull()?.takeIf { !it.ended && newRooms.none { r -> r.id == it.id } }
                activeResult.exceptionOrNull()?.isWatchRoomGone == true -> {
                    WatchTogetherSession.forget(profileId, active.roomId)
                    null
                }
                else -> activeRoom
            }
            r.await().exceptionOrNull()?.let { if (it !is CancellationException) AppLog.w("WatchTogether", "invitations: ${it.message}") }
            val changed = shelfKey(newRooms, newInvites, newActive) != shelfKey(rooms, accountInvitations, activeRoom)
            rooms = newRooms
            accountInvitations = newInvites
            activeRoom = newActive
            loadedFor = profileId
            if (changed) version++
        }
    }

    /** Polls while [visible] (Home on screen) and the app is in the foreground; cancel to stop. */
    suspend fun poll(visible: () -> Boolean) {
        while (true) {
            if (visible() && AppForeground.visible) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("WatchTogether", "invitation poll failed: ${e.message}")
                }
            }
            delay(POLL_MS)
        }
    }

    /** A declined or accepted invitation leaves the row at once. */
    fun dropAccountInvitation(id: String) {
        accountInvitations = accountInvitations.filterNot { it.id == id }
        version++
    }

    fun dropRoom(id: String) {
        rooms = rooms.filterNot { it.id == id }
        if (activeRoom?.id == id) activeRoom = null
        version++
    }

    /** The shelf's cards: the room this profile is in first, then account and profile invitations. */
    fun cards(): List<HomeCard> = buildList {
        activeRoom?.let { add(card(it, "Return to your room", null)) }
        accountInvitations.forEach { invite ->
            add(
                HomeCard(
                    id = "watch-invite-${invite.id}",
                    link = HomeLink.WatchRoom(invite.roomId, accountInviteId = invite.id),
                    title = invite.title ?: "Watch Together",
                    subtitle = "${invite.creatorName ?: "Someone"} invited your account",
                    overview = "Open to accept with this profile, or decline.",
                    backdropUrl = invite.backdropUrl ?: invite.posterUrl,
                    posterUrl = invite.posterUrl,
                ),
            )
        }
        rooms.forEach { room ->
            val label = if (room.creatorProfileId == ApiClient.profileId) "Your room" else "${room.creatorName.ifEmpty { "Someone" }} invited you"
            add(card(room, label, null))
        }
    }

    private fun card(room: WatchRoom, label: String, inviteId: String?): HomeCard {
        val joined = room.members.count { it.joined }
        val status = when (room.status) {
            WatchRoomStatus.LOBBY -> "In the lobby"
            WatchRoomStatus.PLAYING -> "Playing"
            WatchRoomStatus.PAUSED -> "Paused"
            else -> room.status
        }
        return HomeCard(
            id = "watch-room-${room.id}",
            link = HomeLink.WatchRoom(room.id, inviteId),
            title = room.title,
            subtitle = "$label · $status",
            overview = "$joined of ${room.members.size} joined: " + room.members.joinToString { it.name },
            backdropUrl = room.backdropUrl ?: room.posterUrl,
            posterUrl = room.posterUrl,
        )
    }

    private fun shelfKey(rooms: List<WatchRoom>, invites: List<WatchRoomAccountInvitation>, active: WatchRoom?) =
        (listOfNotNull(active) + rooms).joinToString("|") { r ->
            "${r.id}:${r.status}:${r.title}:${r.members.joinToString(",") { "${it.profileId}${it.joined}${it.present}" }}"
        } + "#" + invites.joinToString("|") { "${it.id}:${it.status}" }
}
