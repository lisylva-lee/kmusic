package com.kmusic.apkhd.playback

/**
 * 播放列表顺序换算。
 *
 * 队列面板拖动排序时，逻辑队列（`queue`）和播放器播放列表是**两份**数据：
 * 逻辑队列含尚未解析的曲目，播放列表只含已解析的。所以不能直接用逻辑下标去动播放列表。
 *
 * 这里把「逻辑队列里把某首移到 to」换算成「播放器的 moveMediaItem 目标下标」。
 * 抽出来是因为 ExoPlayer 的 moveMediaItem 语义是「先移除、再插入到目标下标」，
 * 下标在移除后会偏移 —— 算错不会崩，只会让播放列表顺序悄悄错位。
 */
object PlaylistOrder {

    /**
     * @param fromAt  被移动那首**当前**在播放列表中的下标
     * @param afterAt 移动到目标位置后、紧随其后的那首在**当前**播放列表中的下标
     *                （-1 表示它后面没有已解析的曲目 → 移到末尾）
     * @param count   播放列表条目数
     * @return moveMediaItem 的目标下标
     */
    fun moveTarget(fromAt: Int, afterAt: Int, count: Int): Int = when {
        count <= 0 -> 0
        afterAt < 0 -> count - 1
        // 目标在当前位置之后：移除自身后它整体前移一位
        afterAt > fromAt -> afterAt - 1
        else -> afterAt
    }
}
