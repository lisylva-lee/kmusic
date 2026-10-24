package com.kmusic.apkhd.model

/**
 * 曲库列表项 —— 专辑 / 歌手 / 歌单 三种共用。
 *
 * 它们在 UI 上都是「封面 + 标题 + 副标题」，没必要为三种各写一套列表。
 */
data class CollectionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
    /** 歌手用圆形封面，专辑/歌单用圆角方形 */
    val round: Boolean = false
)
