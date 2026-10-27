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

    // ---------------- 繁简互转（AI 歌单真因） ----------------
    //
    // 模型一遇「粤语 / 港台 / 80 年代」就整片用繁体返回（千千闕歌|陳慧嫻），
    // 而酷我曲库是简体。实测 12 首里 11 首因此搜不到，界面报「只有 0 首能播」。
    // 下面这组用例就是那次事故的回归测试。

    @Test
    fun t2s_convertsCommonTraditionalChars() {
        assertEquals("千千阙歌", T2S.convert("千千闕歌"))
        assertEquals("陈慧娴", T2S.convert("陳慧嫻"))
        assertEquals("海阔天空", T2S.convert("海闊天空"))
        assertEquals("风继续吹", T2S.convert("風繼續吹"))
        assertEquals("张国荣", T2S.convert("張國榮"))
        assertEquals("谭咏麟", T2S.convert("譚詠麟"))
        assertEquals("夕阳之歌", T2S.convert("夕陽之歌"))
        assertEquals("梅艳芳", T2S.convert("梅艷芳"))
    }

    @Test
    fun t2s_leavesSimplifiedUntouched() {
        // 已经是简体的必须原样返回，否则会把正常歌名转坏
        for (s in listOf("千千阙歌", "陈慧娴", "海阔天空", "周杰伦", "晴天", "Hello")) {
            assertEquals(s, T2S.convert(s))
        }
        // 日文汉字（新字体）不该被当成繁体转掉 —— 米津玄師 是日本歌手的本名写法
        assertEquals("米津玄師", T2S.convert("米津玄師"))
    }

    @Test
    fun t2s_noopReturnsSameInstance() {
        // 没有繁体字时不该额外分配（findTrackByName 每首都会调它）
        val s = "七里香"
        assertTrue("同引用才算零分配", s === T2S.convert(s))
    }

    @Test
    fun t2s_rulesAreConsistent() {
        // 表错位会把歌名转成乱码，而现象极难反推。这里做一致性自检：
        // 把常见港台歌名整串转一遍，长度必须不变（1:1 单字映射，永不变长变短）
        val samples = listOf(
            "千千闕歌", "陳慧嫻", "海闊天空", "風繼續吹", "張國榮", "譚詠麟",
            "夕陽之歌", "梅艷芳", "鐵血丹心", "羅文", "講不出再見", "餓狼傳說"
        )
        for (s in samples) {
            assertEquals("长度必须保持：$s", s.length, T2S.convert(s).length)
        }
    }

    @Test
    fun nameMatches_traditionalModelOutputHitsSimplifiedCatalog() {
        // 左：模型给的繁体；右：酷我返回的简体
        assertTrue(TrackMatcher.nameMatches("千千闕歌", "千千阙歌"))
        assertTrue(TrackMatcher.nameMatches("海闊天空", "海阔天空-《九五2班》网络电影插曲"))
        assertTrue(TrackMatcher.nameMatches("風繼續吹", "风继续吹-《纵横四海》电影主题曲"))
        assertTrue(TrackMatcher.nameMatches("講不出再見", "讲不出再见"))
    }

    @Test
    fun artistMatches_traditionalModelOutputHitsSimplifiedCatalog() {
        assertTrue(TrackMatcher.artistMatches("陳慧嫻", "陈慧娴"))
        assertTrue(TrackMatcher.artistMatches("張國榮", "张国荣"))
        assertTrue(TrackMatcher.artistMatches("譚詠麟", "谭咏麟"))
        // 多人署名仍要走 token 比对
        assertTrue(TrackMatcher.artistMatches("羅文", "罗文&甄妮"))
    }

    @Test
    fun traditionalStillRejectsWrongSong() {
        // 繁简归一不能把「不该匹配」变成「匹配」—— 防串歌的底线不能松
        assertFalse(TrackMatcher.nameMatches("千千闕歌", "千千晚星"))
        assertFalse(TrackMatcher.artistMatches("張國榮", "陈慧娴"))
        assertFalse(TrackMatcher.nameMatches("晴天", "晴天娃娃"))
        // 反过来：括号里的版次标注不该妨碍命中
        assertTrue(TrackMatcher.nameMatches("千千闕歌", "千千阙歌 (Live)"))
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

    // ---------------- 翻唱 / 合作署名（实测踩坑回归） ----------------

    @Test
    fun pickBest_prefersOriginalOverCover() {
        // 酷我把翻唱排在原唱前面 —— 必须仍然挑原唱
        val target = c("泡沫", "邓紫棋", 265)
        val best = TrackMatcher.pickBest(
            target,
            listOf(
                c("泡沫", "张新航&邓 紫 棋", 265), // 翻唱，时长都一样
                c("泡沫", "邓紫棋", 264)            // 原唱
            )
        )
        assertEquals("邓紫棋", best?.artist)
    }

    @Test
    fun pickBest_usesCoverOnlyWhenNoOriginal() {
        // 没有原唱时才退而用合作版本（折中规则的收益）
        val target = c("泡沫", "邓紫棋", 265)
        val best = TrackMatcher.pickBest(
            target,
            listOf(c("泡沫", "张新航&邓 紫 棋", 265))
        )
        assertEquals("张新航&邓 紫 棋", best?.artist)
    }

    @Test
    fun artist_coverWithTargetSecond_passes() {
        // 折中：目标歌手在第 2 位算命中
        assertTrue(TrackMatcher.artistMatches("邓紫棋", "张新航&邓紫棋"))
        // 但目标歌手排到第 3 位就不再放过（噪声太大）
        assertFalse(TrackMatcher.artistMatches("邓紫棋", "甲&乙&邓紫棋"))
    }
}
