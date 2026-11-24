package com.strmr.tv.ui.watchtogether

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.strmr.tv.watchtogether.WatchRoom
import com.strmr.tv.watchtogether.WatchSyncRules
import kotlinx.coroutines.delay

/** Whether the player's presence strip shows now ([WatchSyncRules.presenceVisible]); both apps' overlays use it. */
@Composable
fun rememberPresenceVisible(room: WatchRoom, controlsVisible: Boolean = false): Boolean {
    val key = WatchSyncRules.presenceKey(room)
    var changedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(key) {
        changedAt = System.currentTimeMillis()
        now = changedAt
        // Re-check once the show window has passed.
        delay(WatchSyncRules.PRESENCE_SHOW_MS + 50)
        now = System.currentTimeMillis()
    }
    return WatchSyncRules.presenceVisible(room, changedAt, now, controlsVisible)
}
