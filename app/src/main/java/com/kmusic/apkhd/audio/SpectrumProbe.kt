package com.kmusic.apkhd.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * 单首「真实音质」检测：从频谱截止频率推断源头码率。
 *
 * 原理：有损编码器在编码前会把高于码率对应带宽的频率直接砍掉（低通）。
 * 128k 的 MP3/AAC 基本在 16kHz 一刀切，192k 在 19kHz，320k 在 20kHz 上下；
 * 无损（CD 及以上）能一直延伸到 20k+。所以「能量掉到峰值 -80dB 的最高频率」
 * 就是这个文件的实际带宽。
 *
 * 注意：这不是「测谎仪」。老录音本身带宽就窄、黑胶转录有底噪，
 * 都可能误判成升频 —— 所以结论只给「疑似」，并且只在用户主动点「检测」时才算。
 */
object SpectrumProbe {

    data class Result(
        /** 能量截止频率（Hz） */
        val cutoffHz: Int,
        /** 推断结论 */
        val verdict: Verdict,
        /** 检测时的采样率，用于说明 */
        val sampleRate: Int
    )

    enum class Verdict(val label: String) {
        LOSSLESS("无损 / 高码率"),
        HIGH("约 256–320k"),
        MID("约 192k"),
        LOW("疑似 128k 升频"),
        UNKNOWN("无法判定")
    }

    private const val FFT_SIZE = 4096
    /** 取曲子中段，避开前奏淡入和尾部静音 */
    private const val PROBE_SECONDS = 8L
    /** 低于峰值的能量门槛（dB） */
    private const val FLOOR_DB = -80.0

    /**
     * @param durationMs 曲长，用于挑中段
     */
    suspend fun probe(
        context: android.content.Context,
        uri: android.net.Uri,
        durationMs: Long
    ): Result? {
        val startUs = if (durationMs > PROBE_SECONDS * 2000) {
            (durationMs / 2 - PROBE_SECONDS * 500) * 1000
        } else 0L

        var window = FloatArray(0)
        var windowFill = 0
        var sampleRate = 0
        var channels = 1
        var spectrum: DoubleArray? = null

        PcmDecoder.decode(
            context, uri, startUs, PROBE_SECONDS * 1_000_000L,
            onInfo = { info ->
                sampleRate = info.sampleRate
                channels = info.channels
            },
            onPcm = { pcm, count, ch ->
                if (spectrum != null) return@decode
                channels = ch
                if (window.size != FFT_SIZE) window = FloatArray(FFT_SIZE)
                var i = 0
                while (i + ch <= count && windowFill < FFT_SIZE) {
                    // 下混成单声道：左右平均，省一半 FFT
                    var v = 0f
                    for (c in 0 until ch) v += pcm[i + c]
                    window[windowFill++] = v / ch
                    i += ch
                }
                if (windowFill >= FFT_SIZE) {
                    spectrum = averageSpectrum(window)
                }
            }
        )

        val spec = spectrum ?: return null
        if (sampleRate <= 0) return null
        return fromSpectrum(spec, sampleRate)
    }

    /** 从单声道 PCM 直接算（测试用，也方便以后接在线流） */
    fun analyzePcm(samples: FloatArray, sampleRate: Int): Result? {
        if (samples.size < FFT_SIZE || sampleRate <= 0) return null
        val window = samples.copyOf(FFT_SIZE)
        return fromSpectrum(averageSpectrum(window), sampleRate)
    }

    private fun fromSpectrum(spec: DoubleArray, sampleRate: Int): Result? {
        val cutoff = cutoffHz(spec, sampleRate)
        if (cutoff <= 0) return null
        val verdict = when {
            cutoff >= 20_000 -> Verdict.LOSSLESS
            cutoff >= 18_500 -> Verdict.HIGH
            cutoff >= 17_000 -> Verdict.MID
            cutoff >= 10_000 -> Verdict.LOW
            else -> Verdict.UNKNOWN
        }
        return Result(cutoff, verdict, sampleRate)
    }

    /** Hann 窗 + 基 2 FFT，返回单边幅度谱（dB） */
    private fun averageSpectrum(samples: FloatArray): DoubleArray {
        val n = samples.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            re[i] = samples[i] * w
        }
        fft(re, im)
        val half = n / 2
        val mag = DoubleArray(half)
        for (i in 1 until half) {
            val m = hypot(re[i], im[i]) / n
            mag[i] = 20.0 * ln(m.coerceAtLeast(1e-9)) / ln(10.0)
        }
        // 轻度平滑：单帧频谱毛刺太多，会把截止点判早
        val smooth = DoubleArray(half)
        for (i in 1 until half) {
            var s = 0.0
            var c = 0
            for (k in (i - 2)..(i + 2)) {
                if (k in 1 until half) { s += mag[k]; c++ }
            }
            smooth[i] = if (c > 0) s / c else mag[i]
        }
        return smooth
    }

    private fun cutoffHz(spec: DoubleArray, sampleRate: Int): Int {
        var peak = -200.0
        for (i in 1 until spec.size) if (spec[i] > peak) peak = spec[i]
        if (peak < -120.0) return 0
        val floor = peak + FLOOR_DB
        // 从高频往低找第一个「超过门限」的 bin
        for (i in spec.size - 1 downTo 1) {
            if (spec[i] > floor) {
                val binHz = sampleRate.toDouble() / (spec.size * 2)
                return (i * binHz).toInt()
            }
        }
        return 0
    }

    /** 原地基 2 FFT（长度必须是 2 的幂） */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 仅用于自检：正弦波应得到接近其频率的截止 */
    fun selfTest(freqHz: Int, sampleRate: Int = 44100): Int {
        val n = FFT_SIZE
        val s = FloatArray(n) { sin(2.0 * PI * freqHz * it / sampleRate).toFloat() }
        return cutoffHz(averageSpectrum(s), sampleRate)
    }
}
