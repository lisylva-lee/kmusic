package com.kmusic.apkhd.audio

import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DSF / DFF 容器头解析。
 *
 * ## DSF（Sony DSD Stream File）
 *
 * ```
 * 偏移 0   : "DSD " + chunkSize(8) + totalSize(8) + offsetToMetadata(8)   = 28 字节
 * 偏移 28  : "fmt " + chunkSize(8) +                                    = 12 字节头
 *            formatVersion(4) formatID(4) channelType(4) channelNum(4)
 *            samplingFrequency(4) bitsPerSample(4) sampleCount(8)
 *            blockSizePerChannel(4) reserved(4)                          = 40 字节数据
 * 之后      : "data" + chunkSize(8) + 原始 DSD 数据
 * ```
 *
 * **注意**：`chunkSize` 字段是 **chunk 总长**（含 12 字节头），不是数据区长度。
 * 所以 `fmt ` 的 chunkSize = 52，下一个 chunk（`data`）在 28 + 52 = 80。
 * （用 ffmpeg 实测确认：写 52 能解析，写 40 会报 "Invalid data found"。）
 *
 * 数据是 **LSB-first** 打包，且**块交织**：每 `blockSizePerChannel` 字节
 * 为一个单位，ch0 的这一块在前，ch1 的这一块在后…
 *
 * ## DFF（DSDIFF，Philips）
 *
 * ```
 * "FRM8" + size(8) + formType(4) + ...
 *   └ "PROP" + size(8) + propType(4) + ...
 *       └ "FS  " + size(8) + sampleRate(4) + ...
 *       └ "CHNL" + size(8) + numChannels(2) + chID(2)×n + ...
 *       └ "CMPR" + size(8) + compressionType(4) + count(4) + name + ...   (可选)
 *   └ "DSD " + size(8) + 原始 DSD 数据
 * ```
 *
 * 数据是 **MSB-first** 打包，且**字节级交织**：ch0 1 字节、ch1 1 字节交替。
 *
 * 两种容器交织方式相反 —— 这是最容易写错的地方。
 */
@UnstableApi
object DsdContainerParser {

    /** 头部长度上限（够读 fmt + data 定位） */
    private const val MAX_HEADER = 8192

    /** DSD 块的 4 字节标识 */
    private fun tag(b: ByteArray, off: Int): String =
        String(b, off, 4, Charsets.ISO_8859_1)

    /**
     * 快速嗅探：只看头 4 字节。
     *
     * @return true 表示这是 DSF 或 DFF
     */
    fun sniff(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val magic = String(header, 0, 4, Charsets.ISO_8859_1)
        return magic == "DSD " || magic == "FRM8"
    }

    /**
     * 解析容器头。
     *
     * @param header  已读入的容器头字节
     * @param headerLen 有效长度
     * @param totalFileSize 文件总大小（用于算 DFF 时长）
     * @return 解析成功返回信息；数据不足或不支持返回 null
     */
    fun parse(header: ByteArray, headerLen: Int, totalFileSize: Long): DsdInfo? {
        if (headerLen < 4) return null
        return when (tag(header, 0)) {
            "DSD " -> parseDsf(header, headerLen)
            "FRM8" -> parseDff(header, headerLen, totalFileSize)
            else -> null
        }
    }

    // ── DSF ──

    private fun parseDsf(b: ByteArray, n: Int): DsdInfo? {
        // DSD(28) + fmt(52) = 80 字节
        if (n < 80) return null
        // ⚠️ 所有字段都用**绝对偏移**读，不能用 ByteBuffer 连续 readLong/readInt ——
        // chunk 标识是 4 字节（"fmt " / "data"），而 size 字段是 8 字节，
        // 连续读会错位 4 字节，把 chunkSize 读成天文数字。
        val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN)

        // "DSD " chunk: [0]"DSD " [4]size(8) [12]totalSize(8) [20]metadataOffset(8)
        val dsdChunkSize = bb.getLong(4)
        val totalSize = bb.getLong(12)
        val metadataOffset = bb.getLong(20)
        if (dsdChunkSize < 28L) return null

        // "fmt " chunk: [28]"fmt " [32]size(8) [40]formatVersion ... [76]reserved(4)
        if (tag(b, 28) != "fmt ") return null
        val fmtChunkSize = bb.getLong(32)          // = 52（chunk 总长）
        if (fmtChunkSize < 52L) return null

