package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.model.QuickEntry
import com.kmusic.apkhd.model.ChartSection
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.ArtistBrief
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.ui.components.CoverArt
import androidx.compose.foundation.border
import com.kmusic.apkhd.ui.components.MoyinTitle
import com.kmusic.apkhd.ui.components.MoyinNum
import com.kmusic.apkhd.ui.components.MoyinDivider
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.components.SectionHeader
import androidx.compose.foundation.combinedClickable
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.icons.IconPlay
import com.kmusic.apkhd.ui.icons.IconSearch
import com.kmusic.apkhd.ui.theme.ambientAlpha
import com.kmusic.apkhd.ui.theme.ambientGlow
import com.kmusic.apkhd.ui.theme.cardGradient
import com.kmusic.apkhd.ui.theme.cardHighlightGradient
import com.kmusic.apkhd.ui.theme.coverRadiusOf
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.window
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color as UiColor
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconRefresh

/**
 * 首页 —— 与设计稿 01 一致：
 * 问候语 + 搜索框 + 四个入口 + 横滑推荐卡 + 迷你播放条
 */
@Composable
fun HomeScreen(
    entries: List<QuickEntry>,
    recentTracks: List<Track>,
    charts: List<ChartSection> = emptyList(),
    playlists: List<PlaylistBrief> = emptyList(),
    artists: List<ArtistBrief> = emptyList(),
    onSearchClick: () -> Unit,
    onEntryClick: (QuickEntry) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onRecentClick: (Track, List<Track>) -> Unit,
    /** 打开完整「最近播放」列表（媒体库） */
    onOpenRecentList: () -> Unit = {},
    onDownload: ((Track) -> Unit)? = null,
    downloadedIds: Set<String> = emptySet(),
    onChartClick: (ChartSection) -> Unit = {},
    onPlaylistClick: (PlaylistBrief) -> Unit = {},
    /** 该歌单是否已收藏（首页卡片角标） */
    isPlaylistFavorite: (PlaylistBrief) -> Boolean = { false },
    /** 长按卡片收藏 / 取消收藏 */
    onTogglePlaylistFavorite: (PlaylistBrief) -> Unit = {},
    onArtistClick: (ArtistBrief) -> Unit = {},
    /** 换一批（推荐歌单 + 音乐人） */
    onRefreshPlaylists: () -> Unit = {},
    onRefreshArtists: () -> Unit = {},
    playlistsRefreshing: Boolean = false,
    artistsRefreshing: Boolean = false,
    onMiniPlayerClick: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            // 顶部氛围光：从屏幕顶端往下洇开一层强调色。
            // 实现见 AmbientGlow.kt —— 椭圆的纵向半轴压在光带高度之内，
            // 底边是「背景色 → 背景色」，宽屏也不会被裁出一条横向接缝。
            .ambientGlow(
                tint = c.accent,
                bg = c.bg,
                alpha = c.ambientAlpha,
                baseHeight = 340.wdp
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.wdp, end = 16.wdp, top = 8.wdp, bottom = 142.wdp)
        ) {
            item {
                Text(
                    text = greeting(),
                    color = c.t2,
                    fontSize = 14.wsp
                )
                Spacer(Modifier.height(12.wdp))
                MoyinTitle(text = "听点什么", size = 30)
                Spacer(Modifier.height(24.wdp))

                // 搜索入口（点击跳到搜索页）
                Column {
                    // 两套主题都必须有形状和边界。曾经浅色只画一条下划线，
                    // 结果是「深色有胶囊、浅色什么都没有」—— 看起来像圆角没做。
                    // 浅色改成同半径的实底 + 细线：纸感靠线，但形状必须和深色一致。
                    val searchShape = RoundedCornerShape(c.radiusCard)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.wdp)
                            .clip(searchShape)
                            .background(if (c.isDark) c.glass else c.s2)
                            .border(
                                width = 1.wdp,
                                color = if (c.isDark) c.glassBorder else c.lineStrong.copy(alpha = 0.22f),
                                shape = searchShape
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onSearchClick() }
                            .padding(horizontal = 14.wdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MoyinIcon(icon = IconSearch, tint = c.t3, size = 18.wdp)
                        Spacer(Modifier.width(10.wdp))
                        Text(
                            text = "搜索歌曲、歌手、专辑",
                            color = c.t3,
                            fontSize = 14.wsp,
                            letterSpacing = if (c.titleSerif) 0.4.sp else 0.sp
                        )
                    }
                }
                Spacer(Modifier.height(24.wdp))
            }

            // 四个入口（宽屏 4 列一行排开，窄屏 2×2）
            item {
                Column {
                    val cols = if (window.isWide) 4 else 2
                    entries.chunked(cols).forEach { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.wdp),
                            horizontalArrangement = Arrangement.spacedBy(8.wdp)
                        ) {
                            row.forEach { entry ->
                                QuickEntryCard(
                                    entry = entry,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onEntryClick(entry) }
                                )
                            }
                            // 最后一行不足时补占位，避免卡片被拉宽
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(20.wdp))
            }

            // 继续播放：把最近一首提到顶部，一键续播（比让用户去列表里找少一步）
            recentTracks.firstOrNull()?.let { last ->
                item(key = "continue") {
                    ContinueCard(
                        track = last,
                        recentCount = recentTracks.size,
                        onClick = { onRecentClick(last, recentTracks) },
                        onOpenList = onOpenRecentList,
                        modifier = Modifier.padding(top = 14.wdp)
                    )
                }
            }

            // ---- 排行榜（酷我原生，点进去必定能播） ----
            if (charts.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(22.wdp))
                    SectionHeader(text = "排行榜", hint = "可播放")
                    Spacer(Modifier.height(12.wdp))
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.wdp)) {
                        items(charts) { sec -> ChartCard(section = sec, onClick = { onChartClick(sec) }) }
                    }
                }
            }

            // ---- 推荐歌单（网易云推荐位） ----
            if (playlists.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(22.wdp))
                    SectionHeader(
                        text = "推荐歌单",
                        trailing = { RefreshButton(onClick = onRefreshPlaylists, busy = playlistsRefreshing) }
                    )
                    Spacer(Modifier.height(12.wdp))
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.wdp)) {
                        items(playlists) { pl ->
                            PlaylistCard(
                                playlist = pl,
                                onClick = { onPlaylistClick(pl) },
                                favorited = isPlaylistFavorite(pl),
                                onLongPress = { onTogglePlaylistFavorite(pl) }
                            )
                        }
                    }
                }
            }

            // ---- 音乐人 ----
            if (artists.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(22.wdp))
                    SectionHeader(
                        text = "音乐人",
                        trailing = { RefreshButton(onClick = onRefreshArtists, busy = artistsRefreshing) }
                    )
                    Spacer(Modifier.height(12.wdp))
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.wdp)) {
                        items(artists) { a -> ArtistCircle(artist = a, onClick = { onArtistClick(a) }) }
                    }
                }
            }
        }

        }
    }
}

