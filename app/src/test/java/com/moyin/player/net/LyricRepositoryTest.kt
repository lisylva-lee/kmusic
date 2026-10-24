package com.kmusic.apkhd.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词解析测试 —— 覆盖 LRC 时间轴格式差异，
 * 这些格式 lrclib 实际都会返回，解析错就会导致歌词高亮/滚动不工作。
 */
class LyricRepositoryTest {

    private val repo = LyricRepository()

    @Test
    fun parse_syncedLyrics_basicFormat() {
        val lrc = "[00:12.34]第一行歌词\n[00:15.60]第二行歌词\n[01:02.10]第三行歌词"
        val lines = repo.parseLyrics(lrc, "")
        assertEquals(3, lines.size)
        assertEquals(12_340L, lines[0].timeMs)
        assertEquals("第一行歌词", lines[0].text)
        assertEquals(15_600L, lines[1].timeMs)
        assertEquals(62_100L, lines[2].timeMs)
    }

    @Test
    fun parse_multipleTagsOnOneLine_expands() {
        // 同一句歌词挂多个时间标签（副歌重复）是常见格式
        val lrc = "[00:10.00][01:20.00]重复的副歌"
        val lines = repo.parseLyrics(lrc, "")
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(80_000L, lines[1].timeMs)
        assertEquals("重复的副歌", lines[0].text)
        assertEquals("重复的副歌", lines[1].text)
    }

    @Test
    fun parse_threeDigitMillis() {
        val lines = repo.parseLyrics("[00:05.123]三位毫秒", "")
        assertEquals(5_123L, lines[0].timeMs)
    }

    @Test
    fun parse_resultIsSortedByTime() {
        val lrc = "[00:30.00]后\n[00:10.00]前"
        val lines = repo.parseLyrics(lrc, "")
        assertEquals("前", lines[0].text)
        assertEquals("后", lines[1].text)
    }

    @Test
    fun parse_plainLyricsWithoutTimestamps_evenlySpaced() {
        val plain = "第一句\n第二句\n第三句"
        val lines = repo.parseLyrics("", plain)
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(4_000L, lines[1].timeMs)
        assertEquals(8_000L, lines[2].timeMs)
    }

    @Test
    fun parse_emptyInput_returnsEmpty() {
        assertTrue(repo.parseLyrics("", "").isEmpty())
    }

    @Test
    fun parse_ignoresBlankAndTagOnlyLines() {
        val lrc = "[00:01.00]\n[00:02.00]有内容\n\n[ar:歌手]\n[00:03.00]  "
        val lines = repo.parseLyrics(lrc, "")
        assertEquals(1, lines.size)
        assertEquals("有内容", lines[0].text)
    }
}
