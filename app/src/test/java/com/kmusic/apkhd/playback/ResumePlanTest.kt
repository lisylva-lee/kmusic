package com.kmusic.apkhd.playback

import com.kmusic.apkhd.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 恢复上次播放的边界条件。
 *
 * 这些分支真机很难逐个复现（要造「快照比队列新」「歌被删了」「正好听完」），
 * 所以钉在纯函数上 —— ResumePlan 是 ViewModel 里唯一有判断逻辑的部分。
 */
class ResumePlanTest {

    private fun t(id: String, dur: Long = 200_000L) =
        Track(id = id, name = "n-$id", artist = "a", album = "b", durationMs = dur)

    private val tracks = listOf(t("a"), t("b"), t("c"), t("d"))

    // ---- 恢复到第几首 ----

    @Test
    fun `按 id 找到时不看下标`() {
        // 下标说第 0 首，id 说是第 3 首 —— id 赢（下标会漂，id 不会）
        assertEquals(3, ResumePlan.index(tracks, "d", 0))
    }

    @Test
    fun `id 找不到才退回下标`() {
        assertEquals(2, ResumePlan.index(tracks, "gone", 2))
    }

    @Test
    fun `下标越界要夹回区间`() {
        assertEquals(0, ResumePlan.index(tracks, "gone", -5))
        assertEquals(3, ResumePlan.index(tracks, "gone", 99))
    }

    @Test
    fun `空队列返回 -1`() {
        assertEquals(-1, ResumePlan.index(emptyList(), "a", 0))
    }

    @Test
    fun `id 为空串时退回下标`() {
        assertEquals(1, ResumePlan.index(tracks, "", 1))
    }

    // ---- 从哪开始播 ----

    @Test
    fun `正常位置原样返回`() {
        assertEquals(42_000L, ResumePlan.positionMs(42_000L, 200_000L))
    }

    @Test
    fun `负数当 0`() {
        assertEquals(0L, ResumePlan.positionMs(-1L, 200_000L))
    }

    @Test
    fun `快到最后三秒就从头开始`() {
        // 199_000 距离 200_000 只剩 1 秒 → 当作听完
        assertEquals(0L, ResumePlan.positionMs(199_000L, 200_000L))
        // 剩余正好 3 秒也算听完（边界含等号）
        assertEquals(0L, ResumePlan.positionMs(197_000L, 200_000L))
        // 剩 3.1 秒 → 保留
        assertEquals(196_900L, ResumePlan.positionMs(196_900L, 200_000L))
    }

    @Test
    fun `位置超过时长也归零`() {
        assertEquals(0L, ResumePlan.positionMs(500_000L, 200_000L))
    }

    @Test
    fun `时长未知时原样返回`() {
        // 在线曲目解析前 durationMs 可能是 0，这时候无从判断「快听完」，只能信快照
        assertEquals(180_000L, ResumePlan.positionMs(180_000L, 0L))
    }
}
