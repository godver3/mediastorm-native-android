package com.strmr.tv.watchtogether

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.app.Config
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The Watch Together endpoints (backend handlers/watch_rooms.go and watch_party.go). Room calls
// are profile scoped (`/api/users/{profile}/watch-rooms/…`); background ones pass
// `promptOnLock = false` so a poll never raises a PIN prompt.

private fun ApiClient.rooms(profileId: String) = "/api/users/${segment(profileId)}/watch-rooms"
private fun ApiClient.room(profileId: String, roomId: String) = "${rooms(profileId)}/${segment(roomId)}"

private fun nativeCapabilities() = ApiClient.json.encodeToJsonElement(WatchRoomCapabilities.NATIVE)

/** `POST watch-rooms`: the server rejects unknown fields, so the body is exactly its `WatchRoomCreate`. */
suspend fun ApiClient.createWatchRoom(profileId: String, create: WatchRoomCreate): WatchRoom =
    send("POST", rooms(profileId), body = create.body())

suspend fun ApiClient.watchRoom(profileId: String, roomId: String, promptOnLock: Boolean = false): WatchRoom =
    send("GET", room(profileId, roomId), promptOnLock = promptOnLock, timeoutMs = 15_000)

suspend fun ApiClient.joinWatchRoom(profileId: String, roomId: String): WatchRoom =
    send("POST", "${room(profileId, roomId)}/join", body = buildJsonObject {
        put("clientId", Config.clientId)
        put("capabilities", nativeCapabilities())
    })

suspend fun ApiClient.setWatchRoomReady(profileId: String, roomId: String, ready: Boolean): WatchRoom =
    send("POST", "${room(profileId, roomId)}/ready", body = buildJsonObject { put("ready", ready) }, promptOnLock = false)

/** `POST state`; a stale [expectedRevision] answers 409 ([isRevisionConflict]). */
suspend fun ApiClient.updateWatchRoomState(
    profileId: String, roomId: String, status: String, position: Double, duration: Double? = null, expectedRevision: Long? = null,
    episode: WatchRoomEpisode? = null,
): WatchRoom = send("POST", "${room(profileId, roomId)}/state", promptOnLock = false, timeoutMs = 15_000, body = buildJsonObject {
    put("status", status)
    put("position", position)
    duration?.takeIf { it > 0 }?.let { put("duration", it) }
    expectedRevision?.takeIf { it >= 0 }?.let { put("expectedRevision", it) }
    episode?.let { put("episode", it.body()) }
})

suspend fun ApiClient.heartbeatWatchRoom(profileId: String, roomId: String, buffering: Boolean = false) {
    sendRaw("POST", "${room(profileId, roomId)}/heartbeat", promptOnLock = false, timeoutMs = 15_000, body = buildJsonObject {
        put("clientId", Config.clientId)
        put("buffering", buffering)
    })
}

suspend fun ApiClient.leaveWatchRoom(profileId: String, roomId: String) {
    sendRaw("POST", "${room(profileId, roomId)}/leave")
}

suspend fun ApiClient.endWatchRoom(profileId: String, roomId: String) {
    sendRaw("POST", "${room(profileId, roomId)}/end")
}

/** Rooms this profile was invited to (`GET watch-rooms/invitations`). */
suspend fun ApiClient.watchRoomInvitations(profileId: String): List<WatchRoom> =
    sendList("GET", "${rooms(profileId)}/invitations", promptOnLock = false, timeoutMs = 15_000)

suspend fun ApiClient.inviteAccountToWatchRoom(profileId: String, roomId: String, username: String): WatchRoomAccountInvitation =
    send("POST", "${room(profileId, roomId)}/account-invites", body = buildJsonObject { put("username", username.trim()) })

suspend fun ApiClient.roomAccountInvitations(profileId: String, roomId: String): List<WatchRoomAccountInvitation> =
    sendList("GET", "${room(profileId, roomId)}/account-invites", promptOnLock = false, timeoutMs = 15_000)

