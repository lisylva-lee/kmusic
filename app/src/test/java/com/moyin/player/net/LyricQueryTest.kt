package com.kmusic.apkhd.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词查询的归一化与校验。
 *
 * 为什么值得单独测：这块出问题**不会崩**，只会静默地
 * 「明明有歌词却查不出来」或者「配上了别人的词」—— 后者用户根本发现不了。
 */
class LyricQueryTest {

    // ---------------- 去括号 ----------------

    @Test
    fun `去掉中英文括号后缀`() {
        assertEquals("晴天", LyricQuery.stripBrackets("晴天 (Live)"))
        assertEquals("晴天", LyricQuery.stripBrackets("晴天（Live版）"))
        assertEquals("晴天", LyricQuery.stripBrackets("晴天 [Live]"))
        assertEquals("夜曲", LyricQuery.stripBrackets("【高音质】夜曲"))
    }

    @Test
    fun `去掉 feat 尾巴`() {
        assertEquals("以父之名", LyricQuery.stripBrackets("以父之名 feat. 某人"))
        assertEquals("屋顶", LyricQuery.stripBrackets("屋顶 ft. 温岚"))
    }

    @Test
    fun `名字本身没后缀时保持原样`() {
        assertEquals("七里香", LyricQuery.stripBrackets("七里香"))
    }

    // ---------------- 取第一位歌手 ----------------

    @Test
    fun `多歌手取第一个`() {
        assertEquals("周杰伦", LyricQuery.firstArtist("周杰伦/方文山"))
        assertEquals("周杰伦", LyricQuery.firstArtist("周杰伦、温岚"))
        assertEquals("周杰伦", LyricQuery.firstArtist("周杰伦 & 温岚"))
        assertEquals("周杰伦", LyricQuery.firstArtist("周杰伦"))
    }

    // ---------------- 候选 ----------------

    @Test
    fun `候选按干净优先且去重`() {
        val c = LyricQuery.candidates("晴天 (Live)", "周杰伦")
        assertTrue(c.isNotEmpty())
        assertEquals("晴天" to "周杰伦", c.first())
        assertEquals(c.size, c.distinct().size)
    }

    @Test
    fun `空歌名不产生候选`() {
        assertTrue(LyricQuery.candidates("   ", "周杰伦").isEmpty())
    }

    // ---------------- 校验（防配错词） ----------------

    @Test
    fun `同名同歌手通过`() {
        assertTrue(LyricQuery.verify("晴天", "周杰伦", "晴天", "周杰伦"))
    }

    @Test
    fun `歌名只是包含关系时不算命中`() {
        // 关键用例：如果只判「包含」，晴天娃娃 / 晴天(Live) 之类会被错认成晴天
        assertFalse(LyricQuery.verify("晴天娃娃", "周杰伦", "晴天", "周杰伦"))
        assertFalse(LyricQuery.verify("晴天", "周杰伦", "晴天娃娃", "周杰伦"))
    }

    @Test
    fun `歌手不同不算命中`() {
        assertFalse(LyricQuery.verify("晴天", "蔡依林", "晴天", "周杰伦"))
    }

    @Test
    fun `候选歌手缺失或更长都能通过`() {
        // 源之间歌手字段常有出入（缺歌手 / 多合作歌手），不能因此把真歌词丢掉
        assertTrue(LyricQuery.verify("晴天", "", "晴天", "周杰伦"))
        assertTrue(LyricQuery.verify("晴天", "周杰伦/温岚", "晴天", "周杰伦"))
    }

    @Test
    fun `我们这边歌手缺失时不挑歌手`() {
        assertTrue(LyricQuery.verify("晴天", "随便谁", "晴天", ""))
    }

    @Test
    fun `带后缀的返回也算命中`() {
        assertTrue(LyricQuery.verify("晴天 (Live)", "周杰伦", "晴天", "周杰伦"))
    }

