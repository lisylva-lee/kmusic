package com.kmusic.apkhd.ui.screens

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.kmusic.apkhd.ui.icons.IconChevronRight
import com.kmusic.apkhd.ui.icons.IconVolume
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.model.LyricLine
import com.kmusic.apkhd.model.PlayMode
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.ui.components.CoverArt
import com.kmusic.apkhd.ui.components.VinylCover
import com.kmusic.apkhd.audio.SpectrumProbe
import com.kmusic.apkhd.ui.AudioInfo
import com.kmusic.apkhd.ui.components.EqBandSliders
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconCheck
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.icons.IconPlayLoop
import com.kmusic.apkhd.ui.icons.IconPlayOrder
import com.kmusic.apkhd.ui.icons.IconPlayShuffle
import com.kmusic.apkhd.ui.icons.IconPlaySingle
import com.kmusic.apkhd.ui.icons.IconPlus
import com.kmusic.apkhd.ui.icons.IconInfo
import com.kmusic.apkhd.ui.icons.IconLyrics
import com.kmusic.apkhd.ui.icons.IconMore
import com.kmusic.apkhd.ui.icons.IconNext
import com.kmusic.apkhd.ui.icons.IconPause
import com.kmusic.apkhd.ui.icons.IconPlay
import com.kmusic.apkhd.ui.icons.IconPrev
import com.kmusic.apkhd.ui.icons.IconEqualizer
import com.kmusic.apkhd.ui.icons.IconQueue
import com.kmusic.apkhd.ui.icons.IconVolume
import com.kmusic.apkhd.data.LYRIC_OFFSET_DEFAULT_MS
import com.kmusic.apkhd.ui.lyricLineSp
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.safeBottomPadding
import com.kmusic.apkhd.ui.theme.sheetBottomPadding
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.theme.wspSp
import com.kmusic.apkhd.ui.theme.window
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import com.kmusic.apkhd.ui.icons.IconClose
import com.kmusic.apkhd.model.UserPlaylist
import com.kmusic.apkhd.ui.icons.IconPlaylist

/**
 * 播放页。
 *
 * 结构：**一套布局**，中央方块区域在「封面」和「歌词」之间 Crossfade 切换。
 * 这样歌词与封面天然同位置同大小，不会出现两套布局对不齐的问题。
 * 点中央方块切到歌词，再点一次回封面。
 */
