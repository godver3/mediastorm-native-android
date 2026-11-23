package com.strmr.mobile.ui.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.strmr.mobile.ui.onboarding.parseHex
import com.strmr.tv.ui.AppToast
import com.strmr.tv.ui.watchtogether.QrCodeImage
import com.strmr.tv.watchtogether.WatchLobbyModel
import com.strmr.tv.watchtogether.WatchRoomMember

@Composable
fun MemberRow(member: WatchRoomMember) {
    ListItem(
        headlineContent = { Text(member.name + if (member.isCreator) " · Host" else "") },
        leadingContent = { MemberDot(member, 36) },
        trailingContent = { Text(memberState(member), color = memberStateColor(member), style = MaterialTheme.typography.labelLarge) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.widthIn(max = 640.dp),
    )
}

/** A member's initial on their colour, dimmed when they're away. */
@Composable
fun MemberDot(member: WatchRoomMember, sizeDp: Int) {
    val color = member.color?.let(::parseHex) ?: Color(0xFF5E6AD2)
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(if (member.present) color else color.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(member.name.take(1).uppercase(), fontSize = (sizeDp * 0.45f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

fun memberState(m: WatchRoomMember): String = when {
    !m.joined -> "Invited"
    !m.present -> "Away"
    m.buffering -> "Buffering"
    m.ready -> "Ready"
    else -> "Joined"
}

fun memberStateColor(m: WatchRoomMember): Color = when {
    !m.joined || !m.present -> Color.White.copy(alpha = 0.5f)
    m.buffering -> Color(0xFFFFB74D)
    m.ready -> Color(0xFF81C784)
    else -> Color.White.copy(alpha = 0.75f)
}

/** The host's invitations: pending accounts (with revoke), invite by username, the guest link. */
fun LazyListScope.hostInvites(model: WatchLobbyModel) {
    item { SectionTitle("Invite server accounts") }
    items(model.pendingInvites, key = { "invite-${it.id}" }) { invite ->
        ListItem(
            headlineContent = { Text(invite.inviteeUsername ?: "Account") },
            supportingContent = { Text("Invited") },
            trailingContent = {
                IconButton(onClick = { model.revokeInvite(invite.id) }) { Icon(Icons.Outlined.Close, contentDescription = "Revoke") }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
    item { InviteField(model) }
    item { SectionTitle("Guests without an account") }
    item { GuestLink(model) }
}

@Composable
private fun InviteField(model: WatchLobbyModel) {
    var username by remember { mutableStateOf("") }
    fun invite() {
        if (username.isBlank()) return
        model.inviteAccount(username)
        username = ""
    }
    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { invite() }),
        )
        FilledTonalButton(onClick = ::invite, enabled = username.isNotBlank() && !model.busy) { Text("Invite") }
    }
}

@Composable
private fun GuestLink(model: WatchLobbyModel) {
    val external = model.external
    val clipboard = LocalClipboardManager.current
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (external == null) {
            Text("A QR code, short code and link for the web player.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = model::createExternal, enabled = !model.busy) { Text("Create Guest Link") }
            return@Column
        }
        QrCodeImage(external.url, Modifier.size(220.dp))
        Text(external.shortCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text("Enter the code at ${external.landingUrl.ifEmpty { external.url }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(external.url, color = MaterialTheme.colorScheme.primary, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            IconButton(onClick = { clipboard.setText(AnnotatedString(external.url)); AppToast.show("Link copied") }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy link")
            }
        }
        OutlinedButton(onClick = model::revokeExternal, enabled = !model.busy) { Text("Turn Off Guest Link") }
    }
}