/**
 * 「换一批」按钮 —— 推荐歌单 / 音乐人区块标题右侧。
 *
 * 做成**文字 + 图标**而不是图标按钮：区块标题行高只有 12.wsp 左右，
 * 纯图标没有说明性，用户不知道点了会发生什么。
 * busy 态下禁用点击并显示「换一批中」，避免连点造成并发请求。
 */
@Composable
private fun RefreshButton(
    onClick: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(99.wdp))
            .background(if (busy) c.s2 else c.accentDim)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !busy,
                onClick = onClick
            )
            .padding(horizontal = 10.wdp, vertical = 4.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoyinIcon(
            icon = IconRefresh,
            tint = if (busy) c.t3 else c.accent,
            size = 13.wdp
        )
        Spacer(Modifier.width(4.wdp))
        Text(
            text = if (busy) "换一批中" else "换一批",
            color = if (busy) c.t3 else c.accent,
            fontSize = 11.wsp
        )
    }
}

@Composable
private fun QuickEntryCard(
    entry: QuickEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    // 渐变由主题 token 生成（浅色也有）：高亮卡用强调色淡染，普通卡 s1→s2 / s3→s2
    val bg = Brush.linearGradient(
        if (entry.highlighted) c.cardHighlightGradient else c.cardGradient
    )
    val titleColor = if (entry.highlighted) c.accent else c.t1
    val subColor = if (entry.highlighted) c.t2 else c.t3

    Column(
        modifier = modifier
            .height(72.wdp)
            .clip(RoundedCornerShape(c.radiusCard))
            .background(bg)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.wdp, vertical = 10.wdp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = entry.title, color = titleColor, fontSize = 13.wsp, maxLines = 1)
        Spacer(Modifier.height(12.wdp))
        Text(text = entry.subtitle, color = subColor, fontSize = 10.wsp, maxLines = 1)
    }
}