    // ---------------- 书名号 / 横杠补充说明（酷我高频杂质）----------------

    @Test
    fun `去掉书名号后缀`() {
        // 实测：'Gee-《非诚勿扰》节目插曲' 查 lrclib → 404，'Gee' → 200
        assertEquals("Gee", LyricQuery.stripBrackets("Gee-《非诚勿扰》节目插曲"))
        assertEquals("财神来到我家门", LyricQuery.stripBrackets("财神来到我家门-原曲：夫妻双双把家还"))
    }

    @Test
    fun `书名号在中间也要去`() {
        assertEquals("晴天", LyricQuery.stripBrackets("《叶惠美》晴天"))
    }

    @Test
    fun `带连字符的正常歌名不被砍残`() {
        // 横杠前不足 2 字符时不切，避免误伤
        assertTrue(LyricQuery.stripBrackets("X-Y").isNotBlank())
    }

    @Test
    fun `杂质歌名能通过校验`() {
        assertTrue(
            LyricQuery.verify("Gee", "少女时代", "Gee-《非诚勿扰》节目插曲", "少女时代")
        )
    }

    // ---------------- 繁简归一（lrclib 上大量条目是繁体）----------------

    @Test
    fun `繁体歌手能匹配上简体`() {
        // 实测：lrclib 的 '野心家' 6 条候选里 4 条是繁体
        assertTrue(LyricQuery.verify("野心家", "張靚穎", "野心家", "张靓颖"))
        assertTrue(LyricQuery.verify("野心家 (臻品母帶)", "張靚穎", "野心家", "张靓颖"))
    }

    @Test
    fun `繁体歌名能匹配上简体`() {
        assertTrue(
            LyricQuery.verify(
                "野心家(《灼灼韶華風禾起》主題曲)", "張靚穎", "野心家-《灼灼韶华》影视剧主题曲", "张靓颖"
            )
        )
    }

    @Test
    fun `歌手带英文后缀仍能匹配`() {
        // '张靓颖 - Jane Zhang' 这种，norm 后是「张靓颖janezhang」，包含关系成立
        assertTrue(LyricQuery.verify("野心家", "张靓颖 - Jane Zhang", "野心家", "张靓颖"))
    }

    @Test
    fun `纯英文名不因繁简规则误判`() {
        // 'Jane Zhang' 里没有「张靓颖」的任何字 → 仍必须否（这是别人的署名）
        assertFalse(LyricQuery.verify("野心家", "Jane Zhang", "野心家", "张靓颖"))
    }

    @Test
    fun `繁简归一不影响不同歌的区分`() {
        assertFalse(LyricQuery.verify("晴天", "張學友", "晴天", "周杰伦"))
        assertFalse(LyricQuery.verify("愛上你", "劉宇寧", "爱上", "刘宇宁"))
    }

    // ---------------- 时长只用于排序 ----------------

    @Test
    fun `时长未知不参与优选但不否决`() {
        val unknown = LyricQuery.durationDistance(0, 240)
        val close = LyricQuery.durationDistance(240, 242)
        assertTrue(close < unknown)
        // 候选侧时长未知 → 排到后面（比「已知且相差 5 秒」靠后），但不否决
        assertTrue(LyricQuery.durationDistance(240, 0) > LyricQuery.durationDistance(240, 245))
    }
}

/**
 * OST / 原声带署名。
 *
 * 实测实例：酷我「爱上 / 刘宇宁」，网易云把这首 OST 的歌手登记成「玉簟秋/OST」。
 * 不放这一类进去，明明有 754 字符现成歌词的歌会被否掉。
 *
 * 但**不能**因此放松到能配错歌 —— 所以下面同时钉了一批必须仍然被否的反例。
 */
class LyricQueryCollectiveArtistTest {

