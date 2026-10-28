package com.kmusic.apkhd.playback

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 十段均衡器的频段定义与旧曲线迁移。
 *
 * 为什么是这十个点：31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k ——
 * 每段正好一个倍频程，是硬件图示均衡器（graphic EQ）的通用刻度，
 * 也是「31~16k」这个说法的来源。它和旧的 60 / 230 / 910 / 3.6k / 14k 不同：
 * 旧五段是当年**设备均衡器**给什么就用什么，现在自己算，没理由继续将就。
 *
 * ⚠️ 频段数一变，所有存下来的曲线都跟着变（内置 12 套 + 用户自建）。
 * 旧曲线是 5 个点，必须按**对数频率轴**插值到 10 个点，而不是按下标硬凑 ——
 * 按下标凑会把 60Hz 的 +9dB 搬到 31Hz，低音增强直接变味。
 */
object EqBands {

    /** 十段中心频率（Hz），从低到高，升序 */
    val FREQS: IntArray = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    /** 段数 —— 全仓只认这一个常量，别再写字面量 5 */
    const val COUNT: Int = 10

    /** 旧五段的中心频率（Hz）。只用于把旧曲线迁移过来 */
    val LEGACY_FREQS: IntArray = intArrayOf(60, 230, 910, 3_600, 14_000)

    /**
     * 五段 → 十段。
     *
     * 在 log10(f) 上做线性插值：频率轴本来就是对数的，线性插值在对数轴上
     * 才等于「相邻倍频程等比」。超出旧锚点范围的两端（31Hz、16kHz）
     * **取端点值而不外推** —— 外推会在 31Hz 上放大出 +12dB 之类的假低频。
     */
    fun resample(legacy: IntArray): IntArray {
        if (legacy.isEmpty()) return IntArray(COUNT)
        if (legacy.size == COUNT) return legacy.copyOf()
        if (legacy.size == 1) return IntArray(COUNT) { legacy[0] }

        val srcX = DoubleArray(legacy.size) { log10(LEGACY_FREQS.getOrElse(it) { LEGACY_FREQS.last() }.toDouble()) }
        return IntArray(COUNT) { i ->
            val x = log10(FREQS[i].toDouble())
            when {
                x <= srcX.first() -> legacy.first()
                x >= srcX.last() -> legacy.last()
                else -> {
                    var seg = 0
                    while (seg < legacy.size - 2 && x > srcX[seg + 1]) seg++
                    val span = (srcX[seg + 1] - srcX[seg]).coerceAtLeast(1e-9)
                    val t = (x - srcX[seg]) / span
                    val y = legacy[seg] + (legacy[seg + 1] - legacy[seg]) * t
                    y.roundToInt()
                }
            }
        }
    }

    /** 任意长度曲线规整成十段（用户预设读盘、恢复备份都走这里） */
    fun normalize(gains: IntArray): IntArray = when (gains.size) {
        COUNT -> gains.copyOf()
        0 -> IntArray(COUNT)
        else -> resample(gains)
    }

    /** 频率标签：31 / 125 / 1k / 16k */
    fun label(hz: Int): String = when {
        hz <= 0 -> "—"
        hz >= 1000 -> {
            val k = hz / 1000
            if (hz % 1000 == 0) "${k}k" else "%.1fk".format(hz / 1000f)
        }
        else -> "$hz"
    }

    /** 供曲线图用的等比刻度（0..1），与 EqCurveMath 的 20Hz..20kHz 轴一致 */
    fun fractionOf(hz: Int, minHz: Float = 20f, maxHz: Float = 20_000f): Float {
        val f = hz.toFloat().coerceIn(minHz, maxHz)
        return (lnRatio(f, minHz, maxHz)).coerceIn(0f, 1f)
    }

    private fun lnRatio(f: Float, lo: Float, hi: Float): Float =
        (kotlin.math.ln(f / lo) / kotlin.math.ln(hi / lo))

    /** 用于把 dB 换算成线性倍数（处理器内部用，单测也验这个） */
    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
}
