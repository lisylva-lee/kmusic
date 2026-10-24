package com.kmusic.apkhd.model

import kotlinx.serialization.Serializable

/**
 * 自建歌单。
 *
 * 和收藏一样存**完整曲目**，不是只存 id —— 只存 id 回查拿不到歌名/歌手，
 * 列表会退化成「曲目 440613」这种占位。
 */
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val tracks: List<Track> = emptyList()
)
