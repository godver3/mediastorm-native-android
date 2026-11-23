package com.strmr.tv.watchtogether

import android.content.Context
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.App
import com.strmr.tv.app.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** The room a profile is in on this device, kept across launches. */
@Serializable
data class ActiveWatchRoom(val roomId: String, val itemId: String = "")

/**
 * What the next player opens into: the room it syncs with. Set by the lobby as it starts
 * playback; the player only syncs a playback of the room's own title ([WatchRoomTitle.matches]).
 */
data class WatchRoomLaunch(
    val roomId: String,
    val profileId: String,
    val itemId: String,
    val isHost: Boolean,
    /** The host's own resume point, seeded into the room on the first snapshot (seconds; 0 for none). */
    val resumePosition: Double = 0.0,
)

/**
 * The active Watch Together room per profile (the Expo `watchTogetherSession`), stored on the
 * device, and the room the player syncs with. Main thread.
 */
object WatchTogetherSession {
    private const val LOG = "WatchTogether"
    private val prefs by lazy { App.context.getSharedPreferences("strmr.watchTogether", Context.MODE_PRIVATE) }

    private val _active = MutableStateFlow<ActiveWatchRoom?>(null)
    /** The active profile's room, once [restore] has checked it with the server. */
    val active: StateFlow<ActiveWatchRoom?> = _active

    /** The room the next player syncs with ([WatchRoomLaunch]). */
    @Volatile var launch: WatchRoomLaunch? = null

    private fun key(profileId: String) = "room.$profileId"

    private fun stored(profileId: String): ActiveWatchRoom? = prefs.getString(key(profileId), null)?.let {
        runCatching { ApiClient.json.decodeFromString<ActiveWatchRoom>(it) }.getOrNull()
    }

    fun remember(profileId: String, room: WatchRoom) {
        val value = ActiveWatchRoom(room.id, room.itemId)
        if (_active.value == value && profileId == ApiClient.profileId) return
        prefs.edit().putString(key(profileId), ApiClient.json.encodeToString(ActiveWatchRoom.serializer(), value)).apply()
        if (profileId == ApiClient.profileId) _active.value = value
    }

    /** The room ended or was left: forget it, and stop any player syncing with it. */
    fun forget(profileId: String, roomId: String? = null) {
        val current = stored(profileId)
        if (roomId != null && current != null && current.roomId != roomId) return
        prefs.edit().remove(key(profileId)).apply()
        if (profileId == ApiClient.profileId) _active.value = null
        if (launch?.let { it.profileId == profileId && (roomId == null || it.roomId == roomId) } == true) launch = null
    }

    /**
     * After launch or a profile switch: checks the profile's stored room with the server. Kept
     * when it still runs, cleared when it ended or is gone, retried every 10 s while the server
     * can't be reached (the Expo `_layout` restore). Runs until settled; cancel to stop.
     */
    suspend fun restore(profileId: String) {
        _active.value = null
        val saved = stored(profileId) ?: return
        while (true) {
            try {
                val room = ApiClient.watchRoom(profileId, saved.roomId)
                if (room.ended || (saved.itemId.isNotEmpty() && saved.itemId != room.itemId)) {
                    AppLog.i(LOG, "saved room ${room.id} ended; cleared")
                    forget(profileId)
                } else {
                    AppLog.i(LOG, "saved room ${room.id} still ${room.status}")
                    remember(profileId, room)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isWatchRoomGone) {
                    forget(profileId)
                    return
                }
                AppLog.w(LOG, "restore failed (${e.message}); retrying in 10 s")
                delay(10_000)
            }
        }
    }
}