@Composable
fun PlayerScreen(
    track: Track?,
    isPlaying: Boolean,
    /** 播放进度（0~1）。用 lambda 提供：进度每 100ms 变一次，只有真正读它的进度条才重组 */
    progressProvider: () -> Float,
    /** 播放位置（毫秒）。同样用 lambda：读在顶层会把封面 / 歌词 / 按钮全带着重组 */
    positionProvider: () -> Long,
    durationMs: Long,
    lyrics: List<LyricLine>?,
    currentLyricIndex: Int,
    loadingLyrics: Boolean = false,
    queue: List<Track> = emptyList(),
    currentQueueIndex: Int = -1,
    onSeek: (Float) -> Unit = {},
    onQueueSelect: (Int) -> Unit = {},
    onQueueRemove: (Int) -> Unit = {},
    onQueueMove: (Int, Int) -> Unit = { _, _ -> },
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onBack: () -> Unit,
    playMode: PlayMode = PlayMode.ORDER,
    isFavorite: Boolean = false,
    qualityTier: String = "master",
    onPlayModeChange: (PlayMode) -> Unit = {},
    onQualityChange: (String) -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onDownload: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    lyricOffsetMs: Long = 0L,
    onNudgeLyricOffset: (Long) -> Unit = {},
    onResetLyricOffset: () -> Unit = {},
    /** 歌词字号倍率（0.8~1.8）*/
    lyricFontScale: Float = 1.0f,
    onNudgeLyricFontScale: (Float) -> Unit = {},
    onResetLyricFontScale: () -> Unit = {},
    onReportWrongLyric: (() -> Unit)? = null,
    // 音效：均衡器 + 系统增强 + 睡眠定时。直接放在播放页，不藏在设置里
    eqEnabled: Boolean = false,
    eqAvailable: Boolean = false,
    eqPresets: List<String> = emptyList(),
    eqPreset: Int = 0,
    /** 各频段增益（mB），空表表示设备没给均衡器频段 */
    eqBands: List<Int> = emptyList(),
    /** 各频段中心频率（Hz） */
    eqFreqs: List<Int> = emptyList(),
    /** 增益可调范围 [min, max] mB */
    eqRange: IntArray = intArrayOf(-1500, 1500),
    onEqEnabledChange: (Boolean) -> Unit = {},
    onEqPresetChange: (Int) -> Unit = {},
    onRefreshEq: () -> Unit = {},
    onEqBandChange: (Int, Int) -> Unit = { _, _ -> },
    fxBass: Int = 0,
    onFxBassChange: (Int) -> Unit = {},
    fxVirtual: Int = 0,
    onFxVirtualChange: (Int) -> Unit = {},
    fxLoudness: Int = 0,
    onFxLoudnessChange: (Int) -> Unit = {},
    fxReverb: Int = -1,
    onFxReverbChange: (Int) -> Unit = {},
    fxBassAvailable: Boolean = false,
    fxVirtualAvailable: Boolean = false,
    fxLoudnessAvailable: Boolean = false,
    fxReverbAvailable: Boolean = false,
    /** 有项目是因为 effect 池被占满（而不是本机没有）才不可用 —— 可重挂恢复 */
    fxStarved: Boolean = false,
    eqStarved: Boolean = false,
    /** 正在播 DSD（音效面板据此提示「均衡器已自动旁路」） */
    dsdPlaying: Boolean = false,
    /** DSD 倍率标签 */
    dsdLabel: String? = null,
    /** 用户是否开启了 DSD 自动旁路 EQ */
    dsdBypassEq: Boolean = true,
    fxBassStarved: Boolean = false,
    fxVirtualStarved: Boolean = false,
    fxLoudnessStarved: Boolean = false,
    fxReverbStarved: Boolean = false,
    fxDynamicsStarved: Boolean = false,
    onReattachFx: () -> Unit = {},
    /** 动态范围处理：0 关 / 1 轻 / 2 中 / 3 强 */
    fxDynamics: Int = 0,
    onFxDynamicsChange: (Int) -> Unit = {},
    fxDynamicsAvailable: Boolean = false,
    /** 省电播放（offload）已开：音效全部不可用 */
    audioOffload: Boolean = false,
    // 音频信息 + AI 导赏
    audioInfo: AudioInfo? = null,
    /** 响度归一当前增益（dB）；0 表示没归一 */
    loudnessGainDb: Float = 0f,
    /** 频谱检测 */
    probeResult: SpectrumProbe.Result? = null,
    probeRunning: Boolean = false,
    probeError: String? = null,
    onProbe: () -> Unit = {},
    aiConfigured: Boolean = false,
    aiText: String = "",
    aiLoading: Boolean = false,
    aiError: String? = null,
    // 歌词翻译
    lyricTranslation: Map<Int, String> = emptyMap(),
    translating: Boolean = false,
    hasTranslation: Boolean = false,
    onTranslateLyrics: () -> Unit = {},
    onRequestAi: () -> Unit = {},
    onCancelAi: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    sleepTotalMin: Int = 0,
    sleepRemainingSec: Int = 0,
    onSleepChange: (Int) -> Unit = {},
    playlists: List<UserPlaylist> = emptyList(),
    onAddToPlaylist: (String) -> Unit = {},
    onCreatePlaylistWithTrack: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    // 封面解码要按目标尺寸限制，Builder 需要 Context
    val context = LocalContext.current
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showEffects by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // 自建歌单：⋯ → 加入歌单 →（可选）新建
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylist by remember { mutableStateOf(false) }

    // 换歌时回到封面态
    LaunchedEffect(track?.id) { showLyrics = false }

    // ⚠️ 播放页是覆盖层（盖在首页/列表之上），但根布局原来只有背景色、不参与命中测试：
    // Compose 会继续往下层找可点击元素 —— 用户在黑胶与标题/控制栏之间的空白处点一下，
    // 事件就穿透到下面的歌曲列表，点到哪首就换哪首（表现像"看不见的按钮"）。
    //
    // 修法：**根 Box 自己接 clickable**（不是加一个兄弟层 —— 兄弟层在最底层会被上层的
    // Column 盖住，命中测试走不到它）。根节点自己的 pointerInput 在冒泡链的末端，
    // 子元素（黑胶/按钮/进度条）先拿到事件，没人消费的才落到这里被吞掉。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { /* 空白区点击：吞掉，不穿透到下层 */ }
    ) {
        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无播放", color = c.t3, fontSize = 14.wsp)
            }
            return@Box
        }

        // ── 沉浸背景：封面放大 + 高斯模糊 + 压暗 ──────────────────────────
        //
        // 纯色底 + 一个方形封面是十年前的播放器长相。把封面本身铺满整屏，
        // 画面就跟着这首歌走色 —— Apple Music / 主流播放器都是这么做的。
        //
        // 三个必须做的处理：
        // 1. **先放大 1.3 倍再模糊**：不放大，模糊边缘会露出秃边（画面四周发虚发黑）
        // 2. **盖一层蒙版**：不盖的话歌词和标题糊在花背景里，读不清
        // 3. **低版本降级**：Modifier.blur 官方注明只在 Android 12+ 生效，低版本会被静默忽略，
        //    那时把蒙版加重，退化成「低调的纯色氛围」，而不是变成一张刺眼的花图
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // 只在深色（A）铺封面模糊。浅色（B）是纸面设计，模糊一层会变成脏灰，破坏纸感
        if (c.isDark && !track.coverUrl.isNullOrEmpty()) {
            // 背景图铺满全屏后又模糊 64dp，原图分辨率毫无意义：
            // 不限制解码尺寸的话，4K 封面会按原尺寸解出几十 MB 位图，每次换歌都白吃一次内存
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(track.coverUrl)
                        .size(720)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.3f
                            scaleY = 1.3f
                        }
                        .then(
                            if (canBlur) Modifier.blur(64.wdp, BlurredEdgeTreatment.Unbounded)
                            else Modifier
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                when {
                                    // 不能模糊 → 直接压成纯色，不留封面残影
                                    !canBlur && c.isDark -> listOf(Color(0xF00B0E14), Color(0xF00B0E14))
                                    !canBlur -> listOf(Color(0xF0F5F6FA), Color(0xF0F5F6FA))
                                    // 能模糊 → 顶部几乎不压（让封面色彩和纹理透出来），
                                    // 底部压深（放控制按钮，必须有对比度）
                                    c.isDark -> listOf(Color(0x33000000), Color(0xCC000000))
                                    else -> listOf(Color(0x55FFFFFF), Color(0xE6FFFFFF))
                                }
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 背景已经铺到屏幕边缘（沉浸式），内容必须自己让开状态栏和手势条，
                // 否则「正在播放」会被状态栏压住、底部工具栏会被手势条盖掉。
                .statusBarsPadding()
                .safeBottomPadding()
                .padding(horizontal = 16.wdp)
        ) {
            // ===== 宽屏（平板 / 折叠屏展开）：跨两栏全局顶栏 + 左 40% 信息 / 右 60% 歌词 =====
            // 只在“宽屏 + 横屏”时启用：竖屏平板（如 800x1280dp）单列更合理，
            // 否则左栏会被拉成又窄又高的长条，封面下方全是空白。
            if (window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380) {
                Spacer(Modifier.height(4.wdp))
                // 全局顶栏：一条横跨两栏，不拆成两份
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavIcon(IconBack) { onBack() }
                    Text("正在播放", color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
                    NavIcon(IconMore) { showMode = false; showMore = true }
                }
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.wdp, bottom = 8.wdp)
                ) {
                    // ── 宽屏重排：左栏只放黑胶（撑满高度），右栏上歌词、下信息与控制 ──
                    // 之前封面和文字/控制挤在同一栏，封面只剩 ~250dp，盘径被压到屏高 27%。
                    val compactH = window.heightDp < 520
                    BoxWithConstraints(
                        Modifier
                            .weight(if (compactH) 0.42f else 0.46f)
                            .fillMaxHeight()
                            .padding(end = 14.wdp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 上限 460dp：再大盘心会贴到唱臂支点
                        val coverSide = minOf(minOf(maxWidth, maxHeight), 460.wdp)
                            .coerceAtLeast(96.wdp)
                        Box(
                            Modifier
                                .size(coverSide)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { showQuality = true }
                        ) {
                            VinylCover(
                                coverUrl = track.coverUrl,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize(),
                                showTonearm = coverSide >= 150.dp
                            )
                        }
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = LocalDensity.current.density * (if (compactH) 0.78f else 1f),
                            fontScale = LocalDensity.current.fontScale
                        )
                    ) {
                        Column(
                            Modifier
                                .weight(if (compactH) 0.58f else 0.54f)
                                .fillMaxHeight()
                        ) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                LyricsPane(
                                    lyrics = lyrics,
                                    currentIndex = currentLyricIndex,
                                    loading = loadingLyrics,
                                    offsetMs = lyricOffsetMs,
                                    positionProvider = positionProvider,
                                    fontScale = lyricFontScale,
                                    onSeekToMs = { ms ->
                                        if (durationMs > 0) onSeek((ms.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f))
                                    },
                                    translations = lyricTranslation,
                                    translating = translating,
                                    hasTranslation = hasTranslation,
                                    onTranslate = onTranslateLyrics,
                                    aiConfigured = aiConfigured,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(10.wdp))
                            Text(
                                text = track.name,
                                color = c.t1,
                                fontSize = (if (c.titleSerif) 23 else 20).wsp,
                                fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
                                fontWeight = if (c.titleSerif) FontWeight.Bold else FontWeight.SemiBold,
                                letterSpacing = if (c.titleSerif) 0.sp else (-0.017).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!compactH) {
                                Spacer(Modifier.height(5.wdp))
                                Text(
                                    text = track.artist + if (track.album.isNotEmpty()) " · ${track.album}" else "",
                                    color = c.t2,
                                    fontSize = 12.wsp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(10.wdp))
                            SeekBar(progressProvider = progressProvider, onSeek = onSeek)
                            Spacer(Modifier.height(6.wdp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                ElapsedTimeText(positionProvider = positionProvider, color = c.t3)
                                Text(formatMs(durationMs), color = c.t3, fontSize = 10.wsp)
                            }
                            Spacer(Modifier.height(8.wdp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CtrlButton(
                                    icon = playModeIcon(playMode),
                                    size = 40.wdp,
                                    iconSize = 16.wdp,
                                    tint = if (playMode == PlayMode.ORDER) null else c.accent
                                ) { showMode = true }
                                CtrlButton(icon = IconPrev, size = 46.wdp, iconSize = 16.wdp) { onPrev() }
                                CtrlButton(
                                    icon = if (isPlaying) IconPause else IconPlay,
                                    size = 54.wdp,
                                    iconSize = 16.dp,
                                    emphasized = true
                                ) { onTogglePlay() }
                                CtrlButton(icon = IconNext, size = 46.wdp, iconSize = 16.wdp) { onNext() }
                                CtrlButton(
                                    icon = IconHeart,
                                    size = 40.wdp,
                                    iconSize = 16.wdp,
                                    tint = if (isFavorite) c.accent else null
                                ) { onToggleFavorite() }
                            }
                            Spacer(Modifier.height(8.wdp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                ToolItem(IconQueue, if (queue.isEmpty()) "队列" else "队列 ${queue.size}") { showQueue = true }
                                ToolItem(IconEqualizer, "音效") { showEffects = true }
                                ToolItem(IconInfo, "信息") { showInfo = true }
                            }
                        }
                    }
                }
            } else {
            Spacer(Modifier.height(4.wdp))
            // 导航
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIcon(IconBack) { onBack() }
                Text("正在播放", color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
                NavIcon(IconMore) { showMode = false; showMore = true }
            }

            // 中央方块：封面 ⇄ 歌词（同位置同大小）
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // 封面必须同时受宽高约束：手机横屏（高 < 宽）时按最短边收缩，
                // 否则正方形会超出容器、压住下面的歌名/进度/按钮（表现为"图片和字重合"）
                val coverSide = minOf(maxWidth, maxHeight).coerceAtLeast(96.wdp)
                Box(
                    Modifier
                        .size(coverSide)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showLyrics = !showLyrics }
                ) {
                    Crossfade(
                        targetState = showLyrics,
                        animationSpec = tween(220),
                        label = "coverLyrics"
                    ) { lyricsMode ->
                        if (lyricsMode) {
                            LyricsPane(
                                lyrics = lyrics,
                                currentIndex = currentLyricIndex,
                                loading = loadingLyrics,
                                offsetMs = lyricOffsetMs,
                                positionProvider = positionProvider,
                                fontScale = lyricFontScale,
                                onSeekToMs = { ms ->
                                    if (durationMs > 0) onSeek((ms.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f))
                                },
                                translations = lyricTranslation,
                                translating = translating,
                                hasTranslation = hasTranslation,
                                onTranslate = onTranslateLyrics,
                                aiConfigured = aiConfigured,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            VinylCover(
                                coverUrl = track.coverUrl,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // 标题
            Text(
                text = track.name,
                color = c.t1,
                // 深色（A）：无衬线收字距，现代播放器；浅色（B）：衬线，像唱片内页标题
                fontSize = (if (c.titleSerif) 25 else 21).wsp,
                fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
                fontWeight = if (c.titleSerif) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = if (c.titleSerif) 0.sp else (-0.017).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.wdp))
            Text(
                text = track.artist + if (track.album.isNotEmpty()) " · ${track.album}" else "",
                color = c.t2,
                fontSize = 13.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.wdp))
            SeekBar(progressProvider = progressProvider, onSeek = onSeek)
            Spacer(Modifier.height(16.wdp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ElapsedTimeText(positionProvider = positionProvider, color = c.t3)
                Text(formatMs(durationMs), color = c.t3, fontSize = 10.wsp)
            }

            Spacer(Modifier.height(18.wdp))
            // 控制排：深浅主题统一只留按钮，不再加底板。
            // 深色原来那层"玻璃胶囊"在浅色下没有对应物，两套主题像两个播放器；
            // 去掉之后按钮直接落在封面光晕上，靠间距分组，反而更干净。
            Row(
                // 两端留 6dp：车机屏有圆角/黑边，最外侧那颗（播放模式）贴边会被切掉
                Modifier.fillMaxWidth().padding(horizontal = 6.wdp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CtrlButton(
                    icon = playModeIcon(playMode),
                    size = 40.wdp,
                    iconSize = 16.wdp,
                    tint = if (playMode == PlayMode.ORDER) null else c.accent
                ) { showMode = true }
                CtrlButton(icon = IconPrev, size = 48.wdp, iconSize = 16.wdp) { onPrev() }
                CtrlButton(
                    icon = if (isPlaying) IconPause else IconPlay,
                    size = 56.wdp,
                    iconSize = 16.dp,
                    emphasized = true
                ) { onTogglePlay() }
                CtrlButton(icon = IconNext, size = 48.wdp, iconSize = 16.wdp) { onNext() }
                CtrlButton(
                    icon = IconHeart,
                    size = 40.wdp,
                    iconSize = 16.wdp,
                    tint = if (isFavorite) c.accent else null
                ) { onToggleFavorite() }
            }

            Spacer(Modifier.height(24.wdp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ToolItem(IconLyrics, if (showLyrics) "封面" else "歌词") { showLyrics = !showLyrics }
                ToolItem(IconQueue, if (queue.isEmpty()) "队列" else "队列 ${queue.size}") { showQueue = true }
                ToolItem(IconEqualizer, "音效") { showEffects = true }
                ToolItem(IconInfo, "信息") { showInfo = true }
            }
            Spacer(Modifier.height(16.wdp))
            }
        }

        // 播放模式
        if (showMode) {
            OptionSheet(
                title = "播放模式",
                options = PlayMode.entries.map { it.name to it.label },
                selected = playMode.name,
                onPick = { onPlayModeChange(PlayMode.of(it)); showMode = false },
                onDismiss = { showMode = false }
            )
        }

        // 音质
        if (showQuality) {
            OptionSheet(
                title = "音质",
                options = listOf(
                    "master" to "臻品母带 · 24bit/192kHz",
                    "hires" to "无损 FLAC",
                    "high" to "极高品 320k",
                    "standard" to "标准 128k"
                ),
                selected = qualityTier,
                onPick = { onQualityChange(it); showQuality = false },
                onDismiss = { showQuality = false }
            )
        }

        // 更多（下载 / 加入队列）
        if (showPlaylistPicker) {
            AddToPlaylistSheet(
                playlists = playlists,
                onPick = { pid -> onAddToPlaylist(pid); showPlaylistPicker = false },
                onCreateNew = { showPlaylistPicker = false; showNewPlaylist = true },
                onDismiss = { showPlaylistPicker = false }
            )
        }
        if (showNewPlaylist) {
            NewPlaylistDialog(
                onDismiss = { showNewPlaylist = false },
                onConfirm = { n -> onCreatePlaylistWithTrack(n); showNewPlaylist = false }
            )
        }

        if (showMore) {
            MoreSheet(
                onDownload = { onDownload(); showMore = false },
                onAddToQueue = { onAddToQueue(); showMore = false },
                onAddToPlaylist = { showMore = false; showPlaylistPicker = true },
                qualityLabel = qualityName(qualityTier),
                onQualityClick = { showMore = false; showQuality = true },
                lyricOffsetMs = lyricOffsetMs,
                onNudgeOffset = onNudgeLyricOffset,
                onResetOffset = onResetLyricOffset,
                fontScale = lyricFontScale,
                onNudgeFontScale = onNudgeLyricFontScale,
                onResetFontScale = onResetLyricFontScale,
                onReportWrongLyric = onReportWrongLyric?.let { cb -> { showMore = false; cb() } },
                aiConfigured = aiConfigured,
                translating = translating,
                hasTranslation = hasTranslation,
                onTranslate = onTranslateLyrics,
                onDismiss = { showMore = false }
            )
        }

        // 队列面板（覆盖层）
        // 音效面板：均衡器 + 系统增强 + 睡眠定时，全部在这一页搞定
        if (showEffects) {
            EffectSheet(
                eqEnabled = eqEnabled,
                eqAvailable = eqAvailable,
                eqPresets = eqPresets,
                eqPreset = eqPreset,
                eqBands = eqBands,
                eqFreqs = eqFreqs,
                eqRange = eqRange,
                onEqEnabledChange = onEqEnabledChange,
                onEqPresetChange = onEqPresetChange,
                onEqBandChange = onEqBandChange,
                onRefreshEq = onRefreshEq,
                fxBass = fxBass, onFxBassChange = onFxBassChange,
                fxVirtual = fxVirtual, onFxVirtualChange = onFxVirtualChange,
                fxLoudness = fxLoudness, onFxLoudnessChange = onFxLoudnessChange,
                fxReverb = fxReverb, onFxReverbChange = onFxReverbChange,
                fxBassAvailable = fxBassAvailable,
                fxVirtualAvailable = fxVirtualAvailable,
                fxLoudnessAvailable = fxLoudnessAvailable,
                fxReverbAvailable = fxReverbAvailable,
                fxStarved = fxStarved,
                eqStarved = eqStarved,
                fxBassStarved = fxBassStarved,
                fxVirtualStarved = fxVirtualStarved,
                fxLoudnessStarved = fxLoudnessStarved,
                fxReverbStarved = fxReverbStarved,
                fxDynamicsStarved = fxDynamicsStarved,
                onReattachFx = onReattachFx,
                fxDynamics = fxDynamics,
                onFxDynamicsChange = onFxDynamicsChange,
                fxDynamicsAvailable = fxDynamicsAvailable,
                audioOffload = audioOffload,
                dsdPlaying = dsdPlaying,
                dsdLabel = dsdLabel,
                dsdBypassEq = dsdBypassEq,
                sleepTotalMin = sleepTotalMin,
                sleepRemainingSec = sleepRemainingSec,
                onSleepChange = onSleepChange,
                onDismiss = { showEffects = false }
            )
        }

        if (showInfo) {
            InfoSheet(
                info = audioInfo,
                loudnessGainDb = loudnessGainDb,
                probeResult = probeResult,
                probeRunning = probeRunning,
                probeError = probeError,
                onProbe = onProbe,
                canProbe = track?.source == "local",
                aiConfigured = aiConfigured,
                aiText = aiText,
                aiLoading = aiLoading,
                aiError = aiError,
                onRequestAi = onRequestAi,
                onCancelAi = onCancelAi,
                onOpenAiSettings = { showInfo = false; onOpenAiSettings() },
                onDismiss = { showInfo = false; onCancelAi() }
            )
        }

        if (showQueue) {
            QueueSheet(
                queue = queue,
                currentIndex = currentQueueIndex,
                onSelect = { i -> onQueueSelect(i); showQueue = false },
                onRemove = onQueueRemove,
                onMove = onQueueMove,
                onDismiss = { showQueue = false }
            )
        }
    }
}

/**
 * 播放队列：长按拖动排序，右侧 ✕ 移除，点选直接切歌。
 *
 * 拖动用「跟手位移 + 松手换算成目标下标」的做法：
 * 拖动过程中只动视觉，松手才提交一次 onMove —— 这样播放列表不会被反复重建。
 */
@Composable
private fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    val rowH = 56.wdp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragging by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(400.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth().fillMaxHeight(0.66f)
                )
                .clip(
                    if (drawer) RoundedCornerShape(topStart = 18.wdp, bottomStart = 18.wdp)
                    else RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp)
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                // 吃掉点击，避免穿透到遮罩把面板关掉
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                // 底部让位：车机底栏不上报 inset，「随机播放」那一行会被压在图标栏下面
                .sheetBottomPadding(!drawer)
                .padding(top = 14.wdp, bottom = 8.wdp)
        ) {
            Text(
                "播放队列 · ${queue.size} 首",
                color = c.t1,
                fontSize = 14.wsp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(4.wdp))
            Text(
                "长按拖动排序",
                color = c.t3,
                fontSize = 10.wsp,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(8.wdp))
            if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("队列是空的", color = c.t3, fontSize = 13.wsp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(queue, key = { _, t -> t.id }) { i, t ->
                        val on = i == currentIndex
                        val isDrag = dragging == i
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(rowH)
                                .zIndex(if (isDrag) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDrag) dragOffset else 0f
                                    // 拖动中的那一行浮起来，给一点层级感
                                    scaleX = if (isDrag) 1.02f else 1f
                                    scaleY = if (isDrag) 1.02f else 1f
                                }
                                .background(
                                    when {
                                        isDrag -> if (c.isDark) Color(0x33FFFFFF) else Color(0x14000000)
                                        else -> Color.Transparent
                                    }
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = { },
                                        onTap = { if (dragging < 0) onSelect(i) }
                                    )
                                }
                                .pointerInput(queue.size, i) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragging = i
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            val from = dragging
                                            val steps = (dragOffset / rowPx).roundToInt()
                                            dragging = -1
                                            dragOffset = 0f
                                            if (from >= 0 && steps != 0) {
                                                onMove(
                                                    from,
                                                    (from + steps).coerceIn(0, queue.lastIndex)
                                                )
                                            }
                                        },
                                        onDragCancel = {
                                            dragging = -1
                                            dragOffset = 0f
                                        }
                                    ) { _, drag -> dragOffset += drag.y }
                                }
                                .padding(horizontal = 18.wdp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                Column {
                                    Text(
                                        t.name,
                                        color = if (on) c.accent else c.t1,
                                        fontSize = 13.wsp,
                                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (t.artist.isNotEmpty()) {
                                        Text(
                                            t.artist,
                                            color = c.t3,
                                            fontSize = 11.wsp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (on) {
                                MoyinIcon(icon = IconPlay, tint = c.accent, size = 14.wdp)
                                Spacer(Modifier.width(6.wdp))
                            }
                            Box(
                                Modifier
                                    .size(36.wdp)
                                    .clip(RoundedCornerShape(10.wdp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { onRemove(i) },
                                contentAlignment = Alignment.Center
                            ) {
                                MoyinIcon(icon = IconClose, tint = c.t3, size = 14.wdp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 歌词内容：与封面同尺寸的方块内滚动歌词。
 *
 * 偏移 / 字号 / 换源都收进了「更多」面板（播放页 ⋯ → 歌词），
 * 歌词区只负责唱，不再压两行调节条 —— 歌词本来就该占满这一块。
 */
@Composable
private fun LyricsPane(
    lyrics: List<LyricLine>?,
    currentIndex: Int,
    loading: Boolean,
    offsetMs: Long,
    /** 播放位置（毫秒）的提供者。用来算当前行唱到哪了（逐字扫光进度） */
    positionProvider: () -> Long = { 0L },
    /** 歌词字号倍率（0.8~1.8），在「更多 → 歌词」里调 */
    fontScale: Float = 1f,
    /** 行索引 → 译文。整篇都显示：只显示当前行的话，用户点了「译」几乎看不到变化 */
    translations: Map<Int, String> = emptyMap(),
    translating: Boolean = false,
    hasTranslation: Boolean = false,
    onTranslate: () -> Unit = {},
    aiConfigured: Boolean = false,
    /** 用户拖动歌词松手后跳转到对应时间（毫秒）。null 则禁用拖动 seek */
    onSeekToMs: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 底部不再有偏移/字号条，歌词占满整栏
        val viewportH = maxHeight.coerceAtLeast(120.wdp)
        when {
            loading -> Text("歌词加载中…", color = c.t3, fontSize = 13.wsp)
            lyrics.isNullOrEmpty() -> Text("暂无歌词", color = c.t3, fontSize = 13.wsp)
            else -> Column(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                // 只有「手指真的在拖」才算用户滚动；animateScrollToItem 的自动滚动不会置 true
                val isDragged by listState.interactionSource.collectIsDraggedAsState()
                // 最近一次「程序自动滚动」的时间：1.5 秒内不把它当成用户拖动去 seek
                var lastProgrammaticAt by remember { mutableStateOf(0L) }
                // 用户最后一次手动滚动的时间：3 秒内不让自动跟随把视图抢回去
                var userScrollAt by remember { mutableStateOf(0L) }

                // ── 当前行的「唱到哪了」进度（0~1），用于逐字扫光 ──────────────
                //
                // 拆成一个空渲染的子 Composable（LyricSweepDriver）：这里要读 positionProvider()，
                // 而播放位置每 100ms 变一次。读在 LyricsPane 这一层，整个歌词列表会跟着重组；
                // 关在它自己里面，重组范围就只有它一个。
                LyricSweepDriver(
                    positionProvider = positionProvider,
                    lyrics = lyrics,
                    currentIndex = currentIndex,
                    offsetMs = offsetMs
                )

                LaunchedEffect(currentIndex) {
                    // 正在拖动、或刚拖完 3 秒内：都不抢回视图（慢拖超过 3 秒也不会被拉回）
                    if (isDragged) return@LaunchedEffect
                    if (System.currentTimeMillis() - userScrollAt < 3000) return@LaunchedEffect
                    if (currentIndex in lyrics.indices) {
                        // 上下 contentPadding 各留 40% 视口高，滚到当前行即视觉居中
                        lastProgrammaticAt = System.currentTimeMillis()
                        runCatching { listState.animateScrollToItem(currentIndex) }
                    }
                }
                // 用户手动拖动歌词：停手 0.3s 后，取视口正中那一行的时间跳转（方案 A）
                if (onSeekToMs != null) {
                    LaunchedEffect(isDragged) {
                        if (isDragged) return@LaunchedEffect
                        // 手指松开：记录时间，随后 3 秒内自动跟随不抢视图
                        userScrollAt = System.currentTimeMillis()
                        // 自动滚动刚结束的不算用户拖动
                        if (System.currentTimeMillis() - lastProgrammaticAt < 1500) return@LaunchedEffect
                        kotlinx.coroutines.delay(300)
                        if (isDragged) return@LaunchedEffect
                        val info = listState.layoutInfo
                        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                        val centerItem = info.visibleItemsInfo
                            .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }
                        val line = centerItem?.index?.let { lyrics.getOrNull(it) } ?: return@LaunchedEffect
                        if (line.timeMs > 0) onSeekToMs(line.timeMs)
                    }
                }
                LazyColumn(
                    state = listState,
                    // 歌词占满剩余高度；上下各留 40% 视口高，当前行才能停在正中
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 16.wdp,
                        end = 16.wdp,
                        // 让当前行「中心」落在视口正中：行首位置 = 50% - 半个行高
                        // coerceAtLeast(0) 防负数：歌词区极矮时负 padding 会让 Compose 直接抛异常（横屏闪退）
                        top = (viewportH * 0.5f - 16.wdp).coerceAtLeast(0.wdp),
                        bottom = viewportH * 0.5f
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.wdp)
                ) {
                    itemsIndexed(
                        lyrics,
                        // 稳定 key：歌词文本刷新 / 翻译返回时行不会整体重建，
                        // 用「下标:时间轴」——同一份歌词里两行时间相同也不会撞（下标兜底）
                        key = { i, line -> "$i:${line.timeMs}" }
                    ) { i, line ->
                        val on = i == currentIndex
                        // 字号走倍率：当前行 13sp / 相邻与远处 10sp，倍率同时作用于两者。
                        // 计算在 [lyricLineSp] 里（单测覆盖，那里面有括号位置的坑）。
                        // ⚠️ wsp 只接了 Int，所以 lyricLineSp 返回 Int。
                        val sp = lyricLineSp(on, fontScale).wsp
                        // 深色（A）：无衬线、中等字重，现代歌词；浅色（B）：衬线、粗体，唱片内页感
                        val lyricFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif
                        val lyricSpacing = if (c.titleSerif) 0.4.sp else 0.sp
                        val fw = if (on) {
                            if (c.titleSerif) FontWeight.Bold else FontWeight.Medium
                        } else FontWeight.Normal
                        val dim = when {
                            on -> c.t1
                            kotlin.math.abs(i - currentIndex) <= 1 -> c.t2
                            else -> c.t3
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 当前行直接 t1（近黑）：切行瞬间整行就是最深。
                            // 不再做「扫光」——扫光的本质是整句唱完才全黑，看起来就是高亮延迟。
                            Text(
                                text = line.text,
                                color = dim,
                                fontSize = sp,
                                fontFamily = lyricFamily,
                                letterSpacing = lyricSpacing,
                                fontWeight = fw,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )

                        // 译文：每行只要有就显示，字号比原文小一档、颜色降一档
                        val tr = translations[i]
                        if (!tr.isNullOrBlank()) {
                            Spacer(Modifier.height(3.wdp))
                            Text(
                                text = tr,
                                color = c.t3,
                                fontSize = (sp.value * 0.8f).sp,
                                fontFamily = lyricFamily,
                                letterSpacing = lyricSpacing,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        }
                    }
                }

            }
        }
    }
}

/**
 * 扫光进度的驱动：**只负责把播放位置读在叶子层**，本身不渲染任何东西。
 *
 * 为什么单独拆一个：positionProvider() 每 100ms 变一次，直接读在 LyricsPane 里，
 * 整个歌词列表（上百行）会跟着重组；读在它自己里面，重组范围就只有它一个。
 *
 * 动画用 Animatable 手动驱动，而不是 animateFloatAsState：
 * 换行那一刻上一行已经唱到 1.0、新行进度是 0，共用一个补间会让动画从 1.0 倒回 0 ——
 * 用户看到的是「整条亮着往前消退，再从第一个字重新唱」，非常怪。
 * 换行必须瞬移到新行进度，只有同一行内部才做 400ms 平滑追赶。
 */
@Composable
private fun LyricSweepDriver(
    positionProvider: () -> Long,
    lyrics: List<LyricLine>,
    currentIndex: Int,
    offsetMs: Long
) {
    // 基准必须和 VM 判定 currentIndex 时用的一致：播放位置 + offsetMs
    val curLine = lyrics.getOrNull(currentIndex)
    val nextLine = lyrics.getOrNull(currentIndex + 1)
    val rawProgress = when {
        curLine == null -> 0f
        nextLine == null || nextLine.timeMs <= curLine.timeMs -> 1f
        else -> {
            // 同一帧只取一次，保证算进度时分子分母用的是同一个瞬间
            val pos = positionProvider() + offsetMs
            ((pos - curLine.timeMs).toFloat() /
                (nextLine.timeMs - curLine.timeMs)).coerceIn(0f, 1f)
        }
    }
    val sweep = remember { Animatable(0f) }
    var sweepLine by remember { mutableIntStateOf(-1) }
    LaunchedEffect(currentIndex, rawProgress) {
        if (sweepLine != currentIndex) {
            sweepLine = currentIndex
            sweep.snapTo(rawProgress)
        } else {
            sweep.animateTo(rawProgress, tween(durationMillis = 400, easing = LinearEasing))
        }
    }
}

@Composable
private fun LyricOffsetChip(label: String, onClick: () -> Unit) {
    val c = moyinColors()
    Text(
        text = label,
        color = c.t3,
        fontSize = 10.wsp,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 8.wdp, vertical = 5.wdp)
    )
}

/** 0 → 「歌词对齐」，否则显示 ±N.Ns */
private fun offsetLabel(ms: Long): String =
    if (ms == 0L) "歌词对齐" else String.format("%+.1fs", ms / 1000.0)

@Composable
private fun NavIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val c = moyinColors()
    // 底色 + 主色图标：播放页背景是封面模糊图，深浅不可控，
    // 纯透明圆 + 次要灰的图标在亮封面上会直接消失。
    Box(
        Modifier
            .size(44.wdp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (c.isDark) Color(0x33FFFFFF) else Color(0x14000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        MoyinIcon(icon = icon, tint = c.t1, size = 18.wdp)
    }
}

@Composable
private fun CtrlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    emphasized: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val c = moyinColors()
    // 主播放键做成实心强调色：整页只有这一个「实心块」，
    // 眼睛会自动落上去，比加光晕更有效（光晕会被外层玻璃胶囊的 clip 裁掉）。
    val bg = when {
        emphasized -> c.accentFill
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(99.dp))
            .then(
                if (emphasized) Modifier.background(bg)
                else Modifier.background(if (c.isDark) Color(0x0DFFFFFF) else Color(0x09000000))
            )
            .border(
                width = if (emphasized) 0.dp else 1.dp,
                color = if (c.isDark) Color(0x24FFFFFF) else Color(0x1A000000),
                shape = RoundedCornerShape(99.dp)
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        MoyinIcon(
            icon = icon,
            tint = when {
                emphasized -> c.onAccent
                tint != null -> tint
                else -> c.t2
            },
            size = iconSize
        )
    }
}

@Composable
private fun ToolItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(12.wdp)
    ) {
        MoyinIcon(icon = icon, tint = c.t3, size = 17.wdp)
        Spacer(Modifier.width(14.wdp))
        Text(label, color = c.t3, fontSize = 11.wsp)
    }
}

/**
 * 可拖动进度条。
 *
 * - 触摸区做到 24dp 高（视觉条只有 4dp），否则手指点不中
 * - 拖动中显示拖动位置，不被后台 100ms 轮询的播放位置覆盖
 * - 松手才 seek，拖动过程不 seek（避免频繁 seek 导致卡顿）
 */
@Composable
private fun SeekBar(
    /** 播放进度（0~1）的提供者。读在这一层，100ms 的轮询只会重组这条进度条 */
    progressProvider: () -> Float,
    onSeek: (Float) -> Unit
) {
    val c = moyinColors()
    var barWidth by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // 拖动中显示拖动位置，不被后台轮询的播放进度覆盖（dragging 非空时不会去读 provider）
    val shown = (dragging ?: progressProvider()).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(24.wdp)
            .onSizeChanged { barWidth = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / barWidth).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = (offset.x / barWidth).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging?.let { onSeek(it) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null }
                ) { change, _ ->
                    dragging = (change.position.x / barWidth).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 视觉条：深色（A）4dp 圆角粗条；浅色（B）1px 细线 + 菱形把手 —— 纸感全靠细线
        val paper = c.titleSerif
        val barH = if (paper) 1.wdp else 4.wdp
        val barShape = if (paper) RoundedCornerShape(0.dp) else RoundedCornerShape(12.wdp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(barH)
                .background(if (c.isDark) Color(0x2EFFFFFF) else Color(0x1F000000))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .background(
                        // 选了马卡龙时，浅色纸面也用糖纸色（accentFill 与 accent 不同即代表选了预设）
                        if (c.isDark || c.accentFill != c.accent) c.accentFill else c.lineStrong
                    )
            )
        }
        // 拖动手柄：深色白圆点；浅色砖红菱形
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(24.wdp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                Modifier
                    .size(if (dragging != null) 14.wdp else if (paper) 9.wdp else 10.wdp)
                    .graphicsLayer { if (paper) rotationZ = 45f }
                    .background(if (paper) c.accent else Color.White)
            )
        }
    }
}

private fun playModeIcon(mode: PlayMode) = when (mode) {
    PlayMode.ORDER -> IconPlayOrder
    PlayMode.LOOP -> IconPlayLoop
    PlayMode.SINGLE -> IconPlaySingle
    PlayMode.SHUFFLE -> IconPlayShuffle
}

internal fun qualityName(tier: String): String = when (tier) {
    "master" -> "母带"
    "hires" -> "无损"
    "high" -> "极高品"
    else -> "标准"
}

/** 通用底部选项面板（播放模式 / 音质共用） */
/**
 * 音效面板。
 *
 * 为什么放在播放页而不是设置页：调音效是「一边听一边拧」的动作，
 * 藏在设置里等于没有 —— 用户找不到（这条是用户实测反馈的）。
 *
 * 只显示设备真正支持的项：低端机常常只有均衡器，
 * 摆一个点了没反应的开关比不显示更糟。
 */
@Composable
private fun EffectSheet(
    eqEnabled: Boolean,
    eqAvailable: Boolean,
    eqPresets: List<String>,
    eqPreset: Int,
    eqBands: List<Int>,
    eqFreqs: List<Int>,
    eqRange: IntArray,
    onEqEnabledChange: (Boolean) -> Unit,
    onEqPresetChange: (Int) -> Unit,
    onEqBandChange: (Int, Int) -> Unit,
    onRefreshEq: () -> Unit,
    fxBass: Int, onFxBassChange: (Int) -> Unit,
    fxVirtual: Int, onFxVirtualChange: (Int) -> Unit,
    fxLoudness: Int, onFxLoudnessChange: (Int) -> Unit,
    fxReverb: Int, onFxReverbChange: (Int) -> Unit,
    fxBassAvailable: Boolean,
    fxVirtualAvailable: Boolean,
    fxLoudnessAvailable: Boolean,
    fxReverbAvailable: Boolean,
    fxStarved: Boolean,
    eqStarved: Boolean,
    fxBassStarved: Boolean,
    fxVirtualStarved: Boolean,
    fxLoudnessStarved: Boolean,
    fxReverbStarved: Boolean,
    fxDynamicsStarved: Boolean,
    onReattachFx: () -> Unit,
    fxDynamics: Int,
    onFxDynamicsChange: (Int) -> Unit,
    fxDynamicsAvailable: Boolean,
    audioOffload: Boolean,
    /** 正在播 DSD（音效面板据此提示「均衡器已自动旁路」） */
    dsdPlaying: Boolean = false,
    /** DSD 倍率标签 */
    dsdLabel: String? = null,
    /** 用户是否开启了 DSD 自动旁路 EQ */
    dsdBypassEq: Boolean = true,
    sleepTotalMin: Int,
    sleepRemainingSec: Int,
    onSleepChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    // 面板每次打开都重读一次设备频段：audio session 可能在面板没开的时候才建出来，
    // 只依赖 sessionVersion 会漏掉这种情况，用户看到的就是「只有开关没有滑块」
    LaunchedEffect(Unit) { onRefreshEq() }
    var expanded by remember { mutableStateOf<String?>(null) }
    val noInd = { _: MutableInteractionSource -> }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(420.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth().heightIn(max = 540.wdp)
                )
                .clip(
                    if (drawer) RoundedCornerShape(topStart = 20.wdp, bottomStart = 20.wdp)
                    else RoundedCornerShape(topStart = 20.wdp, topEnd = 20.wdp)
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .sheetBottomPadding(!drawer)
                .verticalScroll(rememberScrollState())
                .padding(top = 16.wdp, bottom = 28.wdp)
        ) {
            Text(
                "音效",
                color = c.t1,
                fontSize = 16.wsp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(6.wdp))

            if (audioOffload) {
                // offload 时整条音效链都没挂上 —— 与其显示一堆点了没反应的开关，不如说清楚
                Text(
                    "省电播放已开启：音频直通 DSP，均衡器与全部音效不可用。" +
                        "关闭「省电播放」后重启起播即可恢复。",
                    color = c.t3,
                    fontSize = 12.wsp,
                    lineHeight = 18.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 8.wdp)
                )
            }

            // DSD 播放中：均衡器被自动旁路，**必须留痕** ——
            // 用户设了 EQ 却发现没声音/没效果，不提示会以为 App 出 bug。
            if (dsdPlaying && dsdBypassEq && eqBands.isNotEmpty()) {
                Text(
                    "正在播放 ${dsdLabel ?: "DSD"}：均衡器已自动旁路" +
                        "（DSD 解码链占满 CPU，EQ 会导致卡顿）。" +
                        "你的均衡器设置已保留，切回普通格式立刻恢复。",
                    color = c.t3,
                    fontSize = 12.wsp,
                    lineHeight = 18.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 8.wdp)
                )
            }

            // 均衡器同样不做「挂不上就整块消失」：
            // 没频段时留一行说明，让用户知道是资源/设备问题，不是 App 没这个功能。
            if (eqBands.isEmpty()) {
                EffectRow(
                    "均衡器",
                    if (eqStarved) "资源占用" else "本机不支持",
                    arrow = false
                ) { }
            }
            if (eqBands.isNotEmpty()) {
                val eqBypassed = dsdPlaying && dsdBypassEq
                EffectRow(
                    "均衡器",
                    when {
                        eqBypassed -> "已旁路"
                        eqEnabled -> "开"
                        else -> "关"
                    },
                    arrow = false
                ) {
                    onEqEnabledChange(!eqEnabled)
                }
                if (eqPresets.isNotEmpty()) {
                    // ⚠️ 这一行**必须始终渲染**，不能塞进 `if (eqEnabled)` 里。
                    // 拖动频段会自动打开均衡器（changeEqBand 里 eqEnabled = true），
                    // 如果预设行是条件渲染，第一次拖动时它会突然插进来，
                    // 底部对齐的面板（Alignment.BottomCenter + heightIn）整体上移 74px，
                    // 手指底下的滑块跟着跳走 —— 表现就是「第一次拖动之后再也拖不准」。
                    // 面板高度必须恒定，滑块在屏幕上的位置才不会变。
                    EffectRow(
                        "预设",
                        eqPresets.getOrNull(eqPreset) ?: eqPresets.first(),
                        arrow = true
                    ) { expanded = if (expanded == "eq") null else "eq" }
                    if (expanded == "eq") {
                        ChipRow(
                            options = eqPresets.mapIndexed { i, n -> i.toString() to n },
                            selected = eqPreset.toString()
                        ) { onEqPresetChange(it.toInt()) }
                    }
                }
                EqBandSliders(
                    bands = eqBands,
                    freqs = eqFreqs,
                    range = eqRange,
                    onBandChange = onEqBandChange
                )
            }

            // 增强项一律显示：不可用时灰掉并说明原因。
            // 以前是「不支持就整行不渲染」，结果同一台机器上出现「音效项莫名少两条」，
            // 用户会以为功能被删了 —— 功能缺失和功能不可用必须在界面上分得清。
            FxRow(
                title = "低音增强",
                value = if (fxBassAvailable) fxStrengthLabel(fxBass) else "不可用",
                available = fxBassAvailable && !audioOffload && !fxBassStarved,
                starved = fxBassStarved,
                expanded = expanded == "bass",
                onClick = { expanded = if (expanded == "bass") null else "bass" }
            ) {
                ChipRow(FX_STRENGTH_STEPS, fxBass.toString()) { onFxBassChange(it.toInt()) }
            }
            FxRow(
                title = "环绕增强",
                value = if (fxVirtualAvailable) fxStrengthLabel(fxVirtual) else "不可用",
                available = fxVirtualAvailable && !audioOffload && !fxVirtualStarved,
                starved = fxVirtualStarved,
                expanded = expanded == "virt",
                onClick = { expanded = if (expanded == "virt") null else "virt" }
            ) {
                ChipRow(FX_STRENGTH_STEPS, fxVirtual.toString()) { onFxVirtualChange(it.toInt()) }
            }
            FxRow(
                title = "响度增强",
                value = if (fxLoudnessAvailable) {
                    if (fxLoudness <= 0) "关" else "+${fxLoudness / 100} dB"
                } else "不可用",
                available = fxLoudnessAvailable && !audioOffload && !fxLoudnessStarved,
                starved = fxLoudnessStarved,
                expanded = expanded == "loud",
                onClick = { expanded = if (expanded == "loud") null else "loud" }
            ) {
                ChipRow(FX_LOUD_STEPS, fxLoudness.toString()) { onFxLoudnessChange(it.toInt()) }
            }
            FxRow(
                title = "动态范围",
                value = if (fxDynamicsAvailable) fxDynamicsLabel(fxDynamics) else "不可用",
                available = fxDynamicsAvailable && !audioOffload && !fxDynamicsStarved,
                starved = fxDynamicsStarved,
                expanded = expanded == "dyn",
                onClick = { expanded = if (expanded == "dyn") null else "dyn" }
            ) {
                ChipRow(FX_DYNAMICS_STEPS, fxDynamics.toString()) { onFxDynamicsChange(it.toInt()) }
            }
            FxRow(
                title = "混响",
                value = if (fxReverbAvailable) fxReverbLabel(fxReverb) else "不可用",
                available = fxReverbAvailable && !audioOffload && !fxReverbStarved,
                starved = fxReverbStarved,
                expanded = expanded == "reverb",
                onClick = { expanded = if (expanded == "reverb") null else "reverb" }
            ) {
                ChipRow(FX_REVERB_STEPS, fxReverb.toString()) { onFxReverbChange(it.toInt()) }
            }

            // 手动「重挂音效链」入口已移除。
            // 理由：用户点它十次有九次没反应（池位回收是异步的，重建几乎必然再失败），
            // 反而让人以为功能坏了。现在改成**失败自动退避重试**（见 MoyinPlaybackService），
            // 抢得回来就自己抢回来，抢不回来就是本机不支持 —— 不需要用户操心。

            Spacer(Modifier.height(4.wdp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.wdp)
                    .height(1.dp)
                    .background(if (c.isDark) Color(0x14FFFFFF) else Color(0x0A000000))
            )
            Spacer(Modifier.height(4.wdp))

            EffectRow(
                "睡眠定时",
                if (sleepRemainingSec > 0) {
                    "还剩 %d:%02d".format(sleepRemainingSec / 60, sleepRemainingSec % 60)
                } else "关"
            ) { expanded = if (expanded == "sleep") null else "sleep" }
            if (expanded == "sleep") {
                ChipRow(SLEEP_STEPS, sleepTotalMin.toString()) { onSleepChange(it.toInt()) }
            }
        }
    }
}

