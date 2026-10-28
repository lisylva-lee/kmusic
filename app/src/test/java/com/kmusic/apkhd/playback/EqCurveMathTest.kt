package com.kmusic.apkhd.playback

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.ui.components.EqCurveMath
import com.kmusic.apkhd.ui.theme.CoverTint
import com.kmusic.apkhd.ui.theme.MoyinColors
import com.kmusic.apkhd.ui.theme.withPlayerAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EQ 曲线数学 + 取色门禁 + 预设序列化的纯函数用例。
 *
 * 这三块都是「肉眼验不出来、错了却会静默影响听感/观感」的地方：
 *  - 频率轴映射错一点，圆点就落在错的刻度上，拖到 60Hz 实际改的是 90Hz；
 *  - 取色门禁松一点，浅色系封面会取出一块和背景几乎同色的灰，看上去像坏了；
 *  - 预设序列化丢一条，用户存的曲线会无声消失。
 */
class EqCurveMathTest {

    // ── 频率 ↔ 横向位置（对数轴） ──

    @Test
    fun `20Hz 落在最左，20kHz 落在最右`() {
        assertEquals(0f, EqCurveMath.freqToFraction(20f), 1e-4f)
        assertEquals(1f, EqCurveMath.freqToFraction(20_000f), 1e-4f)
    }

    @Test
    fun `超出范围的频率被夹到边界`() {
        assertEquals(0f, EqCurveMath.freqToFraction(5f), 1e-4f)
        assertEquals(1f, EqCurveMath.freqToFraction(40_000f), 1e-4f)
    }

    @Test
    fun `对数轴：每个十倍频程占等宽`() {
        // 20→200、200→2000、2000→20000 各占 1/3 屏宽。
        // 线性轴的话 200Hz 会挤在最左边 1% 里，那正是老滑块看起来「低频段糊成一团」的原因。
        val a = EqCurveMath.freqToFraction(200f)
        val b = EqCurveMath.freqToFraction(2000f)
        assertEquals(1f / 3f, a, 1e-3f)
        assertEquals(2f / 3f, b, 1e-3f)
    }

    @Test
    fun `横向位置与频率互为反函数`() {
        for (hz in listOf(20f, 60f, 230f, 910f, 3600f, 14_000f, 20_000f)) {
            val back = EqCurveMath.fractionToFreq(EqCurveMath.freqToFraction(hz))
            assertEquals(hz, back, hz * 1e-3f)
        }
    }

    @Test
    fun `五个频段从左到右严格递增`() {
        val freqs = listOf(60, 230, 910, 3600, 14_000)
        val xs = freqs.map { EqCurveMath.freqToFraction(it.toFloat()) }
        for (i in 1 until xs.size) {
            assertTrue("第 $i 段没有落在前一段右侧", xs[i] > xs[i - 1])
        }
    }

    // ── 增益换算 ──

    @Test
    fun `毫贝与分贝换算往返不丢精度`() {
        for (mb in listOf(-1500, -100, 0, 200, 1500)) {
            assertEquals(mb, EqCurveMath.dbToMilliBel(EqCurveMath.milliBelToDb(mb)))
        }
    }

    @Test
    fun `dbToMilliBel 四舍五入到整毫贝`() {
        assertEquals(250, EqCurveMath.dbToMilliBel(2.5f))
        assertEquals(-250, EqCurveMath.dbToMilliBel(-2.5f))
        // 2.456 dB → 246 mB，不截断成 245
        assertEquals(246, EqCurveMath.dbToMilliBel(2.456f))
    }

    // ── 频率标签 ──

    @Test
    fun `频率标签只在小数位非零时带小数`() {
        assertEquals("60", EqCurveMath.freqLabel(60))
        assertEquals("3.6k", EqCurveMath.freqLabel(3600))
        assertEquals("14k", EqCurveMath.freqLabel(14_000))
        assertEquals("1k", EqCurveMath.freqLabel(1000))
    }

    // ── 单调插值：不能冲出相邻值之间 ──

    @Test
    fun `插值曲线永远落在相邻频段的值之间`() {
        // 模拟「拉 60Hz +12dB、230Hz -6dB」这种剧烈落差。
        // 普通三次样条会在两点之间拱到 +14dB 以上（曲线骗人），单调插值不会。
        val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val ys = floatArrayOf(12f, -6f, 0f, 3f, 0f)
        val pts = EqCurveMath.monotoneCurve(xs, ys, 64)
        assertEquals(64, pts.size)
        for ((x, y) in pts) {
            assertTrue("x 越界: $x", x >= -1e-3f && x <= 1f + 1e-3f)
            assertTrue("y 冲出范围: $y", y >= -6.001f && y <= 12.001f)
        }
    }

