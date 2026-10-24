package com.kmusic.apkhd.model

import kotlinx.serialization.Serializable

/**
 * 发现页用的领域模型：歌单 / 歌手。
 * 与 Track 一样带 source，便于 UI 标注来源、播放时决定走原生还是跨源回退。
 */
@Serializable
data class PlaylistBrief(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val playCount: Long = 0,
    /** kuwo / netease */
    val source: String = "kuwo",
    val creator: String = "",
    val description: String = ""
)

@Serializable
/** 酷我专辑（搜索结果，非曲库） */
data class AlbumBrief(
    val id: String,
    val name: String,
    val artist: String = "",
    val coverUrl: String? = null,
    val songCount: Int = 0,
    val source: String = "kuwo"
)

data class ArtistBrief(
    val id: String,
    val name: String,
    val picUrl: String? = null,
    val songCount: Int = 0,
    val albumCount: Int = 0,
    /** kuwo / netease */
    val source: String = "kuwo"
)

/** 排行榜榜单 */
@Serializable
data class ChartBrief(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val songCount: Int = 0,
    val source: String = "kuwo"
)

/** 榜单 + 已拉取的歌曲（首页展示用，点进去不再重新请求） */
data class ChartSection(
    val chart: ChartBrief,
    val tracks: List<Track>
)
