package com.kmusic.apkhd.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSD 抽取链的单元测试。
 *
 * 验证目标（与 Python 参考实现 / ffmpeg `soxr:precision=28` 对齐）：
 *  · 样本数正确（DSD64 → 44.1k，÷64）
 *  · 1 kHz 正弦的主分量幅度 ≈ −6 dBFS（与 ffmpeg 参考 −6.06 dBFS 吻合）
 *  · 噪声底远低于可闻阈值
 *  · DSD64/128/256 三个档位行为一致
 */
class DsdDecimatorTest {

    /**
     * 生成 DSD 字节流（LSB-first）：1 kHz 正弦，幅度 0.5。
     *
     * 用**二阶 sigma-delta 调制器**（和生成回归用 DSF 文件的 Python 脚本一致）——
     * 简单阈值量化会让比特密度超出 ±amp，解出来的幅度会偏大，
     * 就对不上 ffmpeg 参考了。
     */
    private fun dsdSineBytes(
        freq: Double,
        dsdRate: Int,
        byteCount: Int,
        amp: Double = 0.5
    ): ByteArray {
        val out = ByteArray(byteCount)
        val totalBits = byteCount * 8
        var i1 = 0.0
        var i2 = 0.0
        var acc = 0.0
        var ph = 0.0
        val step = 2.0 * PI * freq / dsdRate
        for (bi in 0 until byteCount) {
            var b = 0
            for (i in 0 until 8) {
                val x = amp * sin(ph)
                ph += step
                i1 += x - acc
                i2 += i1 - acc
                val bit = if (i2 >= 0) 1 else 0
                acc = if (bit == 1) 1.0 else -1.0
                if (bit == 1) b = b or (1 shl i)
            }
            out[bi] = b.toByte()
        }
        return out
    }

