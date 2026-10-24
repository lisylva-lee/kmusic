package com.kmusic.apkhd.net

import com.kmusic.apkhd.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今日推荐」的挑选逻辑。
 *
 * 最要紧的一条是**同一天必须稳定**：如果每次进页面都换一批，这个功能就是假的
 * （用户刷新两次就发现了），所以这里把「同种子同结果 / 异种子不同结果」钉死。
 */
class DailyMixTest {

    private fun t(name: String, artist: String, ms: Long = 240_000, src: String = "kuwo") = Track(
        id = "$src:$name:$artist",
        name = name,
        artist = artist,
        album = "",
        coverUrl = null,
        durationMs = ms,
        source = src,
        sourceId = "1"
    )

    // ---------------- 清洗 ----------------

    @Test
    fun `同一首歌来自两个源只留一首`() {
        val pool = listOf(
            t("晴天", "周杰伦", src = "kuwo"),
            t("晴天", "周杰伦", src = "netease")
        )
        assertEquals(1, DailyMix.clean(pool).size)
    }

    @Test
    fun `丢掉太短的片段`() {
        val pool = listOf(t("正常歌", "A"), t("报幕", "A", ms = 12_000))
        assertEquals(listOf("正常歌"), DailyMix.clean(pool).map { it.name })
    }

    @Test
    fun `空歌名不进池`() {
        assertEquals(0, DailyMix.clean(listOf(t("  ", "A"))).size)
    }

    // ---------------- 稳定性（关键） ----------------

    @Test
    fun `同一天的种子给同一批同一顺序`() {
        val pool = (1..60).map { t("歌$it", "歌手$it") }
        val a = DailyMix.build(pool, seed = 20_000, count = 20)
        val b = DailyMix.build(pool, seed = 20_000, count = 20)
        assertEquals(a.map { it.name }, b.map { it.name })
    }

    @Test
    fun `换一天就换一批`() {
        val pool = (1..60).map { t("歌$it", "歌手$it") }
        val a = DailyMix.build(pool, seed = 20_000, count = 20)
        val b = DailyMix.build(pool, seed = 20_001, count = 20)
        assertNotEquals(a.map { it.name }, b.map { it.name })
    }

    // ---------------- 边界 ----------------

    @Test
    fun `空池不炸`() {
        assertTrue(DailyMix.build(emptyList(), 1, 20).isEmpty())
    }

    @Test
    fun `池子不够时给多少算多少`() {
        val pool = (1..5).map { t("歌$it", "A") }
        assertEquals(5, DailyMix.build(pool, 1, 20).size)
    }

    @Test
    fun `结果不重复`() {
        val pool = (1..40).map { t("歌$it", "歌手$it") }
        val r = DailyMix.build(pool, 999, 20)
        assertEquals(r.size, r.distinctBy { it.id }.size)
    }

    // ---------------- 多源均衡 ----------------

    @Test
    fun `两个源都能上得来`() {
        val pool = (1..30).map { t("K$it", "A", src = "kuwo") } +
            (1..30).map { t("N$it", "B", src = "netease") }
        val r = DailyMix.build(pool, 20_000, 20)
        val srcs = r.map { it.source }.toSet()
        assertTrue("两个源都应该出现，实际=$srcs", srcs.size == 2)
    }
}
