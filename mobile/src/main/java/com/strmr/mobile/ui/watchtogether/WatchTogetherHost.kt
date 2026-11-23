package com.strmr.mobile.ui.watchtogether

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.components.SheetAction
import com.strmr.mobile.ui.details.DetailsActionContext
import com.strmr.mobile.ui.details.DetailsExtraActions
import com.strmr.mobile.ui.shell.LocalNavigator
import com.strmr.mobile.ui.shell.Route
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.app.AppSession
import com.strmr.tv.ui.AppToast
import com.strmr.tv.watchtogether.WatchTogetherStart
import kotlinx.coroutines.launch

/** The Watch Together picker asked for from a details page; non-null while it is up. */
object WatchTogetherUi {
    var creating by mutableStateOf<DetailsTarget?>(null)
}

/** Details › More › Watch Together… (titles with catalog ids; a home video has nothing to sync by). */
object DetailsWatchTogetherActions : DetailsExtraActions {
    override fun moreActions(context: DetailsActionContext): List<SheetAction> {
        val target = context.model.target
        if (!target.hasCatalogIds) return emptyList()
        return listOf(SheetAction("Watch Together…", Icons.Outlined.Groups) { WatchTogetherUi.creating = target })
    }
}

/** The picker over the signed-in app (in MainScaffold): Create opens the new room's lobby. */
@Composable
fun WatchTogetherHost() {
    val target = WatchTogetherUi.creating ?: return
    val navigator = LocalNavigator.current
    CreateRoomSheet(target, onCreated = { navigator.push(Route.WatchRoom(it)) }, onDismiss = { WatchTogetherUi.creating = null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRoomSheet(target: DetailsTarget, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val profiles by AppSession.profiles.collectAsState()
    val others = profiles.filter { it.id != ApiClient.profileId }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var username by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun create() {
        if (busy) return
        busy = true
        scope.launch {
            val result = WatchTogetherStart.create(target, picked.toList(), username)
            busy = false
            result.onSuccess { (room, warning) ->
                onDismiss()
                onCreated(room.id)
                warning?.let(AppToast::show)
            }.onFailure { error = it.message ?: "Couldn't create the room." }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Watch Together", style = MaterialTheme.typography.titleLarge)
            Text(target.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (others.isNotEmpty()) {
                Text("Invite profiles", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                others.forEach { p ->
                    val on = p.id in picked
                    ListItem(
                        headlineContent = { Text(p.name) },
                        trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { picked = if (on) picked - p.id else picked + p.id },
                    )
                }
            }
            OutlinedTextField(
                username, { username = it }, label = { Text("Another account (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Guests without an account can join from the lobby's guest link.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = ::create, enabled = !busy) { Text(if (busy) "Creating…" else "Create Room") }
            }
        }
    }
}
