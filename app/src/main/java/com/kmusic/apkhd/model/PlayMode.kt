package com.kmusic.apkhd.model

/**
 * 播放模式。
 *
 * 用 ExoPlayer 原生的 repeatMode + shuffleModeEnabled 实现 —— 队列本身就是播放器的
 * 播放列表，所以这四个模式是**播放器的真实状态**，不是 UI 上的假开关。
 *
 * - 顺序播放：放完最后一首就停
 * - 列表循环：放完回到第一首
 * - 单曲循环：当前曲循环
 * - 随机播放：打乱顺序 + 列表循环
 */
enum class PlayMode(val label: String) {
    ORDER("顺序播放"),
    LOOP("列表循环"),
    SINGLE("单曲循环"),
    SHUFFLE("随机播放");

    companion object {
        fun of(raw: String?): PlayMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ORDER
    }
}
