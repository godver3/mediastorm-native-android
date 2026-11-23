package com.strmr.tv.watchtogether

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.app.AppLog
import kotlinx.coroutines.CancellationException

/** Creating a room from a details page, shared by the TV and phone pickers. */
object WatchTogetherStart {
    /**
     * Creates a room for [target] with [inviteeProfileIds] (this account's profiles) and, when
     * [accountUsername] is set, invites that account. The room is remembered as this profile's.
     * Succeeds with the room and a warning when only the account invite failed.
     */
    suspend fun create(target: DetailsTarget, inviteeProfileIds: List<String>, accountUsername: String): Result<Pair<WatchRoom, String?>> {
        val profileId = ApiClient.profileId
        return try {
            val room = ApiClient.createWatchRoom(profileId, WatchRoomTitle.create(target, inviteeProfileIds))
            WatchTogetherSession.remember(profileId, room)
            AppLog.i("WatchTogether", "created room ${room.id} for ${target.titleId} (${inviteeProfileIds.size} profiles)")
            val warning = accountUsername.trim().takeIf { it.isNotEmpty() }?.let { name ->
                try {
                    ApiClient.inviteAccountToWatchRoom(profileId, room.id, name)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Room created, but inviting $name failed: ${e.message}"
                }
            }
            Result.success(room to warning)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