    @Test
    fun `采样点过少时返回空表而不是崩`() {
        assertEquals(0, EqCurveMath.monotoneCurve(floatArrayOf(), floatArrayOf(), 10).size)
        assertEquals(0, EqCurveMath.monotoneCurve(floatArrayOf(0f), floatArrayOf(0f), 10).size)
        assertEquals(
            0,
            EqCurveMath.monotoneCurve(floatArrayOf(0f, 1f), floatArrayOf(0f), 10).size
        )
    }

    @Test
    fun `全平曲线插值后仍是 0`() {
        val xs = floatArrayOf(0f, 0.5f, 1f)
        val ys = floatArrayOf(0f, 0f, 0f)
        EqCurveMath.monotoneCurve(xs, ys, 16).forEach {
            assertEquals(0f, it.second, 1e-4f)
        }
    }

    // ── 预设表：老用户下标不能变 ──

    @Test
    fun `前七套老预设的顺序与曲线一字不改`() {
        // 老用户存的是 eq_preset 下标。这七项一旦被插入/重排，
        // 升级后听到的就是另一条曲线 —— 这条断言是那种 bug 唯一的前置防线。
        val expected = listOf(
            "原声" to intArrayOf(0, 0, 0, 0, 0),
            "流行" to intArrayOf(-100, 200, 400, 200, -100),
            "摇滚" to intArrayOf(400, 150, -100, 200, 400),
            "古典" to intArrayOf(300, 100, -100, 150, 300),
            "人声" to intArrayOf(-200, 100, 500, 300, -100),
            "低音" to intArrayOf(600, 300, 0, -100, -200),
            "高音" to intArrayOf(-200, -100, 0, 300, 600)
        )
        expected.forEachIndexed { i, (name, gains) ->
            assertEquals(name, EqPresets.nameOf(i))
            assertTrue(EqPresets.gainsOf(i).contentEquals(gains))
        }
    }

    @Test
    fun `预设下标全局唯一，分组不改变下标`() {
        val flat = EqPresets.grouped().flatMap { it.second }
        assertEquals(EqPresets.builtInCount, flat.size)
        // 分组遍历出来的 index 必须正好是 0..n-1 各一次
        assertEquals((0 until EqPresets.builtInCount).toList(), flat.map { it.index })
    }

    @Test
    fun `越界下标落到原声而不是抛异常`() {
        assertEquals("原声", EqPresets.nameOf(-1))
        assertEquals("原声", EqPresets.nameOf(999))
    }

    // ── 用户预设：序列化往返 ──

    /**
     * 用户曲线的**格式契约**。
     *
     * 真正的读写走 `org.json`，而 JVM 单测里 org.json 是 not-mocked 桩
     * （调用即抛 RuntimeException，见 app/build.gradle.kts 的 unitTests 配置）。
     * 所以这里只钉「一条曲线合法/不合法的判据」，读写实现复用同一组判据：
     *  - 名字去空白后不能为空；
     *  - 增益必须正好 5 段（少一段就会让 applyCurve 只写 4 段，第 5 段留着上一条的值）。
     */
    private fun isValidCurve(name: String, gains: List<Int>): Boolean =
        name.trim().isNotEmpty() && gains.size == 5

    @Test
    fun `合法曲线通过格式校验`() {
        assertTrue(isValidCurve("通勤", listOf(300, 200, 0, 150, 200)))
        assertTrue(isValidCurve("夜间", listOf(-200, -100, 0, -100, -200)))
    }

