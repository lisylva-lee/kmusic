package com.kmusic.apkhd.audio

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.kmusic.apkhd.data.DiagLog
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DSF / DFF 的 media3 [Extractor]。
 *
 * 输出 `audio/raw` 的 16bit PCM 轨道，交给 media3 的
 * `DecoderAudioRenderer` + `DefaultAudioSink` 直通播放。
 *
 * ## 为什么必须自己写
 *
 * APK 里的 ffmpeg（`media3-ffmpeg-decoder`）只带**解码器**，没有 DSF/DFF 的
 * **解封装器** —— media3 的 ffmpeg 集成不做容器解析。所以 DSD 只能自己解。
 *
 * ## 输出格式
 *
 * ```
 * Format:
 *   mimeType      = MimeTypes.AUDIO_RAW
 *   pcmEncoding   = C.ENCODING_PCM_16BIT
 *   sampleRate    = 44100 或 88200（用户设置）
 *   channelCount  = 源声道数
 * ```
 */
@UnstableApi
class DsdExtractor(
    /** 输出 PCM 采样率：44100 或 88200 */
    private val outRate: Int
) : Extractor {

    private var info: DsdInfo? = null
    private var reader: DsdPcmReader? = null
    private var trackOutput: TrackOutput? = null

    /** 已产出的 PCM 帧数（用于时间戳） */
    private var framesProduced: Long = 0L

    /** 输出轨已发布 */
    private var trackPublished = false

    /** 读缓冲 */
    private val readBuf = ByteArray(64 * 1024)
    private val pcmBuf = ByteArray(512 * 1024)

    /** 当前文件剩余字节数（用于判断结束） */
    private var bytesRemaining: Long = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        // peek 前 4 字节即可判断魔数，不用关心总长度
        input.peekFully(readBuf, 0, 4)
        input.resetPeekPosition()
        return DsdContainerParser.sniff(readBuf)
    }

    private var extractorOutput: ExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, 0)
        // media3 要求 ExtractorOutput.endTracks() 在所有 track 注册完后调用，
        // 否则 ProgressiveMediaPeriod 认为 preparation 没完成，
        // 报 "Loading finished before preparation is complete"。
        output.endTracks()
        // seekMap 不能在这里发 —— 时长要等容器头解析完才知道。
        // 解析完再补发（media3 允许在 read() 期间发布 seekMap）。
    }

    override fun read(input: ExtractorInput, positionHolder: PositionHolder): Int {
        if (info == null) {
            if (!parseHeader(input)) {
                return Extractor.RESULT_END_OF_INPUT
            }
        }
        val i = info ?: return Extractor.RESULT_END_OF_INPUT

        if (!trackPublished) {
            trackOutput?.format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .setSampleRate(outRate)
                    .setChannelCount(i.channels)
                    .build()
            )
            trackPublished = true
        }

        // 用自己算的剩余字节数，而不是 input.getLength() ——
        // 后者在部分数据源（网络流、未知长度的容器）上返回 -1。
        val want = minOf(readBuf.size.toLong(), bytesRemaining)
        if (want <= 0) return Extractor.RESULT_END_OF_INPUT
        val n = input.read(readBuf, 0, want.toInt())
        if (n <= 0) return Extractor.RESULT_END_OF_INPUT
        bytesRemaining -= n

        // DSD → PCM
        val r = reader ?: return Extractor.RESULT_END_OF_INPUT
        val pcmLen = r.read(readBuf, n, pcmBuf)
        if (pcmLen <= 0) {
            // 本批没有产出 PCM（抽取链的延迟缓冲还没填满）
            positionHolder.position = input.position
            return Extractor.RESULT_CONTINUE
        }

        val frameCount = pcmLen / 2 / i.channels
        if (frameCount <= 0) {
            positionHolder.position = input.position
            return Extractor.RESULT_CONTINUE
        }

        // media3 1.7.1 的 sampleData(DataReader, size, allowShortSample)：
        // 时间戳由 TrackOutput 依据 Format.sampleRate 自行推导，
        // 所以这里只需要把 PCM 字节喂过去。
        trackOutput?.sampleData(
            ByteArrayDataReader(pcmBuf, pcmLen),
            pcmLen,
            false
        )
        framesProduced += frameCount
        positionHolder.position = input.position
        return Extractor.RESULT_CONTINUE
    }

    /**
     * 读文件头并解析容器信息。
     */
    private fun parseHeader(input: ExtractorInput): Boolean {
        val start = input.position
        val fileLen = try { input.getLength() } catch (_: Exception) { -1L }
        // SAF content:// 上 getLength() 可能返回 -1，此时按最大头部长度 peek
        val avail = if (fileLen > 0) minOf(fileLen - start, 8192L).toInt() else 8192
        if (avail < 4) return false
        input.peekFully(readBuf, 0, avail)
        input.resetPeekPosition()

        val magic = String(readBuf, 0, 4, Charsets.ISO_8859_1)
        if (magic != "DSD " && magic != "FRM8") return false

        val totalSize = try {
            val l = input.getLength()
            if (l > 0) l else 0L
        } catch (e: Exception) {
            0L
        }

        val parsed = DsdContainerParser.parse(readBuf, avail, totalSize)
        if (parsed == null) return false

        // 不支持的压缩格式要明确报错，不能静默播成噪音
        if (parsed.compression != null) {
            throw IOException("暂不支持 ${parsed.compression} 压缩的 DSD 文件")
        }
        if (!parsed.isSupported) {
            throw IOException(
                "暂不支持该 DSD 规格：${DsdInfo.rateLabel(parsed.dsdRate)} / ${parsed.channels} 声道" +
                    "（支持 DSD64/128/256）"
            )
        }

        info = parsed
        // 时长通过 seekMap 告诉 player（Format 里没有 duration 字段）——
        // 不给的话进度条和播放列表里的时长是空的。
        extractorOutput?.seekMap(SeekMap.Unseekable(parsed.durationUs))
        // 真实倍率（从容器头读出来的，比猜文件名可靠）
        DsdPlaybackReporter.onDsdParsed(DsdInfo.rateLabel(parsed.dsdRate))
        reader = DsdPcmReader(parsed, outRate)
        // 数据起点由 parser 算好（DSF = 92 = 28 + 52 + 12；DFF = DSD chunk 之后）
        val dataOffset = parsed.dataOffset
        if (dataOffset <= 0L) return false
        input.skip((dataOffset - input.position).toInt())
        bytesRemaining = parsed.dataSize.takeIf { it > 0 } ?: (totalSize - dataOffset)
        return true
    }

    override fun seek(positionUs: Long, seekTimeUs: Long) {
        // 不支持精确 seek —— 由 player 重新打开文件重放
        framesProduced = 0
        reader?.reset()
    }

    override fun release() {
        reader = null
        info = null
        trackOutput = null
        extractorOutput = null
    }

    /**
     * 把 DSD 解析结果回报给播放层。
     *
     * 单独放一个 object 是为了避免 audio 包依赖 playback 包（那边有 ExoPlayer，
     * 会把依赖关系搅乱）。播放层在初始化时把自己的实现注册进来。
     */
    object DsdPlaybackReporter {
        /** 回调：传入 DSD 倍率标签（DSD64 / DSD128 / DSD256） */
        @Volatile
        var onLabel: ((String) -> Unit)? = null

        fun onDsdParsed(label: String) {
            onLabel?.invoke(label)
        }
    }

    /**
     * 把一段 byte[] 当作 media3 的 [DataReader]。
     *
     * media3 1.7.1 的 `TrackOutput.sampleData` 要的是 DataReader 而不是 byte[]，
     * 所以这里做一层薄包装。
     */
    private class ByteArrayDataReader(
        private val buf: ByteArray,
        private val len: Int
    ) : DataReader {
        private var pos = 0

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            val avail = len - pos
            if (avail <= 0) return -1
            val n = minOf(count, avail)
            System.arraycopy(buf, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    companion object {
        /** 判断文件扩展名是否是 DSD */
        @JvmStatic
        fun isDsd(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return lower.endsWith(".dsf") || lower.endsWith(".dff")
        }
    }
}
