package com.kmusic.apkhd.net

import com.kmusic.apkhd.net.TrackMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨源匹配器测试。
 *
 * 这是"宁可少几首，不能串歌"的执行者，所以测试重点放在
 * **反例**上：必须拒绝的都是真实会遇到的坑（Live 版、伴奏、翻唱、同名不同歌）。
 */
class TrackMatcherTest {

    private fun c(name: String, artist: String, sec: Long) = Candidate(name, artist, sec * 1000)

    // ---------------- 归一化 ----------------

    @Test
    fun normalize_stripsBracketsAndPunct() {
        assertEquals("最伟大的作品", TrackMatcher.normalize("最伟大的作品 (Live)"))
        assertEquals("晴天", TrackMatcher.normalize("晴天（伴奏）"))
        assertEquals("hello", TrackMatcher.normalize("Ｈｅｌｌｏ"))
        assertEquals("说好不哭", TrackMatcher.normalize("说好不哭 【DJ版】"))
    }

    // ---------------- 歌名 ----------------

    @Test
    fun name_exactAfterNormalize_matches() {
        assertTrue(TrackMatcher.nameMatches("最伟大的作品", "最伟大的作品"))
        assertTrue(TrackMatcher.nameMatches("Hello", "hello"))
        // 版本标注在括号里，归一化后应当一致
        assertTrue(TrackMatcher.nameMatches("说好不哭", "说好不哭 (with 五月天阿信)"))
    }

    @Test
    fun name_similarButDifferent_rejected() {
        // 长度比不足，必须拒绝
        assertFalse(TrackMatcher.nameMatches("晴天", "晴天娃娃"))
        assertFalse(TrackMatcher.nameMatches("后来", "后来的我们"))
        assertFalse(TrackMatcher.nameMatches("", "晴天"))
    }

    // ---------------- 歌手 ----------------

    @Test
    fun artist_multiSignature_matches() {
        assertTrue(TrackMatcher.artistMatches("周杰伦", "周杰伦"))
        // 候选是多人署名，目标歌手是其中之一
        assertTrue(TrackMatcher.artistMatches("周杰伦", "周杰伦/五月天阿信"))
        assertTrue(TrackMatcher.artistMatches("五月天阿信", "周杰伦&五月天阿信"))
    }

    @Test
    fun artist_different_rejected() {
        assertFalse(TrackMatcher.artistMatches("周杰伦", "林俊杰"))
        assertFalse(TrackMatcher.artistMatches("周杰伦", ""))
    }

    // ---------------- 时长 ----------------

    @Test
    fun duration_within3s_matches() {
        assertTrue(TrackMatcher.durationMatches(244_000, 245_000))
        assertTrue(TrackMatcher.durationMatches(244_000, 244_000))
    }

    @Test
    fun duration_offByMoreThan3s_rejected() {
        assertFalse(TrackMatcher.durationMatches(244_000, 250_000))
    }

    @Test
    fun duration_unknown_rejected_strictMode() {
        // 严格模式：时长未知不放过，这是串歌的主要来源
        assertFalse(TrackMatcher.durationMatches(0, 244_000))
        assertFalse(TrackMatcher.durationMatches(244_000, 0))
        assertFalse(TrackMatcher.durationMatches(0, 0))
    }

    // ---------------- 三重校验合成 ----------------

    @Test
    fun isMatch_allThreePass() {
        assertTrue(
            TrackMatcher.isMatch(
                c("最伟大的作品", "周杰伦", 244),
                c("最伟大的作品", "周杰伦", 244)
            )
        )
    }

    @Test
    fun isMatch_liveVersion_rejectedByDuration() {
        // 歌名和歌手都一样，但 Live 版时长不同 —— 必须靠时长拦下
        assertFalse(
            TrackMatcher.isMatch(
                c("晴天", "周杰伦", 269),
                c("晴天", "周杰伦", 331)   // Live 版
            )
        )
    }

    @Test
    fun isMatch_coverVersion_rejectedByArtist() {
        // 歌名时长都对，翻唱歌手不同 —— 必须靠歌手拦下
        assertFalse(
            TrackMatcher.isMatch(
                c("晴天", "周杰伦", 269),
                c("晴天", "某翻唱歌手", 269)
            )
        )
    }

    @Test
    fun isMatch_sameNameDifferentSong_rejected() {
        // 《后来》刘若英 vs 《后来》其他同名作品：歌名重合但歌手不同
        assertFalse(
            TrackMatcher.isMatch(
                c("后来", "刘若英", 300),
                c("后来", "莫文蔚", 300)
            )
        )
    }

    // ---------------- 择优 ----------------

    @Test
    fun pickBest_choosesClosestDuration() {
        val target = c("晴天", "周杰伦", 269)
        val got = TrackMatcher.pickBest(
            target,
            listOf(
                c("晴天", "周杰伦", 267),
                c("晴天", "周杰伦", 269),
                c("晴天", "周杰伦", 271)
            )
        )
        assertEquals(269_000L, got?.durationMs)
    }

    @Test
    fun pickBest_returnsNullWhenNothingPasses() {
        val target = c("晴天", "周杰伦", 269)
        assertNull(
            TrackMatcher.pickBest(
                target,
                listOf(
                    c("晴天", "周杰伦", 400),     // 时长不过
                    c("晴天", "别人", 269)        // 歌手不过
                )
            )
        )
    }
}
