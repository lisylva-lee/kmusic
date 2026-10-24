package com.kmusic.apkhd.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 酷我歌词接口（m.kuwo.cn newh5）的解析测试。
 *
 * 背景：曲目是酷我源的，歌词却只去 lrclib / gdstudio 找 —— 热榜 50 首里
 * 19 首酷我明明有词却显示「暂无歌词」。现在酷我歌词排第一顺位，
 * 这里钉住三件事：
 * 1. 时间换算（秒 → mm:ss.cc）不能错，且 236.6 要补成 56.60 而不是 56.6
 * 2. 空白行必须丢掉，否则 UI 会出现空歌词行
 * 3. 异常输入一律返回 null，不能抛异常把整个取词协程带崩
 *
 * fixture: kuwo_lyric_658118407.json（`爱上` / 刘宇宁，真实响应裁剪）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KuwoLyricTest {

    private val client = KuwoClient()

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("kuwo_lyric_658118407.json")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `真实响应转成标准LRC`() {
        val lrc = client.lrclistToLrc(fixture())!!
        val lines = lrc.trim().lines()
        // fixture 里 9 条，最后一条是纯空格 → 应该只剩 8 条
        assertEquals(8, lines.size)
        assertEquals("[00:00.00]爱上 (《玉簟秋》电视剧执爱主题曲) - 刘宇宁", lines[0])
        assertEquals("[00:00.96]词：王耀光/张鹏鹏", lines[1])
    }

    @Test
    fun `秒转时间戳 - 跨分钟与补零`() {
        val lrc = client.lrclistToLrc(fixture())!!
        // 223.36s = 3 分 43.36 秒
        assertTrue("应含 03:43.36", lrc.contains("[03:43.36]你像水上明月 在心上飘荡"))
        assertTrue("应含 03:48.99", lrc.contains("[03:48.99]爱从来没有形状"))
        // 233.09 → 09 必须补零，不能是 3:53.9
        assertTrue("百分秒要补零", lrc.contains("[03:53.09]当遗憾终于静静安放"))
        // 236.6 是 56 秒 60 厘秒 = 57.00 之前的最后一位，必须写全两位
        assertTrue("236.6 要补成 03:56.60", lrc.contains("[03:56.60]看见你的模样"))
    }

    @Test
    fun `生成的LRC能被parseLyrics原样解析`() {
        // 这条最有价值：证明「酷我 JSON → LRC 文本 → LyricLine」整条链路通，
        // 而不是只验证了字符串拼接好看。
        val lrc = client.lrclistToLrc(fixture())!!
        val lines = LyricRepository().parseLyrics(lrc, "")
        assertEquals(8, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(960L, lines[1].timeMs)
        assertEquals(236_600L, lines.last().timeMs)
        assertEquals("看见你的模样", lines.last().text)
        // 时间必须严格递增，否则滚动高亮会乱跳
        lines.zipWithNext().forEach { (a, b) -> assertTrue(a.timeMs < b.timeMs) }
    }

    @Test
    fun `异常输入返回null而不抛异常`() {
        assertNull("非 JSON", client.lrclistToLrc("<html>403</html>"))
        assertNull("空对象", client.lrclistToLrc("{}"))
        assertNull("没有 data", client.lrclistToLrc("""{"code":200}"""))
        assertNull("空 lrclist", client.lrclistToLrc("""{"data":{"lrclist":[]}}"""))
        assertNull("全是空白行", client.lrclistToLrc("""{"data":{"lrclist":[{"lineLyric":"  ","time":"1.0"}]}}"""))
    }

    @Test
    fun `时间字段缺失或非法的行被跳过`() {
        val raw = """{"data":{"lrclist":[
            {"lineLyric":"正常行","time":"12.5"},
            {"lineLyric":"没有时间","time":""},
            {"lineLyric":"时间非法","time":"abc"},
            {"lineLyric":"","time":"20.0"},
            {"lineLyric":"尾行","time":"30"}
        ]}}"""
        val lines = client.lrclistToLrc(raw)!!.trim().lines()
        assertEquals(2, lines.size)
        assertEquals("[00:12.50]正常行", lines[0])
        assertEquals("[00:30.00]尾行", lines[1])
    }
}
