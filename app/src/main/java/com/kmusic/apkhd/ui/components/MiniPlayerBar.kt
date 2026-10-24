package com.kmusic.apkhd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.ui.icons.IconPause
import com.kmusic.apkhd.ui.icons.IconPlay
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp

/**
 * 迷你播放条 —— 悬浮在 Tab 栏之上，玻璃拟态。
 * 与设计稿一致：24dp 封面 + 标题/歌手 + 细进度 + 播放键。
 */
@Composable
fun MiniPlayerBar(
    title: String,
    artist: String,
    qualityLabel: String,
    coverUrl: String?,
    progress: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.wdp)
            // 深色（A）：玻璃条，和底栏胶囊同一语言；浅色（B）：纸白方块 + 细线
            .clip(RoundedCornerShape(c.radiusCard))
            // 深色下原来用 7.5% 白的玻璃，背后封面一透上来文字就看不清了；
            // 改成实底 + 高光边，保住层次又不牺牲可读性
            .background(c.s1)
            .border(
                width = 1.wdp,
                color = if (c.isDark) c.glassBorder else c.lineStrong.copy(alpha = 0.20f),
                shape = RoundedCornerShape(c.radiusCard)
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 10.wdp, vertical = 8.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(coverUrl = coverUrl, modifier = Modifier.size(44.wdp), cornerRadius = 8.wdp)
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = c.t1,
                fontSize = 13.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$artist · $qualityLabel",
                color = c.t3,
                fontSize = 10.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.wdp))
            // 细进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.wdp)
                    .clip(RoundedCornerShape(2.wdp))
                    .background(if (c.isDark) Color(0x1AFFFFFF) else Color(0x17000000))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.wdp))
                        .background(c.accentFill)
                )
            }
        }
        Spacer(Modifier.width(12.wdp))
        Box(
            Modifier
                .size(40.wdp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (c.isDark) Color(0x17FFFFFF) else Color(0x0F000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onTogglePlay() },
            contentAlignment = Alignment.Center
        ) {
            MoyinIcon(
                icon = if (isPlaying) IconPause else IconPlay,
                tint = c.t1,
                size = 18.wdp
            )
        }
    }
}