    @Test
    fun `OST 署名不作为歌手判据`() {
        assertTrue(LyricQuery.verify("爱上", "玉簟秋/OST", "爱上-《玉簟秋》电视剧执爱主题曲", "刘宇宁"))
        assertTrue(LyricQuery.verify("某首歌", "某某影视原声带", "某首歌", "刘宇宁"))
    }

    @Test
    fun `原声带 电视剧 电影 主题曲都算集体署名`() {
        assertTrue(LyricQuery.isCollectiveArtist("玉簟秋/OST"))
        assertTrue(LyricQuery.isCollectiveArtist("某剧 电视剧原声带"))
        assertTrue(LyricQuery.isCollectiveArtist("某某电影"))
        assertTrue(LyricQuery.isCollectiveArtist("某某 主题曲"))
        assertTrue(LyricQuery.isCollectiveArtist("某某 live版"))
    }

    @Test
    fun `具体歌手不算集体署名`() {
        assertFalse(LyricQuery.isCollectiveArtist("刘宇宁"))
        assertFalse(LyricQuery.isCollectiveArtist("周杰伦"))
        assertFalse(LyricQuery.isCollectiveArtist(""))
    }

    // ---------- 反例：放宽后必须仍然否得掉 ----------

    @Test
    fun `同名不同人仍然要否（不因 OST 规则放松）`() {
        assertFalse(LyricQuery.verify("爱上", "小宇-宋念宇", "爱上", "刘宇宁"))
        assertFalse(LyricQuery.verify("晴天", "蔡依林", "晴天", "周杰伦"))
    }

    @Test
    fun `歌名不完全相等仍然要否`() {
        assertFalse(LyricQuery.verify("爱上你", "某OST", "爱上", "刘宇宁"))
        assertFalse(LyricQuery.verify("爱上", "某OST", "爱上你", "刘宇宁"))
        assertFalse(LyricQuery.verify("晴天娃娃", "某原声带", "晴天", "周杰伦"))
    }

    @Test
    fun `OST 规则不影响普通歌的匹配`() {
        assertTrue(LyricQuery.verify("美人鱼", "林俊杰", "美人鱼", "林俊杰"))
        assertFalse(LyricQuery.verify("美人鱼", "林俊杰", "可惜没如果", "林俊杰"))
    }
}

/**
 * gdstudio 按名搜词的判据复用同一套 [LyricQuery.verify]。
 * 这些用例来自实测的 gdstudio 真实返回 —— 特别是「同名不同人」的情况，
 * 一旦校验松一点就会把别人的词配上来。
 */
class LyricQueryGdStudioTest {

    @Test
    fun `网易云歌手能匹配上`() {
        // gdstudio 实测：'妥协' / '余佳运'、'妥协' / 'en' 都不该命中 '张含韵'
        assertFalse(LyricQuery.verify("妥协", "余佳运", "妥协 (Live)", "张含韵&姚晓棠"))
        assertFalse(LyricQuery.verify("妥协", "en", "妥协 (Live)", "张含韵&姚晓棠"))
    }

    @Test
    fun `同名同人不同版本不能混`() {
        // '岁月如笔写春秋' 在网易云只有 '一寸光年酱' 和 '叶芷涵' 版，
        // 而我们要的是 '河南三妹5233' —— 必须否掉，宁可没词也不配错
        assertFalse(LyricQuery.verify("岁月如笔写春秋", "一寸光年酱", "岁月如笔写春秋", "河南三妹5233"))
        assertFalse(LyricQuery.verify("岁月如笔写春秋（河南三妹）", "叶芷涵", "岁月如笔写春秋", "河南三妹5233"))
    }

    @Test
    fun `同歌手不同名不能混`() {
        assertFalse(LyricQuery.verify("吹你吹过的晚风", "另一个歌手", "吹你吹过的晚风", "南宫心绪"))
    }

    @Test
    fun `目标歌手本身是多人时取第一位也能匹配`() {
        assertTrue(LyricQuery.verify("妥协", "张含韵", "妥协 (Live)", "张含韵&姚晓棠"))
    }
}