        val formatVersion = bb.getInt(40)
        val formatId = bb.getInt(44)
        val channelType = bb.getInt(48)
        val channelNum = bb.getInt(52)
        val samplingFreq = bb.getInt(56)
        val bitsPerSample = bb.getInt(60)
        val sampleCount = bb.getLong(64)
        val blockSizePerChannel = bb.getInt(72)

        if (formatVersion != 1) return null
        if (formatId != 0) return null   // 0 = DSD raw
        if (bitsPerSample != 1) return null
        if (channelNum <= 0 || channelNum > 8) return null
        if (samplingFreq <= 0) return null
        if (blockSizePerChannel <= 0) return null

        // 下一个 chunk 在 28 + fmtChunkSize
        val dataOffset = 28 + fmtChunkSize.toInt()
        if (dataOffset + 12 > n) {
            // data chunk 头还没读全 —— 至少要能确认 "data" 在哪
            if (dataOffset + 12 <= MAX_HEADER) return null
        }
        // 校验 data chunk 标识
        if (dataOffset + 12 <= n && tag(b, dataOffset) != "data") {
            // 有些文件在 fmt 之后还有别的 chunk，跳过它们
            var q = dataOffset
            while (q + 12 <= n) {
                val t = tag(b, q)
                if (t == "data") {
                    val dsize = ByteBuffer.wrap(b, q + 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    return buildDsfInfo(
                        samplingFreq, channelNum, sampleCount, totalSize, metadataOffset,
                        blockSizePerChannel, (q + 12).toLong(), (dsize - 12).coerceAtLeast(0)
                    )
                }
                val size = ByteBuffer.wrap(b, q + 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
                if (size < 12) return null
                q += size.toInt()
                if (q > n) break
            }
            return null
        }

        // 正常路径：data chunk 紧跟 fmt
        val dsdDataSize = ByteBuffer.wrap(b, dataOffset + 4, 8)
            .order(ByteOrder.LITTLE_ENDIAN).long
        return buildDsfInfo(
            samplingFreq, channelNum, sampleCount, totalSize, metadataOffset,
            blockSizePerChannel, (dataOffset + 12).toLong(), (dsdDataSize - 12).coerceAtLeast(0)
        )
    }

    private fun buildDsfInfo(
        samplingFreq: Int,
        channelNum: Int,
        sampleCount: Long,
        totalSize: Long,
        metadataOffset: Long,
        blockSize: Int,
        dataOffset: Long,
        dataSize: Long
    ): DsdInfo {
        // 时长 = 每声道采样数 / DSD 采样率
        val durationUs = if (sampleCount > 0 && samplingFreq > 0) {
            sampleCount * 1_000_000L / samplingFreq
        } else {
            0L
        }
        return DsdInfo(
            dsdRate = samplingFreq,
            channels = channelNum,
            sampleCount = sampleCount,
            bitOrderLsbFirst = true,
            interleave = DsdInfo.Interleave.BLOCK,
            compression = null,
            durationUs = durationUs,
            blockSize = blockSize,
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    // ── DFF ──

    private fun parseDff(b: ByteArray, n: Int, totalFileSize: Long): DsdInfo? {
        if (n < 16) return null
        val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.BIG_ENDIAN)

        // "FRM8": [0]"FRM8" [4]size(8) [12]formType(4)
        val frm8Size = bb.getLong(4)
        val formType = bb.getInt(12)

        var sampleRate = 0
        var channels = 0
        var compression: String? = null
        var dsdDataOffset = -1L
        var dsdDataSize = 0L

        // FRM8 头 = "FRM8"(4) + size(8) + formType(4) = 16 字节，
        // 第一个子 chunk 从 16 开始（从 12 开始会读到 formType 当 chunk 标识）
        var p = 16
        while (p + 12 <= n && (frm8Size <= 0L || p + 12 <= frm8Size)) {
            val t = tag(b, p)
            val size = ByteBuffer.wrap(b, p + 4, 8).order(ByteOrder.BIG_ENDIAN).long
            when (t) {
                "PROP" -> {
                    // PROP 内部再遍历
                    // PROP 的结构是: "PROP" size(8) propType(4) 然后才是子 chunk
                    // 所以子 chunk 从 p + 16 开始，不是 p + 12
                    var q = p + 16
                    val propEnd = p + 12 + size
                    while (q + 12 <= n && q + 12 <= propEnd) {
                        val ptag = tag(b, q)
                        val psize = ByteBuffer.wrap(b, q + 4, 8).order(ByteOrder.BIG_ENDIAN).long
                        when (ptag) {
                            "FS  " -> {
                                // FS  chunk: [q]"FS  " [q+4]size(8) [q+12]sampleRate(4)
                                if (q + 16 <= n) {
                                    sampleRate = ByteBuffer.wrap(b, q + 12, 4)
                                        .order(ByteOrder.BIG_ENDIAN).int
                                }
                            }
                            "CHNL" -> {
                                // CHNL chunk: [q]"CHNL" [q+4]size(8) [q+12]numChannels(2)
                                if (q + 14 <= n) {
                                    channels = ByteBuffer.wrap(b, q + 12, 2)
                                        .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                                }
                            }
                            "CMPR" -> {
                                compression = readCompressionName(b, n, q)
                            }
                        }
                        q += (12 + psize).toInt()
                    }
                }
                "DSD " -> {
                    // IFF 的 chunk size 字段 = payload 长度（不含 12 字节的 id+size 头）
                    dsdDataOffset = (p + 12).toLong()
                    dsdDataSize = size
                    break
                }
                "DST " -> {
                    // DST 压缩 —— 不支持
                    return DsdInfo(
                        dsdRate = sampleRate,
                        channels = channels,
                        sampleCount = 0L,
                        bitOrderLsbFirst = false,
                        interleave = DsdInfo.Interleave.BYTE,
                        compression = "DST ",
                        durationUs = 0L,
                        dataOffset = 0L,
                        dataSize = 0L
                    )
                }
            }
            p += (12 + size).toInt()
            if (size <= 0) break
        }

        if (sampleRate <= 0 || channels <= 0) return null
        if (compression != null && compression != "not compressed") return null

        // DFF 的时长：数据长度 / 声道数 / (采样率/8)
        var durationUs = 0L
        if (dsdDataOffset > 0 && sampleRate > 0 && channels > 0) {
            val totalBytes = if (dsdDataSize > 0) dsdDataSize else (totalFileSize - dsdDataOffset)
            val bytesPerChannel = totalBytes / channels
            val samples = bytesPerChannel * 8L
            durationUs = samples * 1_000_000L / sampleRate
        }

        return DsdInfo(
            dsdRate = sampleRate,
            channels = channels,
            sampleCount = 0L,
            bitOrderLsbFirst = false,
            interleave = DsdInfo.Interleave.BYTE,
            compression = compression,
            durationUs = durationUs,
            dataOffset = if (dsdDataOffset > 0) dsdDataOffset else 0L,
            dataSize = dsdDataSize
        )
    }

    /**
     * 读 CMPR 块里的压缩格式名。
     *
     * CMPR payload: compressionType(4) + count(4) + [DTD chunk]
     * - compressionType != 0 时直接是格式标识（DFF 里是 4 字符，如 "DST "）
     * - compressionType == 0 时后面跟一个 "DTD " chunk，
     *   其结构是 "DTD "(4) size(8) timestamp(4) compressionType(4) nameLen(4) name
     */
    private fun readCompressionName(b: ByteArray, n: Int, cmprOffset: Int): String? {
        if (cmprOffset + 16 > n) return null
        val type = ByteBuffer.wrap(b, cmprOffset + 12, 4).order(ByteOrder.BIG_ENDIAN).int
        if (type != 0) {
            // 直接是 4 字符的格式名
            if (cmprOffset + 20 > n) return "COMPR$type"
            val nm = String(b, cmprOffset + 16, 4, Charsets.ISO_8859_1).trim()
            return if (nm.isNotEmpty()) nm else "COMPR$type"
        }
        // DTD 块的位置：cmprOffset 是 CMPR chunk 起点(标识处)，
        // payload 从 cmprOffset+12 开始，payload 头是 type(4) + count(4) = 8 字节，
        // 所以 DTD chunk 在 cmprOffset + 12 + 8 = cmprOffset + 20
        val dtdOffset = cmprOffset + 20
        if (dtdOffset + 24 > n) return "DTD?"
        if (tag(b, dtdOffset) != "DTD ") return "DTD?"
        // DTD payload: timestamp(4) + compressionType(4) + nameLen(4) + name
        val nameLen = ByteBuffer.wrap(b, dtdOffset + 20, 4).order(ByteOrder.BIG_ENDIAN).int
        if (nameLen <= 0 || nameLen > 64) return "DTD?"
        val nameOffset = dtdOffset + 24
        if (nameOffset + nameLen > n) return "DTD?"
        val name = String(b, nameOffset, nameLen, Charsets.ISO_8859_1).trim()
        // "not compressed" 是合法的（表示未压缩）
        return if (name.equals("not compressed", ignoreCase = true)) null else name
    }
}
