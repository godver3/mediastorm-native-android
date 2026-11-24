package com.strmr.tv.ui.details

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.strmr.tv.ui.SidePanelDialog

/**
 * Quality: the presets the server offers for the title, in its order, the default marked and
 * the current one checked and focused. Picking one ranks Play and Choose Source with it; the
 * server remembers it for this title on this device (the default forgets it).
 */
@Composable
fun QualityPresetDialog(state: QualityPresetState, title: String, onDismiss: () -> Unit) {
    val presets = state.offered
    val active = state.activeId
    val focusIndex = presets.indexOfFirst { it.id == active }.coerceAtLeast(0)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    SidePanelDialog("Quality", title, onDismiss) {
        itemsIndexed(presets) { index, preset ->
            val selected = preset.id == active
            ListItem(
                selected = selected,
                onClick = { onDismiss(); state.pick(preset.id) },
                headlineContent = {
                    Text(if (preset.id == state.defaultId) "${preset.name} (default)" else preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = if (selected) { { Icon(Icons.Filled.Check, contentDescription = "Selected") } } else null,
                modifier = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier,
            )
        }
        item {
            Text("Remembered for this title on this device.",
                 fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 12.dp))
        }
    }
}
