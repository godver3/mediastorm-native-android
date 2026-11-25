package com.strmr.tv.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text

/** Playback Debugging, at the bottom of Settings: closed until opened, then a switch per [PlaybackDebugFlag]. */
fun LazyListScope.playbackDebugRows(open: Boolean, onToggleOpen: () -> Unit) {
    item(key = "playbackDebug") {
        val changed = PlaybackDebugFlags.changed.size
        ListItem(
            selected = false, onClick = onToggleOpen,
            headlineContent = { Text("Playback Debugging") },
            supportingContent = {
                Text((if (changed > 0) "$changed changed · " else "") +
                    "Turn parts of the player on or off to track down a problem; applies to the next playback")
            },
            trailingContent = { Text(if (open) "Hide" else "Show") },
        )
    }
    if (!open) return
    items(PlaybackDebugFlag.entries, key = { "playbackDebug.${it.name}" }) { flag ->
        val on = PlaybackDebugFlags.on(flag)
        ListItem(
            selected = false, onClick = { PlaybackDebugFlags.set(flag, !on) },
            headlineContent = { Text(flag.title) },
            supportingContent = { Text(flag.detail + if (on != flag.default) " · changed" else "") },
            trailingContent = { Text(if (on) "On" else "Off") },
        )
    }
    item(key = "playbackDebug.reset") {
        ListItem(
            selected = false, onClick = { PlaybackDebugFlags.reset() },
            headlineContent = { Text("Reset Playback Debugging") },
            supportingContent = { Text("Everything back to its default") },
        )
    }
}