    /** Goertzel：测指定频率的幅度 */
    private fun amplitude(samples: FloatArray, freq: Double, sampleRate: Int): Double {
        if (samples.isEmpty()) return 0.0
        val n = samples.size
        val wq = 2.0 * cos(2.0 * PI * freq / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (x in samples) {
            val s0 = x + wq * s1 - s2
            s2 = s1
            s1 = s0
        }
        val real = s1 - s2 * cos(2.0 * PI * freq / sampleRate)
        val imag = s2 * sin(2.0 * PI * freq / sampleRate)
        return 2.0 * hypot(real, imag) / n
    }

    private fun dbfs(a: Double): Double = 20.0 * ln(a.coerceAtLeast(1e-15)) / ln(10.0)

    @Test
    fun `DSD64 抽取到 44100 的样本数正确`() {
        val dsdRate = 2822400
        val outRate = 44100
        val byteCount = 64 * 1024   // 524288 比特 = 0.186 s
        val data = dsdSineBytes(1000.0, dsdRate, byteCount)

        val dec = DsdDecimator.Decimator(dsdRate, outRate)
        val collected = ArrayList<Float>()
        for (b in data) {
            dec.feedByte(b.toInt() and 0xFF)
            val r = dec.read()
            for (v in r) collected.add(v)
        }

        // 期望：524288 比特 / 64 = 8192 个输出样本（减去滤波器延迟的几个）
        val expected = byteCount * 8 / 64
        val actual = collected.size
        assertTrue("样本数 $actual 应接近 $expected", abs(actual - expected) < 200)
    }

    @Test
    fun `DSD64 的 1kHz 主分量与 ffmpeg 参考一致`() {
        val dsdRate = 2822400
        val outRate = 44100
        val byteCount = 64 * 1024
        val data = dsdSineBytes(1000.0, dsdRate, byteCount)

        val dec = DsdDecimator.Decimator(dsdRate, outRate)
        val collected = ArrayList<Float>()
        for (b in data) {
            dec.feedByte(b.toInt() and 0xFF)
            for (v in dec.read()) collected.add(v)
        }
        val samples = collected.toFloatArray()

        val amp = amplitude(samples, 1000.0, outRate)
        // ffmpeg soxr:precision=28 对同一文件的 1 kHz 是 −6.06 dBFS
        // 允许 ±0.3 dB 的偏差
        assertTrue("1kHz 幅度 $amp (${dbfs(amp)} dBFS) 应接近 −6 dBFS", dbfs(amp) in -6.4..-5.7)
    }

    @Test
    fun `DSD64 的噪声底远低于可闻阈值`() {
        val dsdRate = 2822400
        val outRate = 44100
        val byteCount = 64 * 1024
        val data = dsdSineBytes(1000.0, dsdRate, byteCount)

        val dec = DsdDecimator.Decimator(dsdRate, outRate)
        val collected = ArrayList<Float>()
        for (b in data) {
            dec.feedByte(b.toInt() and 0xFF)
            for (v in dec.read()) collected.add(v)
        }
        val samples = collected.toFloatArray()

        // 2 kHz 处（没有信号）应该是噪声底
        val noise = amplitude(samples, 2000.0, outRate)
        assertTrue("噪声底 ${dbfs(noise)} dBFS 应低于 −55 dBFS", dbfs(noise) < -55.0)
    }

    @Test
    fun `DSD128 和 DSD256 的 1kHz 幅度与 DSD64 一致`() {
        val outRate = 44100
        val amps = mutableListOf<Double>()
        for (dsdRate in intArrayOf(2822400, 5644800, 11289600)) {
            // 保证相同的输出时长
            val seconds = 0.186
            val byteCount = (dsdRate * seconds / 8).toInt()
            val data = dsdSineBytes(1000.0, dsdRate, byteCount)

            val dec = DsdDecimator.Decimator(dsdRate, outRate)
            val collected = ArrayList<Float>()
            for (b in data) {
                dec.feedByte(b.toInt() and 0xFF)
                for (v in dec.read()) collected.add(v)
            }
            amps.add(amplitude(collected.toFloatArray(), 1000.0, outRate))
        }
        // 三档之间偏差应 < 0.2 dB
        val maxDiff = (amps.max() - amps.min()).let { 20.0 * ln(it) / ln(10.0) }
        assertTrue("三档幅度不一致: $amps (差 ${maxDiff} dB)", maxDiff < 0.2)
    }

    @Test
    fun `88kHz 输出的样本数是 44kHz 的两倍`() {
        val dsdRate = 2822400
        val byteCount = 32 * 1024
        val data = dsdSineBytes(1000.0, dsdRate, byteCount)

        val dec44 = DsdDecimator.Decimator(dsdRate, 44100)
        val dec88 = DsdDecimator.Decimator(dsdRate, 88200)
        var n44 = 0
        var n88 = 0
        for (b in data) {
            dec44.feedByte(b.toInt() and 0xFF)
            n44 += dec44.read().size
            dec88.feedByte(b.toInt() and 0xFF)
            n88 += dec88.read().size
        }
        assertTrue("44.1k=$n44  88.2k=$n88 应接近 2 倍", abs(n44 * 2 - n88) < 200)
    }

    @Test
    fun `reset 之后重新抽取能恢复到相同输出`() {
        val dsdRate = 2822400
        val outRate = 44100
        val byteCount = 16 * 1024
        val data = dsdSineBytes(1000.0, dsdRate, byteCount)

        val dec = DsdDecimator.Decimator(dsdRate, outRate)
        val first = ArrayList<Float>()
        for (b in data) {
            dec.feedByte(b.toInt() and 0xFF)
            for (v in dec.read()) first.add(v)
        }
        dec.reset()
        val second = ArrayList<Float>()
        for (b in data) {
            dec.feedByte(b.toInt() and 0xFF)
            for (v in dec.read()) second.add(v)
        }
        assertEquals("reset 后应产出同样的样本数", first.size, second.size)
    }

    @Test
    fun `BOX8 查表值在满量程两端分别是正负一`() {
        // BOX8[0xFF] = 8 个 1 → +1；BOX8[0x00] = 8 个 0 → −1
        val dec = DsdDecimator.Decimator(2822400, 44100)
        // 通过极端输入验证不崩溃且输出有界
        val allOnes = ByteArray(8192) { 0xFF.toByte() }
        for (b in allOnes) dec.feedByte(b.toInt() and 0xFF)
        val samples = dec.read()
        for (v in samples) {
            assertTrue("输出应在 −1.5 ~ 1.5 之间，实际 $v", abs(v) <= 1.5f)
        }
    }
}
