package com.kmusic.apkhd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.theme.wspSp

/**
 * 封面占位 / 图片。
 * 与设计稿一致：indigo 单色渐变 + 左上角母带标签细线胶囊。
 */
@Composable
fun CoverArt(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    qualityTag: String? = null
) {
    val c = moyinColors()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(c.coverGrad))
    ) {
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (qualityTag != null) {
            Text(
                text = qualityTag,
                color = if (c.isDark) Color(0xFFD8D9E8) else Color(0xFF4A47BE),
                fontSize = 9.wsp,
                letterSpacing = 0.2.wspSp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.wdp)
                    .background(
                        color = if (c.isDark) Color(0x73000000) else Color(0xB8FFFFFF),
                        shape = RoundedCornerShape(99.dp)
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (c.isDark) Color(0x2EFFFFFF) else Color(0x12000000),
                        shape = RoundedCornerShape(99.dp)
                    )
                    .padding(horizontal = 5.wdp, vertical = 2.wdp)
            )
        }
    }
}

/**
 * 音质细线标签 —— 设计稿中唯一允许使用强调色的地方之一（仅边框与文字）。
 */
@Composable
fun QualityTag(
    text: String,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Text(
        text = text,
        color = if (c.isDark) Color(0xFFA5A3F2) else Color(0xFF4A47BE),
        fontSize = 9.wsp,
        letterSpacing = 0.17.wspSp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .border(width = 0.5.dp, color = c.accentLine, shape = RoundedCornerShape(99.dp))
            .padding(horizontal = 5.wdp, vertical = 2.wdp)
    )
}

/**
 * 模糊封面背景 —— 歌词态使用，封面虚化放大后铺底。
 */
@Composable
fun BlurredCoverBackground(
    coverUrl: String?,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Box(
        modifier = modifier
            .background(Brush.linearGradient(c.coverGrad))
    ) {
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(26.dp)
            )
        }
        // 蒙层：深色压暗到 90%，浅色提亮到 90%
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            c.bg.copy(alpha = 0.55f),
                            c.bg.copy(alpha = 0.90f)
                        )
                    )
                )
        )
    }
}

/** 通用次级信息行 */
@Composable
fun MetaText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Text(
        text = text,
        color = moyinColors().t3,
        fontSize = 9.wsp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
