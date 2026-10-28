package com.kmusic.apkhd.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 双二阶滤波器（biquad）系数与幅频响应 —— 纯 Kotlin，不依赖 Android。
 *
 * 单独抽出来有两个原因：
 *  1. [EqProcessor] 在音频线程每帧跑它，系数必须只算一次；
 *  2. 界面要回答「这条曲线到底把峰值抬了多少 dB」（响度要扣掉这部分余量），
 *     答案只能靠**解析幅频响应**算，不能靠猜最大值 —— 十段串起来时，
 *     相邻两段的响应会在中间叠加，单看某一段的增益会低估。
 * 放在一个对象里，两边用的是同一份系数公式，不会各写一套然后慢慢漂开。
 *
 * 公式来自 RBJ Audio EQ Cookbook。系数归一化到 a0 = 1，
 * 返回值顺序固定为 `[b0, b1, b2, a1, a2]`，与 [EqProcessor] 的差分方程对应：
 * ```
 * y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
 * ```
 */
object Biquad {

    /** 恒等（全通、增益 1）—— 增益为 0 的段直接用它，省掉三角函数 */
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f)

    /**
     * 单步递推（转置直接 II 型），**系数下标与差分方程的对应关系只在这里写一次**。
     *
     * ```
     * y  = b0*x + z1
     * z1 = b1*x - a1*y + z2
     * z2 = b2*x - a2*y
     * ```
     *
     * 抽成函数是因为这里犯过一次极其隐蔽的错：把 `z1` 写成 `-b2*y`、`z2` 写成 `+a1*x`，
     * 也就是 b2 和 a1 互换。这个错误的可怕之处在于它**不会报错、不会发散**——
     * 1kHz 的 peaking 在 1kHz 处增益变成 0dB，却在 10kHz 涨到 +8dB、
     * 16kHz 涨到 +320dB，最后全靠限幅器压回满幅，听感是「一片杂音」。
     * 单测里必须拿**频率响应**验，不能只验系数是否算对。
     *
     * @param state 长度 2 的状态，原地更新
     */
    fun step(c: FloatArray, state: FloatArray, x: Float): Float {
        val y = c[0] * x + state[0]
        state[0] = c[1] * x - c[3] * y + state[1]
        state[1] = c[2] * x - c[4] * y
        return y
    }

    /**
     * 跑一段正弦、丢掉前 [settle] 个样本，返回稳态增益（dB）。
     *
     * 用 **RMS** 而不是峰值：8kHz/48kHz 每周期只有 6 个样本，离散取样抓不到真正
     * 的波峰，测出来会莫名低 1dB —— 那是测量误差，会被误当成滤波写错。
     * RMS 对相位不敏感，且正弦的 A/√2 正好约掉。
     *
     * 用它做「系数 → 实际听感」的端到端验收：递推写错时 [magnitudeAt]（解析式）
     * 依然是对的，只有真跑一遍才会暴露。这个函数就是为那次 b2/a1 互换的 bug 留的探针。
     */
    fun measureAmplitudeDb(
        c: FloatArray,
        freq: Float,
        fs: Float = 48_000f,
        amplitude: Float = 0.5f,
        samples: Int = 48_000,
        settle: Int = 12_000
    ): Float {
        val state = FloatArray(2)
        val w = 2.0 * PI * (freq / fs)
        var sum = 0.0
        var count = 0
        for (n in 0 until samples) {
            val x = (amplitude * sin(w * n)).toFloat()
            val y = step(c, state, x)
            if (!y.isFinite()) return Float.POSITIVE_INFINITY
            if (n >= settle) {
                sum += y.toDouble() * y
                count++
            }
        }
        if (count == 0) return 0f
        val rms = kotlin.math.sqrt(sum / count).toFloat()
        val inRms = amplitude / kotlin.math.sqrt(2f)
        return 20f * log10((rms / inRms).coerceAtLeast(1e-9f))
    }

    /**
     * 峰值滤波器（peaking EQ）。
     *
     * @param f0 中心频率 Hz
     * @param gainDb 该点的增益（dB），正为抬、负为削
     * @param q 品质因数，越大越窄。1 个倍频程带宽取 √2 ≈ 1.414
     * @param fs 采样率 Hz
     */
    fun peaking(f0: Float, gainDb: Float, q: Float, fs: Float): FloatArray {
        if (gainDb == 0f) return IDENTITY
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * (f0 / fs).coerceIn(1e-4f, 0.49f)
        val cw = cos(w0)
        val alpha = sin(w0) / (2f * q)
        val a0 = 1f + alpha / a
        return floatArrayOf(
            (1f + alpha * a) / a0,
            (-2f * cw) / a0,
            (1f - alpha * a) / a0,
            (-2f * cw) / a0,
            (1f - alpha / a) / a0
        )
    }

    /**
     * 低架滤波器（low shelf）：[f0] 以下整体抬/削，以上逐渐回到 0 dB。
     * 低音增强用它，而不是拿 62Hz 那一段当低音用 —— 那一段带宽只有一个倍频程，
     * 抬 62Hz 对 40Hz 的鼓皮几乎没作用。
     */
    fun lowShelf(f0: Float, gainDb: Float, q: Float, fs: Float): FloatArray {
        if (gainDb == 0f) return IDENTITY
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * (f0 / fs).coerceIn(1e-4f, 0.49f)
        val cw = cos(w0)
        val alpha = sin(w0) / (2f * q)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha
        val a0 = (a + 1f) + (a - 1f) * cw + twoSqrtAAlpha
        return floatArrayOf(
            (a * ((a + 1f) - (a - 1f) * cw + twoSqrtAAlpha)) / a0,
            (2f * a * ((a - 1f) - (a + 1f) * cw)) / a0,
            (a * ((a + 1f) - (a - 1f) * cw - twoSqrtAAlpha)) / a0,
            (-2f * ((a - 1f) + (a + 1f) * cw)) / a0,
            ((a + 1f) + (a - 1f) * cw - twoSqrtAAlpha) / a0
        )
    }

    /**
     * 在频率 [f] 处的线性幅度 |H(e^{jw})|。
     *
     * 直接代 z = e^{jw} 求分子分母的模，不做 FFT —— 只算单点，代价是几个三角函数。
     */
    fun magnitudeAt(c: FloatArray, f: Float, fs: Float): Float {
        val w = 2.0 * PI * (f / fs)
        val cw = cos(w).toFloat()
        val sw = sin(w).toFloat()
        val c2w = cos(2.0 * w).toFloat()
        val s2w = sin(2.0 * w).toFloat()
        val numRe = c[0] + c[1] * cw + c[2] * c2w
        val numIm = -(c[1] * sw + c[2] * s2w)
        val denRe = 1f + c[3] * cw + c[4] * c2w
        val denIm = -(c[3] * sw + c[4] * s2w)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        return if (den < 1e-12f) 1f else num / den
    }

    /**
     * 整条曲线（十段 + 低音搁架）在 20Hz–20kHz 上的**最大提升**（dB，只算正的）。
     *
     * 响度增强的前级增益要扣掉这个值（v2.3.4 A2 的规则），否则「拉满低频 + 叠响度」
     * 会一起撞上限幅器，听感是闷和喘，而不是更响。
     *
     * 为什么不能直接取十段里的最大值：相邻段在中间频率上会叠加。
     * 实测把 31/62/125 都拉 +9dB 时，峰值在 62Hz 附近是 +13.6dB，
     * 而不是 +9dB —— 只扣 9dB 就漏了 4.6dB 进限幅器。
     */
    fun peakBoostDb(
        gainsMb: IntArray,
        bassStrength: Int,
        sampleRate: Float = 48_000f,
        bassShelfHz: Float = 110f,
        bassMaxDb: Float = 9f,
        q: Float = 1.4142135f
    ): Float {
        val nyquist = sampleRate * 0.45f
        // 探针：等比铺 64 点，再把每个段的中心频率塞进去。
        // 只靠等比网格会错过段中心（31Hz 这类低频一个倍频程才有几个点），
        // 峰值就会被低估 —— 而低估峰值等于没让出余量。
        val probes = ArrayList<Float>(80)
        val lo = 20f
        val ratio = ln(nyquist / lo)
        for (i in 0 until 64) {
            probes.add(lo * kotlin.math.exp(ratio * i / 63f))
        }
        for (hz in EqBands.FREQS) {
            if (hz < nyquist) probes.add(hz.toFloat())
        }

        val coeffs = Array(EqBands.COUNT) { b ->
            Biquad.peaking(EqBands.FREQS[b].toFloat(), gainsMb.getOrElse(b) { 0 } / 100f, q, sampleRate)
        }
        val bassDb = if (bassStrength > 0) bassStrength.coerceAtMost(1_000) / 1_000f * bassMaxDb else 0f
        val shelf = Biquad.lowShelf(bassShelfHz, bassDb, 0.8f, sampleRate)

        var peak = 0f
        for (f in probes) {
            var db = 0f
            for (b in 0 until EqBands.COUNT) {
                if (coeffs[b] === IDENTITY) continue
                db += 20f * log10(magnitudeAt(coeffs[b], f, sampleRate).coerceAtLeast(1e-6f))
            }
            if (bassDb != 0f) {
                db += 20f * log10(magnitudeAt(shelf, f, sampleRate).coerceAtLeast(1e-6f))
            }
            if (db > peak) peak = db
        }
        return peak.coerceAtLeast(0f)
    }
}