@Composable
private fun EffectRow(
    title: String,
    value: String,
    arrow: Boolean = true,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 13.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = c.t1, fontSize = 14.wsp, modifier = Modifier.weight(1f))
        Text(value, color = c.t3, fontSize = 13.wsp)
        if (arrow) {
            Spacer(Modifier.width(4.wdp))
            MoyinIcon(IconChevronRight, tint = c.t3, size = 13.wdp)
        }
    }
}

/**
 * 带「不可用」态的增强项行。
 *
 * 和 [EffectRow] 的区别：不可用时**照样占一行**，文字变灰、值写「不可用」、点了不展开。
 * 这样用户永远能看到「这台机器有哪些音效项」，不会误以为功能被删。
 * starved=true 时额外标「资源占用」，因为这种是能救回来的（见面板底部的重挂入口）。
 */
@Composable
private fun FxRow(
    title: String,
    value: String,
    available: Boolean,
    starved: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val c = moyinColors()
    val dim = !available
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                enabled = available,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 13.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = if (dim) c.t3.copy(alpha = 0.55f) else c.t1,
            fontSize = 14.wsp,
            modifier = Modifier.weight(1f)
        )
        Text(
            when {
                !available && starved -> "资源占用"
                // 从没建成过 = 这台机器的 ROM 没给这个 effect 的 per-session 实现，
                // 说「不可用」太含糊，用户会一直以为是临时故障、反复点。
                !available -> "本机不支持"
                else -> value
            },
            color = if (dim) c.t3.copy(alpha = 0.55f) else c.t3,
            fontSize = 13.wsp
        )
        if (available) {
            Spacer(Modifier.width(4.wdp))
            MoyinIcon(IconChevronRight, tint = c.t3, size = 13.wdp)
        }
    }
    if (available && expanded) content()
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.wdp, vertical = 2.wdp),
        horizontalArrangement = Arrangement.spacedBy(8.wdp)
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (on) c.accent
                        else if (c.isDark) Color(0x14FFFFFF) else Color(0x0A000000)
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onPick(key) }
                    .padding(horizontal = 13.wdp, vertical = 7.wdp)
            ) {
                Text(label, color = if (on) Color.White else c.t2, fontSize = 12.wsp)
            }
        }
    }
}

