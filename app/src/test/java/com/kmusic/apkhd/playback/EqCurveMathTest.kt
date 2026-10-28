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
        //
        // v2.3.5 起曲线是十段，但**源数据仍是当年那五段**，经 EqBands.resample 插值。
        // 所以这里连着比对两件事：顺序没动 + 插值出来的确实是那根曲线的新刻度采样。
        val legacy = listOf(
            "原声" to intArrayOf(0, 0, 0, 0, 0),
            "流行" to intArrayOf(-100, 200, 400, 200, -100),
            "摇滚" to intArrayOf(400, 150, -100, 200, 400),
            "古典" to intArrayOf(300, 100, -100, 150, 300),
            "人声" to intArrayOf(-200, 100, 500, 300, -100),
            "低音" to intArrayOf(600, 300, 0, -100, -200),
            "高音" to intArrayOf(-200, -100, 0, 300, 600)
        )
        legacy.forEachIndexed { i, (name, gains) ->
            assertEquals(name, EqPresets.nameOf(i))
            val want = EqBands.resample(gains)
            assertTrue(
                "预设 #$i（$name）曲线不符：${EqPresets.gainsOf(i).toList()} != ${want.toList()}",
                EqPresets.gainsOf(i).contentEquals(want)
            )
        }
    }

    @Test
    fun `每套预设都是十段，且增益在可调范围内`() {
        for (i in 0 until EqPresets.builtInCount) {
            val g = EqPresets.gainsOf(i)
            assertEquals("预设 #$i（${EqPresets.nameOf(i)}）段数", EqBands.COUNT, g.size)
            g.forEach { v ->
                assertTrue("超出范围: $v", v >= EqProcessor.LEVEL_MIN && v <= EqProcessor.LEVEL_MAX)
            }
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
     *  - 增益必须正好十段（少一段就会让 applyCurve 少写一段，那一段留着上一条的值）。
     */
    private fun isValidCurve(name: String, gains: List<Int>): Boolean =
        name.trim().isNotEmpty() && gains.size == EqBands.COUNT

    @Test
    fun `合法曲线通过格式校验`() {
        assertTrue(isValidCurve("通勤", EqBands.resample(intArrayOf(300, 200, 0, 150, 200)).toList()))
        assertTrue(isValidCurve("夜间", EqBands.resample(intArrayOf(-200, -100, 0, -100, -200)).toList()))
    }

    @Test
    fun `残缺曲线被判非法`() {
        assertFalse(isValidCurve("", List(EqBands.COUNT) { 0 }))
        assertFalse(isValidCurve("   ", List(EqBands.COUNT) { 0 }))
        assertFalse(isValidCurve("只有四段", listOf(1, 2, 3, 4)))
        assertFalse(isValidCurve("旧五段", listOf(1, 2, 3, 4, 5)))
        assertFalse(isValidCurve("多了一段", List(EqBands.COUNT + 1) { 1 }))
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

    // ── 音效状态判定 ──
    //
    // 旧用例里有 PoolStarved / Suspended 两个分支，那套状态空间随着
    // 系统音效池一起消失了 —— 处理器就在 PCM 管线上，既不排队也不交还。
    // 现在只剩四种：改着声音、被主动旁路（省电/DSD）、还没起播、什么都没开。

    @Test
    fun `省电播放时一律报已旁路，哪怕三项都开着`() {
        val s = FxStatus.resolve(
            offload = true, dsdPlaying = false, dsdBypassEq = true,
            wanted = 3, attached = true, sampleRateHz = 48_000
        )
        assertEquals(FxStatus.Bypassed(FxStatus.Bypassed.Reason.OFFLOAD), s)
    }

    @Test
    fun `DSD 旁路优先于已接管线`() {
        val s = FxStatus.resolve(
            offload = false, dsdPlaying = true, dsdBypassEq = true,
            wanted = 1, attached = true, sampleRateHz = 44_100
        )
        assertEquals(FxStatus.Bypassed(FxStatus.Bypassed.Reason.DSD), s)
    }

    @Test
    fun `用户允许 DSD 不旁路时照常生效`() {
        val s = FxStatus.resolve(
            offload = false, dsdPlaying = true, dsdBypassEq = false,
            wanted = 1, attached = true, sampleRateHz = 44_100
        )
        assertTrue(s is FxStatus.Active)
    }

    @Test
    fun `一项没开是 Idle，不是旁路`() {
        assertEquals(
            FxStatus.Idle,
            FxStatus.resolve(false, false, true, wanted = 0, attached = true)
        )
    }

    @Test
    fun `开着但还没起播报 Waiting`() {
        // 关键区分：没起播 ≠ 没生效。以前这里报「全关」，跟界面上的开关自相矛盾。
        assertEquals(
            FxStatus.Waiting,
            FxStatus.resolve(false, false, true, wanted = 1, attached = false)
        )
    }

    @Test
    fun `全关优先于未起播`() {
        assertEquals(
            FxStatus.Idle,
            FxStatus.resolve(false, false, true, wanted = 0, attached = false)
        )
    }

    @Test
    fun `生效时把采样率与余量带出来`() {
        val s = FxStatus.resolve(
            offload = false, dsdPlaying = false, dsdBypassEq = true,
            wanted = 2, attached = true, sampleRateHz = 48_000,
            headroomDb = 4.5f, loudnessAppliedDb = 3.5f, loudnessRequestedDb = 8f
        )
        assertEquals(FxStatus.Active(48_000, 4.5f, 3.5f, 8f), s)
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

    // ── 十段：频点与旧曲线迁移 ──

    @Test
    fun `十段频点是一个倍频程一段，从 31 到 16k`() {
        assertEquals(10, EqBands.COUNT)
        assertEquals(
            listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000),
            EqBands.FREQS.toList()
        )
        // 相邻段比值都接近 2 —— 有一个不接近，就不是「每段一个倍频程」
        for (i in 1 until EqBands.COUNT) {
            val ratio = EqBands.FREQS[i].toFloat() / EqBands.FREQS[i - 1]
            assertTrue("第 $i 段比值 $ratio 偏离 2", ratio > 1.8f && ratio < 2.2f)
        }
    }

    @Test
    fun `旧五段插值成十段：端点守住，中间单调`() {
        // 低音曲线：60Hz +6dB 一路降到 14kHz -2dB，中间不该出现反方向的鼓包
        val out = EqBands.resample(intArrayOf(600, 300, 0, -100, -200))
        assertEquals(EqBands.COUNT, out.size)
        assertEquals("最左段取源曲线首值", 600, out.first())
        assertEquals("最右段取源曲线末值", -200, out.last())
        for (i in 1 until out.size) {
            assertTrue("第 $i 段反向抬头: ${out.toList()}", out[i] <= out[i - 1])
        }
    }

    @Test
    fun `插值不外推：两端之外不放大`() {
        // 源曲线首段就是 +900，插值后最左段也只能是 +900，不能因为外推变成 +1200
        val out = EqBands.resample(intArrayOf(900, 0, 0, 0, 0))
        assertEquals(900, out.first())
        assertEquals(0, out.last())
        assertTrue(out.all { it in -900..900 })
    }

    @Test
    fun `已经是十段的曲线原样返回`() {
        val ten = intArrayOf(100, 90, 80, 70, 60, 50, 40, 30, 20, 10)
        assertTrue(EqBands.resample(ten).contentEquals(ten))
    }

    // ── Biquad：系数与幅频响应 ──

    @Test
    fun `0dB 的 peaking 就是直通`() {
        val c = Biquad.peaking(1_000f, 0f, 1.4f, 48_000f)
        // a1 = a2 = 0 且 b0 = 1 时传递函数恒为 1
        assertEquals(1f, c[0], 1e-5f)
        assertEquals(0f, c[1], 1e-5f)
        assertEquals(0f, c[2], 1e-5f)
    }

    @Test
    fun `peaking 在中心频率处的增益就是设定值`() {
        for (db in listOf(-12f, -6f, 3f, 9f)) {
            val c = Biquad.peaking(1_000f, db, 1.4f, 48_000f)
            val mag = Biquad.magnitudeAt(c, 1_000f, 48_000f)
            val got = 20f * kotlin.math.log10(mag)
            assertEquals("设定 $db dB", db, got, 0.2f)
        }
    }

    @Test
    fun `peaking 的增益在远离中心频率处回到 0`() {
        val c = Biquad.peaking(1_000f, 12f, 1.4f, 48_000f)
        val low = 20f * kotlin.math.log10(Biquad.magnitudeAt(c, 30f, 48_000f))
        val high = 20f * kotlin.math.log10(Biquad.magnitudeAt(c, 18_000f, 48_000f))
        assertEquals(0f, low, 1.5f)
        assertEquals(0f, high, 1.5f)
    }

    @Test
    fun `串联峰值高于单段最大值（相邻段会叠加）`() {
        // 这是「余量不能取十段最大值」的实证：31/62/125 全拉满时，
        // 峰值出现在段与段之间，比 +12dB 还高。
        val gains = IntArray(EqBands.COUNT) { 0 }
        gains[0] = 1_200; gains[1] = 1_200; gains[2] = 1_200
        val peak = Biquad.peakBoostDb(gains, 0, 48_000f, 110f, 12f, 1.4f)
        assertTrue("峰值 $peak 未超过单段 12dB", peak > 12.5f)
        assertTrue("峰值 $peak 大得不合理", peak < 24f)
    }

    @Test
    fun `全平曲线的串联峰值是 0`() {
        val peak = Biquad.peakBoostDb(IntArray(EqBands.COUNT), 0, 48_000f, 110f, 12f, 1.4f)
        assertEquals(0f, peak, 0.05f)
    }

    @Test
    fun `衰减曲线不产生正余量`() {
        // 全段衰减时串联峰值必然 < 0，但 headroom 的语义是「要让出多少」——
        // 让出量不可能为负（补回去等于偷着加响度），所以契约是夹到 0。
        val gains = IntArray(EqBands.COUNT) { -1_200 }
        val peak = Biquad.peakBoostDb(gains, 0, 48_000f, 110f, 12f, 1.4f)
        assertEquals("全衰减时应夹到 0", 0f, peak, 0.01f)
    }
}
