package com.strmr.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.strmr.tv.ui.watchtogether.MemberDot
import com.strmr.tv.ui.watchtogether.memberState
import com.strmr.tv.ui.watchtogether.memberStateColor
import com.strmr.tv.watchtogether.WatchRoomSync

/**
 * Watch Together over the video: who's watching (a dot each, top left, clear of the clock and toasts; never focusable, so the
 * remote stays with the player) and, while the room waits for everyone's player, the
 * preparing card with who is ready.
 */
@Composable
fun WatchRoomOverlays(sync: WatchRoomSync, controlsVisible: Boolean = false, modifier: Modifier = Modifier) {
    val room by sync.room.collectAsState()
    val switching by sync.switching.collectAsState()
    val current = room ?: return
    Box(modifier.fillMaxSize().focusProperties { canFocus = false }) {
        AnimatedVisibility(com.strmr.tv.ui.watchtogether.rememberPresenceVisible(current, controlsVisible), Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.padding(top = 40.dp, start = 64.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                current.members.filter { it.joined }.forEach { MemberDot(it, 22) }
            }
        }
        AnimatedVisibility(current.waitingForReady || switching != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.width(420.dp).background(Color(0xE61C1C1E), RoundedCornerShape(20.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val ready = current.members.count { it.joined && it.ready }
                val joined = current.members.count { it.joined }
                Text(switching?.let { "Switching to the host's episode, ${it.code}…" } ?: "Getting everyone ready…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("$ready of $joined ready", color = Color.White.copy(alpha = 0.7f))
                current.members.filter { it.joined }.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MemberDot(m, 22)
                        Text(m.name, Modifier.weight(1f))
                        Text(memberState(m), color = memberStateColor(m), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