private val FX_STRENGTH_STEPS = listOf(
    "0" to "关", "250" to "弱", "500" to "中", "750" to "强", "1000" to "极强"
)
private val FX_LOUD_STEPS = listOf(
    "0" to "关", "300" to "+3 dB", "600" to "+6 dB", "900" to "+9 dB", "1200" to "+12 dB"
)
private val FX_REVERB_STEPS = listOf(
    "-1" to "关", "1" to "小房间", "2" to "中房间", "3" to "大房间",
    "4" to "中厅", "5" to "大厅", "6" to "板式"
)
private val FX_DYNAMICS_STEPS = listOf(
    "0" to "关", "1" to "轻", "2" to "中", "3" to "强"
)
private val SLEEP_STEPS = listOf(
    "0" to "关", "15" to "15 分钟", "30" to "30 分钟",
    "45" to "45 分钟", "60" to "1 小时", "90" to "1.5 小时"
)

private fun fxStrengthLabel(v: Int) = when {
    v <= 0 -> "关"
    v < 375 -> "弱"
    v < 625 -> "中"
    v < 875 -> "强"
    else -> "极强"
}

private fun fxReverbLabel(v: Int) =
    FX_REVERB_STEPS.firstOrNull { it.first == v.toString() }?.second ?: "关"

