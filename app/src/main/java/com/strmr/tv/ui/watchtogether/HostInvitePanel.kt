package com.strmr.tv.ui.watchtogether

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.strmr.tv.ui.PanelSection
import com.strmr.tv.ui.watchtogether.QrCodeImage
import com.strmr.tv.watchtogether.WatchLobbyModel

/**
 * The host's side of the lobby: pending account invitations (Select revokes one), Invite an
 * Account…, and the guest link for people without an account (a QR code, a short code and
 * the link, played in the web player), with Turn Off.
 */
@Composable
fun HostInvitePanel(model: WatchLobbyModel, modifier: Modifier = Modifier) {
    var inviting by remember { mutableStateOf(false) }
    val external = model.external
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Invite", style = MaterialTheme.typography.titleLarge) }
        item { PanelSection("Server Accounts") }
        items(model.pendingInvites, key = { it.id }) { invite ->
            ListItem(
                selected = false, onClick = { model.revokeInvite(invite.id) },
                headlineContent = { Text(invite.inviteeUsername ?: "Account") },
                supportingContent = { Text("Invited · Select to revoke") },
            )
        }
        item {
            ListItem(selected = false, onClick = { inviting = true }, headlineContent = { Text("Invite an Account…") })
        }
        item { PanelSection("Guests Without an Account") }
        if (external == null) {
            item {
                ListItem(
                    selected = false, onClick = model::createExternal,
                    headlineContent = { Text("Create Guest Link") },
                    supportingContent = { Text("A QR code and short code for the web player") },
                )
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    QrCodeImage(external.url, Modifier.size(200.dp))
                }
            }
            item {
                Text(external.shortCode, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                     modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            item {
                Text("Scan the code, or enter the short code at ${external.landingUrl.ifEmpty { external.url }}",
                     color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
            item { Text(external.url, color = Color(0xFF93C5FD), fontSize = 13.sp, maxLines = 2) }
            item { ListItem(selected = false, onClick = model::revokeExternal, headlineContent = { Text("Turn Off Guest Link") }) }
        }
    }
    if (inviting) InviteAccountDialog(onInvite = { model.inviteAccount(it) }, onDismiss = { inviting = false })
}