@Composable
private fun ContinueCard(
    track: Track,
    recentCount: Int,
    onClick: () -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(c.radiusCard))
            .background(Brush.linearGradient(c.cardHighlightGradient))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.wdp, vertical = 11.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(
            coverUrl = track.coverUrl,
            modifier = Modifier.size(54.wdp),
            cornerRadius = coverRadiusOf(c)
        )
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "继续播放",
                color = c.accent,
                fontSize = 10.wsp,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(3.wdp))
            Text(
                text = track.name,
                color = c.t1,
                fontSize = 15.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.wdp))
            Text(
                text = track.artist,
                color = c.t3,
                fontSize = 11.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.wdp))
        Column(horizontalAlignment = Alignment.End) {
            MoyinIcon(icon = IconPlay, tint = c.accent, size = 22.wdp)
            Spacer(Modifier.height(4.wdp))
            // 完整列表在媒体库；这里只留一个轻入口，避免首页再长出一份列表
            Text(
                text = "最近 $recentCount 首 ›",
                color = c.t3,
                fontSize = 10.wsp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onOpenList() }
                    .padding(horizontal = 2.wdp, vertical = 2.wdp)
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: ((Track) -> Unit)? = null,
    downloaded: Boolean = false,
    /** 列表序号。浅色（B）会渲染成砖红色的 01/02/03 */
    index: Int? = null
) {
    val c = moyinColors()
    // 浅色走「纸感编号列表」：编号 + 方形封面 + 底部实线；
    // 深色走「卡片流」：无编号、圆角封面、靠间距分组
    val paper = c.titleSerif
    Column(modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(vertical = if (paper) 11.wdp else 8.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (paper && index != null) {
            MoyinNum(index, Modifier.width(24.wdp))
        }
        CoverArt(
            coverUrl = track.coverUrl,
            modifier = Modifier.size(if (paper) 52.wdp else 48.wdp),
            cornerRadius = if (paper) 2.wdp else 7.wdp
        )
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                color = c.t1,
                fontSize = 14.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = c.t3,
                fontSize = 11.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (track.durationMs > 0) {
            Text(
                text = formatDuration(track.durationMs),
                color = c.t3,
                fontSize = 10.wsp
            )
        }
        if (onDownload != null && track.source != "local") {
            Spacer(Modifier.width(4.wdp))
            Box(
                Modifier
                    .size(40.wdp)
                    .clip(RoundedCornerShape(12.wdp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { if (!downloaded) onDownload(track) },
                contentAlignment = Alignment.Center
            ) {
                MoyinIcon(
                    icon = IconDownload,
                    tint = if (downloaded) c.accent else c.t2,
                    size = 19.wdp
                )
            }
        }
    }
        if (paper) MoyinDivider()
    }
}

@Composable
private fun RecommendedCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .width(140.wdp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        CoverArt(
            coverUrl = track.coverUrl,
            modifier = Modifier.size(140.wdp),
            cornerRadius = 12.wdp,
            qualityTag = if (track.qualityLabel.isNotEmpty()) track.qualityLabel else null
        )
        Spacer(Modifier.height(8.wdp))
        Text(
            text = track.name,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = c.t3,
            fontSize = 11.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "早上好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
}

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

internal val Color0xFF262A4E = androidx.compose.ui.graphics.Color(0xFF262A4E)
internal val Color0xFF1A1D33 = androidx.compose.ui.graphics.Color(0xFF1A1D33)
internal val Color0xFFE4E3FC = androidx.compose.ui.graphics.Color(0xFFE4E3FC)
internal val Color0xFFDCDBF8 = androidx.compose.ui.graphics.Color(0xFFDCDBF8)
internal val Color0xFFC9C8FF = androidx.compose.ui.graphics.Color(0xFFC9C8FF)
internal val Color0xFF8F8ED6 = androidx.compose.ui.graphics.Color(0xFF8F8ED6)
internal val Color0xFF3F3CB8 = androidx.compose.ui.graphics.Color(0xFF3F3CB8)
internal val Color0xFF6F6CC9 = androidx.compose.ui.graphics.Color(0xFF6F6CC9)

// 设计稿固定色（不随主题变化的部分）
internal val MoyColor0DFFFFFF = UiColor(0x0DFFFFFF)
internal val MoyColor09000000 = UiColor(0x09000000)
internal val MoyColor262A4E = UiColor(0xFF262A4E)
internal val MoyColor1A1D33 = UiColor(0xFF1A1D33)
internal val MoyColorE4E3FC = UiColor(0xFFE4E3FC)
internal val MoyColorDCDBF8 = UiColor(0xFFDCDBF8)
internal val MoyColorC9C8FF = UiColor(0xFFC9C8FF)
internal val MoyColor8F8ED6 = UiColor(0xFF8F8ED6)
internal val MoyColor3F3CB8 = UiColor(0xFF3F3CB8)
internal val MoyColor6F6CC9 = UiColor(0xFF6F6CC9)

// ---------------- 发现页卡片 ----------------

/** 排行榜卡片（酷我原生，点进去必定能播） */
@Composable
private fun ChartCard(section: ChartSection, onClick: () -> Unit) {
    val c = moyinColors()
    Column(
        modifier = Modifier
            .width(150.wdp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        Box(
            Modifier
                .size(150.wdp)
                .clip(RoundedCornerShape(c.radiusCard))
                .then(
                    if (!section.chart.coverUrl.isNullOrEmpty()) Modifier
                    else Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(c.coverGrad))
                )
        ) {
            if (!section.chart.coverUrl.isNullOrEmpty()) {
                CoverArt(
                    coverUrl = section.chart.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = coverRadiusOf(c)
                )
            }
        }
        Spacer(Modifier.height(8.wdp))
        Text(
            section.chart.name,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.wdp))
        Text(
            "${section.tracks.size} 首",
            color = c.t3,
            fontSize = 11.wsp
        )
    }
}

/** 推荐歌单卡片（网易云推荐位，播放时严格校验后回退酷我） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: PlaylistBrief,
    onClick: () -> Unit,
    /** 已收藏时右上角显示心形角标。null = 不显示收藏状态（老调用点兼容） */
    favorited: Boolean? = null,
    /** 长按收藏 / 取消收藏。null = 不支持长按 */
    onLongPress: (() -> Unit)? = null
) {
    val c = moyinColors()
    Column(
        modifier = Modifier
            .width(132.wdp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick() },
                onLongClick = onLongPress
            )
    ) {
        Box(
            Modifier
                .size(132.wdp)
                .clip(RoundedCornerShape(c.radiusCard))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(c.coverGrad))
        ) {
            if (!playlist.coverUrl.isNullOrEmpty()) {
                CoverArt(
                    coverUrl = playlist.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = coverRadiusOf(c)
                )
            }
            // 收藏角标：压在封面右下角，半透明黑底保证在任何封面图上都看得清
            if (favorited == true) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.wdp)
                        .size(22.wdp)
                        .clip(RoundedCornerShape(11.wdp))
                        .background(Color.Black.copy(alpha = 0.42f)),
                    contentAlignment = Alignment.Center
                ) {
                    MoyinIcon(icon = IconHeart, tint = Color.White, size = 12.wdp)
                }
            }
        }
        Spacer(Modifier.height(8.wdp))
        Text(
            playlist.name,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (playlist.trackCount > 0) {
            Spacer(Modifier.height(2.wdp))
            Text("${playlist.trackCount} 首", color = c.t3, fontSize = 11.wsp)
        }
    }
}

/** 音乐人（圆形头像） */
@Composable
private fun ArtistCircle(artist: ArtistBrief, onClick: () -> Unit) {
    val c = moyinColors()
    Column(
        modifier = Modifier
            .width(76.wdp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(68.wdp)
                .clip(RoundedCornerShape(99.dp))
                .background(c.s2)
        ) {
            if (!artist.picUrl.isNullOrEmpty()) {
                CoverArt(
                    coverUrl = artist.picUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 99.wdp
                )
            }
        }
        Spacer(Modifier.height(8.wdp))
        Text(
            artist.name,
            color = c.t2,
            fontSize = 12.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
