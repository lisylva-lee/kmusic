package com.kmusic.apkhd.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 队列拖动排序 → 播放器 moveMediaItem 目标下标的换算。
 *
 * ExoPlayer 的 moveMediaItem 语义是「先移除、再插入到目标下标」，
 * 所以目标在当前位置**之后**时，因为自身已先被移除，插入下标要减一。
 * 算错不会崩 —— 只会让播放列表顺序悄悄错位，且只在拖动后才发现。
 */
class PlaylistOrderTest {

    @Test
    fun moveDown_targetShiftsBackByOne() {
        // [A,B,C,D]，把 A (0) 移到 C 之后（现在的 2 号 → 目标位置紧随其后的是 D=3）
        // 移除 A 后变成 [B,C,D]，要让 A 落在 C 之后 → 插入下标 2
        assertEquals(2, PlaylistOrder.moveTarget(fromAt = 0, afterAt = 3, count = 4))
    }

    @Test
    fun moveUp_targetUnchanged() {
        // 把 D (3) 移到 A 之前（紧随其后的是 A，当前下标 0）
        // 移除 D 后 [A,B,C]，插入下标 0 → A 后面才是 D 吗？不是：插到 0 → [D,A,B,C] ✓
        assertEquals(0, PlaylistOrder.moveTarget(fromAt = 3, afterAt = 0, count = 4))
    }

    @Test
    fun moveToEnd_usesLastIndex() {
        // afterAt = -1 表示它后面没有已解析的曲目 → 移到末尾
        assertEquals(3, PlaylistOrder.moveTarget(fromAt = 0, afterAt = -1, count = 4))
    }

    @Test
    fun adjacentForwardMove() {
        // [A,B,C]，A(0) 与 B(1) 互换 → A 之后是 C(2)，移除 A 后 C 在 1 → 插入 1 → [B,A,C] ✓
        assertEquals(1, PlaylistOrder.moveTarget(fromAt = 0, afterAt = 2, count = 3))
    }

    @Test
    fun emptyPlaylistIsSafe() {
        assertEquals(0, PlaylistOrder.moveTarget(fromAt = 0, afterAt = -1, count = 0))
    }
}