    @Test
    fun `残缺曲线被判非法`() {
        assertFalse(isValidCurve("", listOf(0, 0, 0, 0, 0)))
        assertFalse(isValidCurve("   ", listOf(0, 0, 0, 0, 0)))
        assertFalse(isValidCurve("只有四段", listOf(1, 2, 3, 4)))
        assertFalse(isValidCurve("多了一段", listOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `名字长度上限是 24 字`() {
        // 和 UserEqPresets.MAX_NAME_LEN 对齐：超长名字在界面上会挤掉右边的增益摘要
        assertEquals(24, UserEqPresets.MAX_NAME_LEN)
        assertEquals(24, "一".repeat(40).take(UserEqPresets.MAX_NAME_LEN).length)
    }

    @Test
    fun `用户曲线上限 20 条`() {
        assertEquals(20, UserEqPresets.MAX_PRESETS)
    }

    // ── 音效状态判定（A4） ──

    @Test
    fun `省电播放时一律报已旁路，哪怕三项都开着`() {
        val s = FxStatus.resolve(
            offload = true, dsdPlaying = false, dsdBypassEq = true,
            wanted = 3, held = 3, starved = false
        )
        assertEquals(FxStatus.Bypassed(FxStatus.Bypassed.Reason.OFFLOAD), s)
    }

    @Test
    fun `DSD 旁路优先于池位状态`() {
        val s = FxStatus.resolve(
            offload = false, dsdPlaying = true, dsdBypassEq = true,
            wanted = 1, held = 0, starved = true
        )
        assertEquals(FxStatus.Bypassed(FxStatus.Bypassed.Reason.DSD), s)
    }

    @Test
    fun `用户允许 DSD 不旁路时照常生效`() {
        val s = FxStatus.resolve(
            offload = false, dsdPlaying = true, dsdBypassEq = false,
            wanted = 1, held = 1, starved = false
        )
        assertTrue(s is FxStatus.Active)
    }

    @Test
    fun `一项没开是 Idle，不是旁路`() {
        assertEquals(
            FxStatus.Idle,
            FxStatus.resolve(false, false, true, wanted = 0, held = 0, starved = false)
        )
    }

    @Test
    fun `暂停中归还池位时不谎报全关`() {
        // 均衡器开着、点了暂停：App 会主动把音效槽还回去，held 归零。
        // 这时候说「三项全关」和界面上的开关自相矛盾，必须是 Suspended。
        assertEquals(
            FxStatus.Suspended,
            FxStatus.resolve(
                offload = false, dsdPlaying = false, dsdBypassEq = true,
                wanted = 1, held = 0, starved = false, paused = true
            )
        )
        // 播放中同样的参数、held 仍是 0，且不是暂停 → 那才是真的没挂上
        assertEquals(
            FxStatus.Bypassed(FxStatus.Bypassed.Reason.ALL_OFF),
            FxStatus.resolve(
                offload = false, dsdPlaying = false, dsdBypassEq = true,
                wanted = 1, held = 0, starved = false, paused = false
            )
        )
    }

    @Test
    fun `池满优先于暂停`() {
        // 池被别的 App 占满 + 暂停：这属于 starved（恢复播放会重试），不是主动归还
        assertEquals(
            FxStatus.PoolStarved,
            FxStatus.resolve(
                offload = false, dsdPlaying = false, dsdBypassEq = true,
                wanted = 2, held = 0, starved = true, paused = true
            )
        )
    }

    @Test
    fun `想开但抢不到池位报 PoolStarved`() {
        assertEquals(
            FxStatus.PoolStarved,
            FxStatus.resolve(false, false, true, wanted = 2, held = 0, starved = true)
        )
    }

    @Test
    fun `开着却占用为 0 报全关旁路`() {
        assertEquals(
            FxStatus.Bypassed(FxStatus.Bypassed.Reason.ALL_OFF),
            FxStatus.resolve(false, false, true, wanted = 2, held = 0, starved = false)
        )
    }

    // ── 取色门禁：不合格必须返回 null（由调用方回退 accent） ──

    @Test
    fun `近背景色被门禁拒绝`() {
        // 深色底 #141416 上的 #171719 —— 人眼几乎分不出，
        // 这种「取到了但看不出来」比取不到更糟：用户以为功能没生效。
        val tint = CoverTint.polishColor(0xFF171719.toInt(), 0xFF141416.toInt(), true)
        assertNull(tint)
    }

    @Test
    fun `灰白色的低饱和色被拒绝`() {
        val tint = CoverTint.polishColor(0xFF8A8A8A.toInt(), 0xFF141416.toInt(), true)
        assertNull(tint)
    }

    @Test
    fun `高饱和的深红在深色底上通过门禁`() {
        // 旧实现用绝对亮度 0.42 当门槛，会把这个颜色判失败（luminance≈0.27），
        // 于是所有深红/深蓝封面都用不上取色 —— 这条用例锁住「改用对比度」的修复。
        val tint = CoverTint.polishColor(0xFFCC2222.toInt(), 0xFF141416.toInt(), true)
        assertTrue("纯红应该通过门禁", tint != null)
        // 已经够饱和够亮，不该被改色（改了就是「取色」变成「造色」）
        assertEquals(0xFFCC2222.toInt(), tint!!.toArgb())
    }

    @Test
    fun `中等饱和的颜色被抬饱和后明显更艳`() {
        // #A85A5A 饱和度约 0.46，低于目标 0.52 —— 应该被抬一档
        val raw = 0xFFA85A5A.toInt()
        val tint = CoverTint.polishColor(raw, 0xFF141416.toInt(), true)
        assertTrue(tint != null)
        val (_, satRaw, _) = CoverTint.rgbToHsv(0xA8, 0x5A, 0x5A)
        val (_, satOut, _) = CoverTint.rgbToHsv(
            (tint!!.toArgb() shr 16) and 0xFF,
            (tint.toArgb() shr 8) and 0xFF,
            tint.toArgb() and 0xFF
        )
        assertTrue("饱和度没被抬高: $satRaw -> $satOut", satOut > satRaw)
    }

    @Test
    fun `浅色主题一律不取色`() {
        assertNull(
            CoverTint.polishColor(0xFFCC2222.toInt(), 0xFFF6F3EE.toInt(), false)
        )
    }

    @Test
    fun `播放页调色板替换后 onAccent 与实际底色对比充足`() {
        val base = MoyinColors(
            isDark = true,
            bg = androidx.compose.ui.graphics.Color(0xFF141416),
            s1 = androidx.compose.ui.graphics.Color(0xFF1C1C20),
            s2 = androidx.compose.ui.graphics.Color(0xFF242429),
            s3 = androidx.compose.ui.graphics.Color(0xFF2E2E33),
            s4 = androidx.compose.ui.graphics.Color(0xFF3A3A40),
            line = androidx.compose.ui.graphics.Color(0xFF2A2A2E),
            t1 = androidx.compose.ui.graphics.Color(0xFFF2F2F4),
            t2 = androidx.compose.ui.graphics.Color(0xFFA8A8B0),
            t3 = androidx.compose.ui.graphics.Color(0xFF6E6E78),
            accent = androidx.compose.ui.graphics.Color(0xFF7C5CFF),
            accentDim = androidx.compose.ui.graphics.Color(0x267C5CFF),
            accentLine = androidx.compose.ui.graphics.Color(0x6B7C5CFF),
            onAccent = androidx.compose.ui.graphics.Color.White,
            coverGrad = listOf(
                androidx.compose.ui.graphics.Color(0xFF2B2B33),
                androidx.compose.ui.graphics.Color(0xFF141416)
            ),
            glass = androidx.compose.ui.graphics.Color(0x13FFFFFF),
            glassBorder = androidx.compose.ui.graphics.Color(0x38FFFFFF),
            lineStrong = androidx.compose.ui.graphics.Color(0x1FFFFFFF),
            radiusCard = 20.dp,
            titleSerif = true,
            numeral = androidx.compose.ui.graphics.Color(0xFF6E6E78)
        )
        // 浅色强调（黄绿）：白字会看不清，必须切成深字
        val light = base.withPlayerAccent(androidx.compose.ui.graphics.Color(0xFFC9E86B))
        assertTrue(light.onAccent.luminance() < 0.5f)
        // 深色强调：仍用白字
        val dark = base.withPlayerAccent(androidx.compose.ui.graphics.Color(0xFF3A2A8C))
        assertEquals(androidx.compose.ui.graphics.Color.White, dark.onAccent)
        // null 时原样返回（不回退到别的色）
        assertEquals(base, base.withPlayerAccent(null))
    }

    @Test
    fun `accentDim 与 accentLine 跟着强调色重算`() {
        val base = MoyinColors(
            isDark = true,
            bg = androidx.compose.ui.graphics.Color.Black,
            s1 = androidx.compose.ui.graphics.Color.Black,
            s2 = androidx.compose.ui.graphics.Color.Black,
            s3 = androidx.compose.ui.graphics.Color.Black,
            s4 = androidx.compose.ui.graphics.Color.Black,
            line = androidx.compose.ui.graphics.Color.Black,
            t1 = androidx.compose.ui.graphics.Color.White,
            t2 = androidx.compose.ui.graphics.Color.White,
            t3 = androidx.compose.ui.graphics.Color.Gray,
            accent = androidx.compose.ui.graphics.Color(0xFF7C5CFF),
            accentDim = androidx.compose.ui.graphics.Color(0x267C5CFF),
            accentLine = androidx.compose.ui.graphics.Color(0x6B7C5CFF),
            onAccent = androidx.compose.ui.graphics.Color.White,
            coverGrad = listOf(androidx.compose.ui.graphics.Color.Black),
            glass = androidx.compose.ui.graphics.Color(0x13FFFFFF),
            glassBorder = androidx.compose.ui.graphics.Color(0x38FFFFFF),
            lineStrong = androidx.compose.ui.graphics.Color(0x1FFFFFFF),
            radiusCard = 20.dp,
            titleSerif = true,
            numeral = androidx.compose.ui.graphics.Color.Gray
        )
        val tint = androidx.compose.ui.graphics.Color(0xFF2E7D32)
        val out = base.withPlayerAccent(tint)
        // 半透明层的 RGB 必须同源，否则出现「亮条 + 旧光晕」
        assertEquals(
            tint.copy(alpha = out.accentDim.alpha).toArgb(),
            out.accentDim.toArgb()
        )
        assertEquals(
            tint.copy(alpha = out.accentLine.alpha).toArgb(),
            out.accentLine.toArgb()
        )
        assertEquals(tint, out.accentFill)
    }
}
