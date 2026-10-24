package com.kmusic.apkhd.model

import kotlinx.serialization.Serializable

/** 统一曲目模型：聚合酷我 / Navidrome / 本地三源 */
@Serializable
data class Track(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0,
    /** 来源：kuwo / navidrome / local */
    val source: String = "kuwo",
    /** 原始 rid（酷我）或 Subsonic id */
    val sourceId: String = "",
    /** 音质标签，如「母带」「无损」「FLAC」 */
    val qualityLabel: String = "",
    /** 是否臻品母带 24bit/192kHz */
    val isMaster: Boolean = false,
    /** 播放直链（已解析） */
    val playUrl: String? = null,
    /** 酷我 ekey，用于流式解密 */
    val ekey: String? = null,
    /** 歌词直链（咪咕搜索响应自带 LRC 地址；其它源为空） */
    val lyricUrl: String? = null
)

/** 播放状态快照，UI 直接观察 */
data class PlaybackState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    val error: String? = null
) {
    val progress: Float
        get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
}

/** 首页快捷入口 */
data class QuickEntry(
    val title: String,
    val subtitle: String,
    val highlighted: Boolean = false
)

/** 一行歌词：时间轴 + 文本 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** 下载任务 */
data class DownloadTask(
    val id: String,
    val name: String,
    val artist: String,
    val qualityLabel: String,
    val progress: Float,
    val status: String,
    val path: String?
)
