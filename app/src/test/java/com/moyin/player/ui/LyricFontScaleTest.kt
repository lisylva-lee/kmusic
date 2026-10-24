package com.kmusic.apkhd.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词字号倍率测试 —— 直接调用产品代码里的 [lyricLineSp] / [coerceLyricScale]。
 *
 * ⚠️ 上一版这个测试是**自己复制了一份字号公式**来测（`fun currentLineSp(scale) =
 * 13f * scale`），等于测了个副本，产品代码里真实的括号位置一行都没覆盖到。
 * 结果线上出了「越调字体越小」的 bug，测试却全绿。
 * 教训：测试必须调用被测代码本身，不能复制它的实现。
 */
class LyricFontScaleTest {

    @Test
    fun 默认倍率下当前行更大() {
        // 产品值：当前行 15sp / 其余 12sp（曾经是 13/10，宽屏重排时调大了）
        assertEquals("当前行默认 15sp", 15, lyricLineSp(on = true, scale = 1.0f))
        assertEquals("其余行默认 12sp", 12, lyricLineSp(on = false, scale = 1.0f))
    }

    /**
     * 这条就是线上 bug 的复现用例。
     *
     * 错误写法 `if (on) 13f else 10f * scale`：1.3 倍时两边都是 13，
     * 1.8 倍时其余行 18 > 当前行 13 —— 用户看到的就是「怎么还缩小」。
     */
    @Test
    fun 任意倍率下当前行都必须严格更大() {
        var s = LYRIC_SCALE_MIN
        while (s <= LYRIC_SCALE_MAX + 0.001f) {
            val cur = lyricLineSp(on = true, scale = s)
            val other = lyricLineSp(on = false, scale = s)
            assertTrue(
                "倍率 " + "%.1f".format(s) + " 时当前行($cur) 必须大于其余行($other)",
                cur > other
            )
            s += LYRIC_SCALE_STEP
        }
    }

    @Test
    fun 倍率对当前行和其余行同时生效() {
        assertEquals("1.5 倍当前行", 23, lyricLineSp(on = true, scale = 1.5f))   // 22.5 → 23
        assertEquals("1.5 倍其余行", 18, lyricLineSp(on = false, scale = 1.5f))  // 18
        assertEquals("1.8 倍当前行", 27, lyricLineSp(on = true, scale = 1.8f))   // 27
        assertEquals("1.8 倍其余行", 22, lyricLineSp(on = false, scale = 1.8f))  // 21.6 → 22
    }

    @Test
    fun 缩到下限时层级仍然分明() {
        assertEquals(12, lyricLineSp(on = true, scale = LYRIC_SCALE_MIN))   // 15 × 0.8 = 12
        assertEquals(10, lyricLineSp(on = false, scale = LYRIC_SCALE_MIN))  // 12 × 0.8 = 9.6 → 10
    }

    @Test
    fun 倍率夹紧在范围内() {
        assertEquals(0.8f, coerceLyricScale(0.3f), 0.001f)
        assertEquals(1.8f, coerceLyricScale(3.0f), 0.001f)
        assertEquals(1.3f, coerceLyricScale(1.3f), 0.001f)
    }

    @Test
    fun 步长累加不会越界() {
        var s = 1.0f
        repeat(20) { s = coerceLyricScale(s + LYRIC_SCALE_STEP) }
        assertEquals(1.8f, s, 0.001f)

        var d = 1.0f
        repeat(20) { d = coerceLyricScale(d - LYRIC_SCALE_STEP) }
        assertEquals(0.8f, d, 0.001f)
    }
}
