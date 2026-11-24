package com.strmr.tv.ui.watchtogether

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.strmr.tv.api.Artwork
import com.strmr.tv.ui.ChoiceDialog
import com.strmr.tv.ui.ContentMargin
import com.strmr.tv.ui.DialogChoice
import com.strmr.tv.ui.LocalNavigator
import com.strmr.tv.watchtogether.WatchLobbyModel
import com.strmr.tv.watchtogether.WatchRoomStatus

/**
 * A Watch Together room's lobby (the Expo `watch-room` screen): the title, the members with
 * their joined / present / ready / buffering state, Ready (guests) or Start (host), and on the
 * right the host's invitations and guest link. Back asks to end the room (host) or leave it.
 * When the host starts, the player opens over this page and returns to it.
 */
@Composable
fun WatchRoomScreen(roomId: String, accountInviteId: String?) {
    val scope = rememberCoroutineScope()
    val model = remember(roomId) { WatchLobbyModel(roomId, accountInviteId, scope) }
    val navigator = LocalNavigator.current
    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.run() }
    LaunchedEffect(model.closed) { if (model.closed) navigator.close() }

    val room = model.room
    // Back: a running room asks first; an ended room or an unanswered invitation just closes.
    BackHandler(enabled = room != null && !model.ended) { confirmExit = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = Artwork.url(room?.backdropUrl ?: room?.posterUrl ?: model.accountInvitation?.backdropUrl),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.35f),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.5f)))))
        Row(Modifier.fillMaxSize().padding(horizontal = ContentMargin, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Watch Together", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium)
                Text(
                    room?.title ?: model.accountInvitation?.title ?: "Loading…",
                    style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, maxLines = 2,
                )
                Text(statusLine(model), color = Color.White.copy(alpha = 0.75f))
                model.error?.let { Text(it, color = Color(0xFFFF6B6B)) }
                LobbyActions(model, onExit = { confirmExit = true }, onClose = { navigator.close() })
                if (room != null) MemberList(room, Modifier.padding(top = 12.dp))
            }
            if (model.isHost && !model.ended) {
                HostInvitePanel(model, Modifier.width(420.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xCC1C1C1E)))
            }
        }
    }
    if (confirmExit) {
        val host = model.isHost
        ChoiceDialog(
            title = if (host) "End this room?" else "Leave this room?",
            message = if (host) "Everyone in the room stops watching." else "The others keep watching without you.",
            choices = listOf(
                DialogChoice(if (host) "End Room" else "Leave Room") { model.exit() },
                DialogChoice("Stay") {},
            ),
            onDismiss = { confirmExit = false },
        )
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

/** Accept / Decline an account invitation; Ready or Start; Rejoin while it plays; Leave or End. */
@Composable
private fun LobbyActions(model: WatchLobbyModel, onExit: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val room = model.room
    val invite = model.accountInvitation
    // Focus the first action whenever the set of actions changes (invitation answered, room ended).
    val shape = listOf(invite != null, room != null, model.ended, model.isHost, room?.status)
    LaunchedEffect(shape) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            invite != null -> {
                Button(onClick = model::acceptInvitation, enabled = !model.busy, modifier = Modifier.focusRequester(first)) { Text("Accept") }
                OutlinedButton(onClick = model::declineInvitation, enabled = !model.busy) { Text("Decline") }
            }
            room == null -> OutlinedButton(onClick = onClose, modifier = Modifier.focusRequester(first)) { Text("Close") }
            model.ended -> Button(onClick = onClose, modifier = Modifier.focusRequester(first)) { Text("Close") }
            else -> {
                when {
                    room.status != WatchRoomStatus.LOBBY ->
                        Button(onClick = model::rejoinPlayback, modifier = Modifier.focusRequester(first)) { Text(model.rejoinLabel) }
                    model.isHost ->
                        Button(onClick = model::start, enabled = !model.busy, modifier = Modifier.focusRequester(first)) { Text("Start") }
                    else -> Button(onClick = model::toggleReady, enabled = !model.busy, modifier = Modifier.focusRequester(first)) {
                        Text(if (model.me?.ready == true) "Not Ready" else "I'm Ready")
                    }
                }
                OutlinedButton(onClick = onExit) { Text(if (model.isHost) "End Room" else "Leave Room") }
            }
        }
    }
}