suspend fun ApiClient.revokeAccountInvitation(profileId: String, roomId: String, inviteId: String) {
    sendRaw("DELETE", "${room(profileId, roomId)}/account-invites/${segment(inviteId)}")
}

/** Invitations to this account from other accounts (not profile scoped). */
suspend fun ApiClient.myAccountInvitations(): List<WatchRoomAccountInvitation> =
    sendList("GET", "/api/watch-room-account-invitations", promptOnLock = false, timeoutMs = 15_000)

/** Accepts with [profileId] as the member; answers the room (join it next). */
suspend fun ApiClient.acceptAccountInvitation(profileId: String, inviteId: String): WatchRoom =
    send("POST", "/api/users/${segment(profileId)}/watch-room-account-invitations/${segment(inviteId)}/accept")

suspend fun ApiClient.declineAccountInvitation(inviteId: String) {
    sendRaw("POST", "/api/watch-room-account-invitations/${segment(inviteId)}/decline")
}

/** The external guest link (QR code, short code); 428 when the server has no external URL set. */
suspend fun ApiClient.createExternalInvitation(profileId: String, roomId: String): WatchPartyInvitation =
    send("POST", "${room(profileId, roomId)}/external-invite")

suspend fun ApiClient.revokeExternalInvitation(profileId: String, roomId: String) {
    sendRaw("DELETE", "${room(profileId, roomId)}/external-invite")
}

/**
 * Hands the host's resolved stream to the room so web guests play the same file. The server
 * keeps only [WatchRoomSourceParams.ALLOWED] params.
 */
suspend fun ApiClient.bindWatchPartySource(profileId: String, roomId: String, source: String, params: Map<String, String>) {
    sendRaw("POST", "${room(profileId, roomId)}/external-source", promptOnLock = false, body = buildJsonObject {
        put("source", source)
        putJsonObject("params") { params.forEach { (k, v) -> put(k, v) } }
    })
}

/** The body of `POST watch-rooms`. */
data class WatchRoomCreate(
    val title: String,
    val mediaType: String,
    val itemId: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val params: Map<String, String>,
    val inviteeProfileIds: List<String>,
    /** The episode the room starts on (created from a specific episode). */
    val episode: WatchRoomEpisode? = null,
) {
    fun body(clientId: String = Config.clientId): JsonObject = buildJsonObject {
        put("title", title)
        put("mediaType", mediaType)
        put("itemId", itemId)
        posterUrl?.takeIf { it.isNotBlank() }?.let { put("posterUrl", it) }
        backdropUrl?.takeIf { it.isNotBlank() }?.let { put("backdropUrl", it) }
        putJsonObject("params") { params.forEach { (k, v) -> put(k, v) } }
        putJsonArray("inviteeProfileIds") { inviteeProfileIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("clientId", clientId)
        put("capabilities", nativeCapabilities())
        episode?.let { put("episode", it.body()) }
    }
}

/** The server's `WatchRoomEpisode` (strict decoder: only its fields, blanks left out). */
fun WatchRoomEpisode.body(): JsonObject = buildJsonObject {
    put("seasonNumber", seasonNumber)
    put("episodeNumber", episodeNumber)
    episodeId?.takeIf { it.isNotBlank() }?.let { put("episodeId", it) }
    title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
}

/** A stale `expectedRevision`: another member changed the state first; the next poll has it. */
val Throwable.isRevisionConflict: Boolean get() = (this as? ApiError)?.statusCode == 409

/** The room is gone for good (410 ended, 404 not found / not invited, 403 not a member). */
val Throwable.isWatchRoomGone: Boolean get() = (this as? ApiError)?.statusCode in setOf(403, 404, 410)

/** 410: the room ended. */
val Throwable.isWatchRoomEnded: Boolean get() = (this as? ApiError)?.statusCode == 410

/** No answer at all (offline, timed out): worth retrying. */
val Throwable.isNetworkFailure: Boolean get() = this is IOException
