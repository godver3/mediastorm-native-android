package com.strmr.tv.ui.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.strmr.tv.ui.onboarding.parseHex
import com.strmr.tv.watchtogether.WatchRoom
import com.strmr.tv.watchtogether.WatchRoomMember

/** The room's members, each with a colour dot and their state; not focusable (nothing to do on them). */
@Composable
fun MemberList(room: WatchRoom, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("In this room", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f))
        room.members.forEach { MemberRow(it) }
    }
}

@Composable
private fun MemberRow(member: WatchRoomMember) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MemberDot(member, 28)
        Text(member.name + if (member.isCreator) " · Host" else "", fontWeight = FontWeight.Medium)
        Text(memberState(member), color = memberStateColor(member), fontSize = 14.sp)
    }
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
