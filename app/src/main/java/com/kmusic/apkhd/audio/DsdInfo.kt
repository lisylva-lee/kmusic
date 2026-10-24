package com.kmusic.apkhd.audio

/**
 * DSD 文件信息。
 *
 * @property dsdRate      DSD 采样率（2822400 / 5644800 / 11289600）
 * @property channels     声道数
 * @property sampleCount  每声道采样数（0 表示未知，按文件长度算）
 * @property bitOrderLsbFirst true = LSB-first（DSF），false = MSB-first（DFF）
 * @property interleave   数据交织方式
 * @property compression  压缩格式（DFF 的 "DST " 等），null = 未压缩
 * @property durationUs   时长（微秒），0 = 未知
 */
@androidx.media3.common.util.UnstableApi
data class DsdInfo(
    val dsdRate: Int,
    val channels: Int,
    val sampleCount: Long,
    val bitOrderLsbFirst: Boolean,
    val interleave: Interleave,
    val compression: String? = null,
    val durationUs: Long = 0L,
    /** DSF 块大小（字节/声道）；DFF 为 0 */
    val blockSize: Int = 0,
    /** 原始 DSD 比特流在文件里的起始偏移（字节） */
    val dataOffset: Long = 0L,
    /** 原始 DSD 比特流长度（字节） */
    val dataSize: Long = 0L
) {
    enum class Interleave {
        /** DSF：块交织，每 blockSize 字节/声道，ch0 整块、ch1 整块… */
        BLOCK,

        /** DFF：字节交织，ch0 1 字节、ch1 1 字节交替 */
        BYTE
    }

    /** 是否是本 App 支持的 DSD 档位 */
    val isSupported: Boolean
        get() = compression == null &&
            dsdRate in SUPPORTED_RATES &&
            channels in 1..8

    /** 是否是 DST 压缩（DFF 专有，暂不支持） */
    val isDstCompressed: Boolean
        get() = compression == "DST "

    companion object {
        /** 支持的 DSD 采样率：DSD64 / DSD128 / DSD256 */
        val SUPPORTED_RATES = intArrayOf(2822400, 5644800, 11289600)

        /** DSD 倍率标签 */
        fun rateLabel(dsdRate: Int): String = when (dsdRate) {
            2822400 -> "DSD64"
            5644800 -> "DSD128"
            11289600 -> "DSD256"
            else -> "DSD"
        }
    }
}
