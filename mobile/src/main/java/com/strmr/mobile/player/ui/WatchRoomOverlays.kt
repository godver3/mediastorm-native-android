package com.strmr.mobile.player.ui

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.watchtogether.MemberDot
import com.strmr.mobile.ui.watchtogether.memberState
import com.strmr.mobile.ui.watchtogether.memberStateColor
import com.strmr.tv.watchtogether.WatchRoomSync

/**
 * Watch Together over the video: who's watching (a dot each, top right, drawn under the
 * controls and taking no touches) and, while the room waits for everyone's player, the
 * preparing card with who is ready.
 */
@Composable
fun WatchRoomOverlays(sync: WatchRoomSync, modifier: Modifier = Modifier) {
    val room by sync.room.collectAsState()
    val current = room ?: return
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            current.members.filter { it.joined }.forEach { MemberDot(it, 20) }
        }
        AnimatedVisibility(current.waitingForReady, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.widthIn(max = 360.dp).background(Color(0xE61C1C1E), RoundedCornerShape(16.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val joined = current.members.filter { it.joined }
                Text("Getting everyone ready…", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("${joined.count { it.ready }} of ${joined.size} ready", color = Color.White.copy(alpha = 0.7f))
                joined.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemberDot(m, 20)
                        Text(m.name, color = Color.White, modifier = Modifier.weight(1f))
                        Text(memberState(m), color = memberStateColor(m), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
