package com.strmr.tv.watchtogether

import kotlinx.serialization.Serializable

// Watch Together, as the server's models/watch_room.go sends it. Times stay RFC 3339 strings;
// nothing here does date math on them (the server extrapolates `position` itself).

/** What this client tells the room it can do (`WatchRoomClientCapabilities`). */
@Serializable
data class WatchRoomCapabilities(
    val nativePlayback: Boolean = false,
    val stateSync: Boolean = false,
    val protocolVersion: Int = 0,
) {
    companion object {
        /** The native player, synced by state polling: protocol 1, as the Expo app reports. */
        val NATIVE = WatchRoomCapabilities(nativePlayback = true, stateSync = true, protocolVersion = 1)
    }
}

@Serializable
data class WatchRoomMember(
    val profileId: String,
    val name: String = "",
    val isGuest: Boolean = false,
    val color: String? = null,
    val iconUrl: String? = null,
    val clientId: String? = null,
    val isCreator: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
    val joined: Boolean = false,
    val present: Boolean = false,
    val joinedAt: String? = null,
    val lastSeenAt: String? = null,
    val capabilities: WatchRoomCapabilities = WatchRoomCapabilities(),
)

/**
 * The series episode a room plays (`WatchRoomEpisode`): set by the host, followed by everyone
 * else. Absent for movies and for rooms from before episodes were shared.
 */
@Serializable
data class WatchRoomEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeId: String? = null,
    val title: String? = null,
) {
    /** The same episode as [other] (by number: ids differ between catalogs). */
    fun same(other: WatchRoomEpisode?) = other != null && other.seasonNumber == seasonNumber && other.episodeNumber == episodeNumber

    val code: String get() = com.strmr.tv.api.episodeCode(seasonNumber, episodeNumber)
}

/** A room: the title, its shared playback state and its members. */
@Serializable
data class WatchRoom(
    val id: String,
    val creatorProfileId: String = "",
    val creatorName: String = "",
    val title: String = "",
    val mediaType: String = "",
    val itemId: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** The details-route params the room was created with (what reopens the title). */
    val params: Map<String, String> = emptyMap(),
    val status: String = WatchRoomStatus.LOBBY,
    val waitingForReady: Boolean = false,
    /** Seconds, extrapolated by the server to the moment it answered while playing. */
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val revision: Long = 0,
    val updatedBy: String? = null,
    val anchorUpdatedAt: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val endedAt: String? = null,
    val endReason: String? = null,
    val members: List<WatchRoomMember> = emptyList(),
    /** The episode everyone plays (a series room whose host shared it); null otherwise. */
    val episode: WatchRoomEpisode? = null,
) {
    val ended: Boolean get() = status == WatchRoomStatus.ENDED
    val isPlaying: Boolean get() = status == WatchRoomStatus.PLAYING

    fun isHost(profileId: String) = creatorProfileId == profileId
    fun member(profileId: String) = members.firstOrNull { it.profileId == profileId }

    /** Why it ended, in words; null while it runs. */
    val endedMessage: String?
        get() = if (!ended) null else when (endReason) {
            "host_ended" -> "The host ended this room."
            "host_disconnected" -> "The host disconnected."
            "all_left" -> "Everyone left this room."
            "expired" -> "This room expired."
            else -> "This room has ended."
        }
}

object WatchRoomStatus {
    const val LOBBY = "lobby"
    const val PLAYING = "playing"
    const val PAUSED = "paused"
    const val ENDED = "ended"
}

/** Another server account invited to a room (`WatchRoomAccountInvitation`). */
@Serializable
data class WatchRoomAccountInvitation(
    val id: String,
    val roomId: String = "",
    val inviteeUsername: String? = null,
    val acceptedProfileId: String? = null,
    val status: String = "",
    val creatorName: String? = null,
    val title: String? = null,
    val mediaType: String? = null,
    val itemId: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val respondedAt: String? = null,
    val revokedAt: String? = null,
) {
    val isPending: Boolean get() = status.isEmpty() || status == "pending"
}

/** The external guest link: a QR code of [url], the [shortCode] typed at [landingUrl]. */
@Serializable
data class WatchPartyInvitation(
    val url: String,
    val landingUrl: String = "",
    val shortCode: String = "",
    val expiresAt: String? = null,
)