private fun fxDynamicsLabel(v: Int) =
    FX_DYNAMICS_STEPS.firstOrNull { it.first == v.toString() }?.second ?: "关"

@Composable
private fun OptionSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp))
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .sheetBottomPadding()
                .padding(top = 14.wdp, bottom = 14.wdp)
        ) {
            Text(
                title,
                color = c.t1,
                fontSize = 14.wsp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(8.wdp))
            options.forEach { (key, label) ->
                val on = key == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onPick(key) }
                        .padding(horizontal = 18.wdp, vertical = 12.wdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        color = if (on) c.accent else c.t1,
                        fontSize = 14.wsp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (on) MoyinIcon(icon = IconCheck, tint = c.accent, size = 16.wdp)
                }
            }
        }
    }
}

/** 更多操作：下载 / 加入播放队列 */
@Composable
private fun MoreSheet(
    onDownload: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    /** 当前音质短名（母带/无损/极高品/标准）；为空则不显示音质入口 */
    qualityLabel: String = "",
    onQualityClick: () -> Unit = {},
    // ── 歌词调节：从歌词区搬过来的（歌词区只负责唱）──
    lyricOffsetMs: Long = 0L,
    onNudgeOffset: (Long) -> Unit = {},
    onResetOffset: () -> Unit = {},
    fontScale: Float = 1f,
    onNudgeFontScale: (Float) -> Unit = {},
    onResetFontScale: () -> Unit = {},
    onReportWrongLyric: (() -> Unit)? = null,
    aiConfigured: Boolean = false,
    translating: Boolean = false,
    hasTranslation: Boolean = false,
    onTranslate: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(400.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth()
                )
                .clip(
                    if (drawer) RoundedCornerShape(topStart = 18.wdp, bottomStart = 18.wdp)
                    else RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp)
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .sheetBottomPadding(!drawer)
                .padding(top = 14.wdp, bottom = 14.wdp)
        ) {
            if (qualityLabel.isNotEmpty()) {
                MoreRow(IconEqualizer, "音质：$qualityLabel", onQualityClick)
            }
            MoreRow(IconDownload, "下载", onDownload)
            MoreRow(IconPlus, "加入播放队列", onAddToQueue)
            MoreRow(IconPlaylist, "加入歌单", onAddToPlaylist)

            // ── 歌词 ────────────────────────────────────────────
            // 放这里而不是歌词区：歌词区被两行 chip 压掉 ~110dp，横屏只剩几行词。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.wdp)
                    .height(1.wdp)
                    .background(c.line.copy(alpha = 0.7f))
            )
            Text(
                "歌词",
                color = c.t3,
                fontSize = 11.wsp,
                modifier = Modifier.padding(start = 18.wdp, top = 10.wdp, bottom = 2.wdp)
            )
            MoreStepper(
                label = "时间轴",
                valueLabel = offsetLabel(lyricOffsetMs),
                valueActive = lyricOffsetMs != LYRIC_OFFSET_DEFAULT_MS,
                minusLabel = "延后",
                plusLabel = "提前",
                onMinus = { onNudgeOffset(-500) },
                onPlus = { onNudgeOffset(500) },
                onReset = onResetOffset
            )
            MoreStepper(
                label = "字号",
                valueLabel = "${(fontScale * 100).toInt()}%",
                valueActive = fontScale != 1f,
                minusLabel = "小",
                plusLabel = "大",
                onMinus = { onNudgeFontScale(-0.1f) },
                onPlus = { onNudgeFontScale(0.1f) },
                onReset = onResetFontScale
            )
            if (aiConfigured) {
                MoreRow(
                    IconInfo,
                    when {
                        translating -> "歌词翻译中…"
                        hasTranslation -> "已翻译（重新译）"
                        else -> "AI 翻译歌词"
                    }
                ) { if (!translating) onTranslate() }
            }
            if (onReportWrongLyric != null) {
                MoreRow(IconInfo, "歌词不对？") { onReportWrongLyric() }
            }
        }
    }
}

