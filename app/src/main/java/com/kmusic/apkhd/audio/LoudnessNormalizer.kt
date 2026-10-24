package com.kmusic.apkhd.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kmusic.apkhd.data.LoudnessEntry
import com.kmusic.apkhd.data.LoudnessStore
import com.kmusic.apkhd.model.Track
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * 响度归一：算出「这首歌该用多大音量放」。
 *
 * 数据来源按可靠度排序：
 *   1. 文件自带 ReplayGain 标签 —— 制作方给的，最准，零成本
 *   2. 之前算过并落盘的结果 —— [LoudnessStore]
 *   3. 现算 EBU R128（解码整首，2~4 秒）—— 算完也落盘
 *
 * 增益策略：**有 headroom 才放大，没有就只衰减**。
 * 不知道峰值时（只有 LUFS 没有 peak）一律只衰减 —— 宁可小声，不要削波。
 * 车机功放本来就容易破音，这个保守取舍是有意的。
 */
object LoudnessNormalizer {

    /** 目标响度 -14 LUFS：与主流流媒体一致，车机环境也不至于太轻 */
    const val TARGET_LUFS = -14.0

    /** 最大衰减，防止把一首极响的歌压成蚊子叫 */
    private const val MIN_GAIN_DB = -24f
    /** 最大提升，即使 headroom 充足也不超过这个数 */
    private const val MAX_BOOST_DB = 6f

    data class Applied(
        val gainDb: Float,
        /** 用于界面显示；来自标签时为 null */
        val lufs: Double?,
        /** 数据来源：tag / cache / r128 / none */
        val source: String
    )

    /**
     * @param mode off / track / album
     */
    suspend fun resolve(
        context: Context,
        track: Track,
        uri: Uri,
        mode: String,
        store: LoudnessStore
    ): Applied {
        if (mode == "off") return Applied(0f, null, "off")
        if (track.source != "local") {
            // 在线流拿不到文件，也没有标签：不做归一（不假装有效果）
            return Applied(0f, null, "none")
        }

        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)

        // 1) 标签
        val tag = TagReader.readReplayGain(context, uri)
        val tagGain = if (mode == "album") {
            tag?.albumGainDb ?: tag?.trackGainDb
        } else {
            tag?.trackGainDb ?: tag?.albumGainDb
        }
        if (tagGain != null) {
            val peak = tag?.trackPeak
            val gain = clampGain(tagGain, peak)
            return Applied(gain, null, "tag")
        }

        // 2) 缓存
        val cached = store.get(track.id)
        if (cached != null && (cached.size == 0L || size == 0L || cached.size == size)) {
            val lufs = cached.lufs
            val gain = clampGain((TARGET_LUFS - lufs).toFloat(), cached.peak.takeIf { it > 0f })
            return Applied(gain, lufs, "cache")
        }

        // 3) 现算
        val result = R128Analyzer.analyze(context, uri) ?: return Applied(0f, null, "none")
        store.put(
            LoudnessEntry(
                id = track.id,
                lufs = result.lufs,
                peak = result.peak,
                size = size
            )
        )
        val gain = clampGain((TARGET_LUFS - result.lufs).toFloat(), result.peak.takeIf { it > 0f })
        return Applied(gain, result.lufs, "r128")
    }

    /**
     * 把目标增益夹到安全范围。
     *
     * @param peak 线性峰值（0..1）。null 表示不知道 → 只允许衰减
     */
    fun clampGain(rawGainDb: Float, peak: Float?): Float {
        val ceiling = if (peak != null && peak > 0f) {
            // 留 0.2dB 余量，避免浮点误差把样本顶到 1.0
            (20.0 * log10((0.98f / peak).toDouble())).toFloat()
        } else {
            0f
        }
        val upper = min(MAX_BOOST_DB, ceiling)
        return rawGainDb.coerceIn(MIN_GAIN_DB, upper)
    }

    /** 线性增益倍数，直接喂 player.volume */
    fun toLinear(gainDb: Float): Float = 10.0.pow(gainDb / 20.0).toFloat().coerceIn(0.02f, 2f)
}
