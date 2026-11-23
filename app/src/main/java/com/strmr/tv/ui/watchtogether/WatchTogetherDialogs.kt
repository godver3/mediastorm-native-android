package com.strmr.tv.ui.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.app.AppSession
import com.strmr.tv.ui.ignoreHeldSelect
import com.strmr.tv.ui.onboarding.FormField
import com.strmr.tv.watchtogether.WatchTogetherStart
import kotlinx.coroutines.launch

/**
 * Details › More › Watch Together…: pick this account's other profiles to invite and, optionally,
 * another server account by username; Create opens the new room's lobby ([onCreated]).
 */
@Composable
fun WatchTogetherCreateDialog(target: DetailsTarget, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val profiles by AppSession.profiles.collectAsState()
    val others = profiles.filter { it.id != ApiClient.profileId }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var username by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun create() {
        if (busy) return
        busy = true
        scope.launch {
            val result = WatchTogetherStart.create(target, picked.toList(), username)
            busy = false
            result.onSuccess { (room, warning) ->
                onDismiss()
                onCreated(room.id)
                warning?.let { com.strmr.tv.ui.AppToast.show(it) }
            }.onFailure { error = it.message ?: "Couldn't create the room." }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().ignoreHeldSelect().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(520.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Watch Together", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(target.name, color = Color.White.copy(alpha = 0.7f))
                if (others.isNotEmpty()) {
                    Text("Invite profiles", color = Color.White.copy(alpha = 0.6f))
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(others, key = { it.id }) { p ->
                            val on = p.id in picked
                            ListItem(
                                selected = false,
                                onClick = { picked = if (on) picked - p.id else picked + p.id },
                                headlineContent = { Text(p.name) },
                                trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                                modifier = if (p == others.first()) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
                FormField(
                    username, { username = it }, label = "Another account (optional)", placeholder = "Username",
                    imeAction = ImeAction.Done,
                    modifier = if (others.isEmpty()) Modifier.focusRequester(first) else Modifier,
                )
                Text("Guests without an account can join from the lobby's guest link.", color = Color.White.copy(alpha = 0.5f))
                error?.let { Text(it, color = Color(0xFFFF6B6B)) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = ::create, enabled = !busy) { Text(if (busy) "Creating…" else "Create Room") }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** The lobby's Invite an Account…: one username. */
@Composable
fun InviteAccountDialog(onInvite: (String) -> Unit, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    fun invite() {
        if (username.isBlank()) return
        onDismiss()
        onInvite(username.trim())
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(480.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Invite an Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                FormField(username, { username = it }, label = "Username", imeAction = ImeAction.Done, onSubmit = ::invite,
                          modifier = Modifier.focusRequester(field))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = ::invite, enabled = username.isNotBlank()) { Text("Invite") }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