/**
 * 「更多」里的 ± 调节行：左标签、右数值（点数值 = 复位）、两端各一个 chip。
 * 和歌词区原来那排 chip 是同一套手感，只是换了个位置。
 */
@Composable
private fun MoreStepper(
    label: String,
    valueLabel: String,
    valueActive: Boolean,
    minusLabel: String,
    plusLabel: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onReset: () -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.wdp, vertical = 7.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.t1, fontSize = 14.wsp)
        Spacer(Modifier.weight(1f))
        MoreChip(minusLabel, onMinus)
        // 数值本身可点：点一下复位。固定宽度，避免 "+0.5s" 和 "100%" 宽度不同把两边 chip 挤来挤去
        Box(
            Modifier
                .width(58.wdp)
                .clip(RoundedCornerShape(99.wdp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onReset() }
                .padding(vertical = 6.wdp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                valueLabel,
                color = if (valueActive) c.accent else c.t3,
                fontSize = 12.wsp,
                fontWeight = if (valueActive) FontWeight.Medium else FontWeight.Normal
            )
        }
        MoreChip(plusLabel, onPlus)
    }
}

/** 「更多」里的小胶囊按钮：有底、有间距，手指点得准 */
@Composable
private fun MoreChip(label: String, onClick: () -> Unit) {
    val c = moyinColors()
    Text(
        text = label,
        color = c.t1,
        fontSize = 11.wsp,
        modifier = Modifier
            .padding(horizontal = 3.wdp)
            .clip(RoundedCornerShape(99.wdp))
            .background(if (c.isDark) Color(0x1FFFFFFF) else Color(0x0F000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.wdp, vertical = 6.wdp)
    )
}

@Composable
private fun MoreRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 12.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoyinIcon(icon = icon, tint = c.t2, size = 17.wdp)
        Spacer(Modifier.width(12.wdp))
        Text(label, color = c.t1, fontSize = 14.wsp)
    }
}

