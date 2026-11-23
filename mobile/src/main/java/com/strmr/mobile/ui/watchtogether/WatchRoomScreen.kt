package com.strmr.mobile.ui.watchtogether

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.strmr.mobile.ui.shell.LocalNavigator
import com.strmr.mobile.ui.shell.PageScaffold
import com.strmr.tv.api.Artwork
import com.strmr.tv.watchtogether.WatchLobbyModel
import com.strmr.tv.watchtogether.WatchRoomStatus

/**
 * A Watch Together room's lobby on a phone or tablet (the Expo `watch-room` screen): the title,
 * Ready or Start, the members, and for the host the invitations and guest link. Back asks to
 * end (host) or leave the room. When the host starts, the player opens and returns here.
 */
@Composable
fun WatchRoomScreen(roomId: String, accountInviteId: String?) {
    val scope = rememberCoroutineScope()
    val model = remember(roomId) { WatchLobbyModel(roomId, accountInviteId, scope) }
    val navigator = LocalNavigator.current
    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.run() }
    LaunchedEffect(model.closed) { if (model.closed) navigator.pop() }
    val room = model.room
    BackHandler(enabled = room != null && !model.ended) { confirmExit = true }

    // The top bar's back arrow behaves as system Back: a running room asks to end or leave first.
    PageScaffold("Watch Together", showBack = true, onBack = { if (room != null && !model.ended) confirmExit = true else navigator.pop() }) { padding ->
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = Artwork.url(room?.backdropUrl ?: room?.posterUrl ?: model.accountInvitation?.backdropUrl),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                    )
                    Text(room?.title ?: model.accountInvitation?.title ?: "Loading…", style = MaterialTheme.typography.headlineSmall)
                    Text(statusLine(model), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Actions(model, onExit = { confirmExit = true }, onClose = { navigator.pop() })
                }
            }
            if (room != null) {
                item { SectionTitle("In this room") }
                items(room.members, key = { it.profileId }) { MemberRow(it) }
            }
            if (model.isHost && !model.ended) hostInvites(model)
        }
    }
    if (confirmExit) {
        val host = model.isHost
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(if (host) "End this room?" else "Leave this room?") },
            text = { Text(if (host) "Everyone in the room stops watching." else "The others keep watching without you.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; model.exit() }) { Text(if (host) "End Room" else "Leave Room") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } },
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

private fun statusLine(model: WatchLobbyModel): String {
    val room = model.room
    model.accountInvitation?.let { return "${it.creatorName ?: "Someone"} invited your account. Accept with this profile to join." }
    room ?: return if (model.busy) "Joining…" else ""
    room.endedMessage?.let { return it }
    val host = if (model.isHost) "You're the host" else "${room.creatorName} is the host"
    val state = when (room.status) {
        WatchRoomStatus.LOBBY -> if (model.isHost) "start when everyone's here" else "waiting for the host to start"
        WatchRoomStatus.PLAYING -> "playing"
        WatchRoomStatus.PAUSED -> "paused"
        else -> room.status
    }
    return "$host · $state"
}

@Composable
private fun Actions(model: WatchLobbyModel, onExit: () -> Unit, onClose: () -> Unit) {
    val room = model.room
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val wide = Modifier.weight(1f)
        when {
            model.accountInvitation != null -> {
                Button(onClick = model::acceptInvitation, enabled = !model.busy, modifier = wide) { Text("Accept") }
                OutlinedButton(onClick = model::declineInvitation, enabled = !model.busy, modifier = wide) { Text("Decline") }
            }
            room == null || model.ended -> FilledTonalButton(onClick = onClose, modifier = wide) { Text("Close") }
            else -> {
                when {
                    room.status != WatchRoomStatus.LOBBY -> Button(onClick = model::rejoinPlayback, modifier = wide) { Text("Rejoin Playback") }
                    model.isHost -> Button(onClick = model::start, enabled = !model.busy, modifier = wide) { Text("Start") }
                    else -> Button(onClick = model::toggleReady, enabled = !model.busy, modifier = wide) {
                        Text(if (model.me?.ready == true) "Not Ready" else "I'm Ready")
                    }
                }
                OutlinedButton(onClick = onExit, modifier = wide) { Text(if (model.isHost) "End Room" else "Leave Room") }
            }
        }
    }
}
