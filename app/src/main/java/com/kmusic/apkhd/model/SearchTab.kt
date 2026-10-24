package com.kmusic.apkhd.model

/**
 * 搜索页签。
 *
 * 之前这一排只是换高亮色的装饰 —— 选什么都返回同一份歌曲列表。现在每个页签
 * 走各自的接口、渲染各自的数据结构：
 * - 单曲 → r.s?ft=music
 * - 歌手 → r.s?ft=artist
 * - 专辑 → r.s?ft=album
 * - 歌单 → r.s?ft=playlist
 */
enum class SearchTab(val label: String) {
    SONG("单曲"),
    ALBUM("专辑"),
    ARTIST("歌手"),
    PLAYLIST("歌单")
}