/**
 * 音质文案 —— 只有真母带才显示 24bit/192kHz。
 * 没有音质信息时返回空串（UI 不显示标签），避免所有歌挂同一个标签。
 */
internal fun qualityText(track: Track): String = when {
    track.isMaster -> "24bit / 192kHz"
    track.qualityLabel.isNotEmpty() -> track.qualityLabel
    else -> ""
}

internal fun formatMs(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 已播放时间。位置读取单独关在这个叶子 Composable 里：
 * positionProvider() 每 100ms 变一次，读在 PlayerScreen 顶层会把整页（封面/歌词/按钮）拖去重组。
 */
@Composable
private fun ElapsedTimeText(
    positionProvider: () -> Long,
    color: Color
) {
    Text(formatMs(positionProvider()), color = color, fontSize = 10.wsp)
}

/**
 * 音频信息 + AI 导赏。
 *
 * 音频参数只列能核实的东西（见 [AudioInfo] 注释：刻意没有位深）。
 * AI 导赏必须用户自己配 key —— 没配就引导去设置，不摆一个点了只会报错的按钮。
 */
@Composable
private fun InfoSheet(
    info: AudioInfo?,
    loudnessGainDb: Float,
    probeResult: SpectrumProbe.Result?,
    probeRunning: Boolean,
    probeError: String?,
    onProbe: () -> Unit,
    canProbe: Boolean,
    aiConfigured: Boolean,
    aiText: String,
    aiLoading: Boolean,
    aiError: String?,
    onRequestAi: () -> Unit,
    onCancelAi: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(420.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth().heightIn(max = 580.wdp)
                )
                .clip(
                    if (drawer) RoundedCornerShape(topStart = 20.wdp, bottomStart = 20.wdp)
                    else RoundedCornerShape(topStart = 20.wdp, topEnd = 20.wdp)
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .sheetBottomPadding(!drawer)
                .verticalScroll(rememberScrollState())
                .padding(top = 16.wdp, bottom = 30.wdp)
        ) {
            Text(
                "音频信息",
                color = c.t1,
                fontSize = 16.wsp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(10.wdp))

            if (info == null) {
                Text(
                    "播放器还没有输出音频轨",
                    color = c.t3,
                    fontSize = 13.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 6.wdp)
                )
            } else {
                InfoRow("格式", info.codec)
                if (info.requestedLabel != null) {
                    InfoRow(
                        "请求档位",
                        if (info.downgraded) "${info.requestedLabel}（源站未提供无损）"
                        else info.requestedLabel
                    )
                }
                InfoRow("源采样率", info.sampleRate?.let { fmtHz(it) } ?: "未知")
                InfoRow(
                    "声道",
                    info.channels?.let {
                        when (it) {
                            1 -> "单声道"
                            2 -> "双声道"
                            else -> "$it 声道"
                        }
                    } ?: "未知"
                )
                InfoRow("码率", info.bitrateKbps?.let { "$it kbps" } ?: "未知")
                InfoRow("设备输出", info.outSampleRate?.let { fmtHz(it) } ?: "未知")
                val sr = info.sampleRate
                val osr = info.outSampleRate
                if (sr != null && osr != null && sr != osr) {
                    InfoRow("重采样", "是（${fmtHz(sr)} → ${fmtHz(osr)}）")
                }
                InfoRow("缓冲余量", "%.1f s".format(info.bufferedMs / 1000f))
                InfoRow("音源", info.sourceLabel)
                if (loudnessGainDb != 0f) {
                    InfoRow(
                        "响度归一",
                        (if (loudnessGainDb > 0) "+" else "") + "%.1f dB".format(loudnessGainDb)
                    )
                }
            }

            // ── 真实音质检测（只对本地文件）──
            Spacer(Modifier.height(10.wdp))
            if (canProbe) {
                if (probeResult != null) {
                    InfoRow("实测截止", fmtHz(probeResult.cutoffHz))
                    InfoRow("推断音质", probeResult.verdict.label)
                    Spacer(Modifier.height(6.wdp))
                    InfoAction("重新检测", onProbe)
                } else if (probeRunning) {
                    Text(
                        "正在解码分析…（整首，约 2~4 秒）",
                        color = c.t3,
                        fontSize = 12.wsp,
                        modifier = Modifier.padding(horizontal = 18.wdp, vertical = 4.wdp)
                    )
                } else {
                    InfoAction("检测真实音质", onProbe)
                }
                probeError?.let {
                    Text(
                        it,
                        color = c.t3,
                        fontSize = 11.wsp,
                        lineHeight = 16.wsp,
                        modifier = Modifier.padding(horizontal = 18.wdp, vertical = 2.wdp)
                    )
                }
            }

            Spacer(Modifier.height(8.wdp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.wdp)
                    .height(1.dp)
                    .background(if (c.isDark) Color(0x14FFFFFF) else Color(0x0F000000))
            )
            Spacer(Modifier.height(14.wdp))

            Text(
                "AI 导赏",
                color = c.t1,
                fontSize = 15.wsp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(8.wdp))

            when {
                !aiConfigured -> {
                    Text(
                        "填入自己的 API Key 后才能生成。支持任何 OpenAI 兼容接口（默认 DeepSeek，也可以填 OpenAI / 通义 / 智谱）。",
                        color = c.t3,
                        fontSize = 12.wsp,
                        lineHeight = 18.wsp,
                        modifier = Modifier.padding(horizontal = 18.wdp)
                    )
                    Spacer(Modifier.height(10.wdp))
                    InfoAction("去设置填 Key", onOpenAiSettings)
                }

                aiText.isNotBlank() -> {
                    Text(
                        aiText,
                        color = c.t1,
                        fontSize = 14.wsp,
                        lineHeight = 23.wsp,
                        modifier = Modifier.padding(horizontal = 18.wdp)
                    )
                    if (aiLoading) {
                        Spacer(Modifier.height(6.wdp))
                        Text(
                            "正在生成…",
                            color = c.t3,
                            fontSize = 11.wsp,
                            modifier = Modifier.padding(horizontal = 18.wdp)
                        )
                    }
                }

                aiLoading -> {
                    Row(
                        Modifier.padding(horizontal = 18.wdp, vertical = 6.wdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.wdp),
                            strokeWidth = 1.6.dp,
                            color = c.accent
                        )
                        Spacer(Modifier.width(10.wdp))
                        Text("正在生成…", color = c.t3, fontSize = 13.wsp)
                    }
                }

                aiError != null -> {
                    Text(
                        aiError,
                        color = c.t3,
                        fontSize = 12.wsp,
                        lineHeight = 18.wsp,
                        modifier = Modifier.padding(horizontal = 18.wdp)
                    )
                    Spacer(Modifier.height(10.wdp))
                    InfoAction("重试", onRequestAi)
                }

                else -> InfoAction("生成导赏", onRequestAi)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.wdp, vertical = 6.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.t3, fontSize = 13.wsp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 2,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun InfoAction(text: String, onClick: () -> Unit) {
    val c = moyinColors()
    Box(
        Modifier
            .padding(horizontal = 18.wdp)
            .clip(RoundedCornerShape(12.wdp))
            .background(c.accentFill)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 10.wdp)
    ) {
        Text(text, color = c.onAccent, fontSize = 13.wsp, fontWeight = FontWeight.Medium)
    }
}

private fun fmtHz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)
