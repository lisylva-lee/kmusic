package com.kmusic.apkhd.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 网易云公开接口的响应模型。
 *
 * 用途仅为**推荐位元数据**（推荐歌单 / 新歌 / 歌手），不承担播放 ——
 * 网易云的播放接口需要登录 + 复刻其自有加密链路，成本远大于收益。
 * 这里拿到的歌在播放时走「跨源回退」到酷我（见 TrackMatcher）。
 *
 * 注意：`playlist/detail` 返回的是**旧字段名**（artists / album / duration），
 * 而 `/api/song/detail` 系列是 ar / al / dt，两套不能混用。
 */
object NeteaseJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

@Serializable
data class NeRecommendResponse(
    val code: Int = 0,
    val result: List<NePlaylist>? = null
)

@Serializable
data class NePlaylist(
    val id: Long = 0,
    val name: String? = null,
    val picUrl: String? = null,
    val playCount: Double = 0.0,
    val trackCount: Int = 0,
    val copywriter: String? = null
)

@Serializable
data class NeArtistListResponse(
    val code: Int = 0,
    val artists: List<NeArtist>? = null
)

@Serializable
data class NeArtist(
    val id: Long = 0,
    val name: String? = null,
    val picUrl: String? = null,
    val albumSize: Int = 0,
    val musicSize: Int = 0,
    val alias: List<String>? = null
)

@Serializable
data class NeNewsongResponse(
    val code: Int = 0,
    val result: List<NeNewsongItem>? = null
)

@Serializable
data class NeNewsongItem(
    val id: Long = 0,
    val name: String? = null,
    val picUrl: String? = null,
    val song: NeSong? = null
)

@Serializable
data class NeSong(
    val id: Long = 0,
    val name: String? = null,
    /** 毫秒 */
    val duration: Long = 0,
    val artists: List<NeNamed>? = null,
    val album: NeAlbum? = null
)

@Serializable
data class NeNamed(val name: String? = null)

@Serializable
data class NeAlbum(
    val name: String? = null,
    val picUrl: String? = null
)

@Serializable
data class NePlaylistDetailResponse(
    val code: Int = 0,
    val result: NePlaylistDetail? = null
)

@Serializable
data class NePlaylistDetail(
    val id: Long = 0,
    val name: String? = null,
    val coverImgUrl: String? = null,
    val trackCount: Int = 0,
    val description: String? = null,
    val tracks: List<NeSong>? = null
)
