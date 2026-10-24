package com.kmusic.apkhd.audio

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DSF 容器头解析的端到端测试。
 *
 * 用真实的 `.dsf` 文件（由 `kmtest/mkdsf.py` 生成）验证：
 * - 采样率 / 声道 / 块大小 解析正确
 * - **数据偏移是 92**（28 + 52 + 12）而不是 80 —— 80 会把 `data` chunk 头当音频
 * - 数据长度与文件实际长度吻合
 */
@UnstableApi
class DsdContainerParserTest {

    /** 生成一个最小 DSF（结构与 mkdsf.py 一致），返回文件字节 */
    private fun buildDsf(
        dsdRate: Int = 2822400,
        channels: Int = 2,
        blockSize: Int = 4096,
        dataBytes: Int = 8192
    ): ByteArray {
        val data = ByteArray(dataBytes) { 0x69 }
        val blob = ByteArray(92 + dataBytes)
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)

        // DSD chunk (28)
        bb.put("DSD ".toByteArray(Charsets.ISO_8859_1))
        bb.putLong(28L)
        bb.putLong(0L)                       // totalSize，后面回填
        bb.putLong(0L)                       // metadataOffset

        // fmt chunk (12 + 40 = 52)
        bb.put("fmt ".toByteArray(Charsets.ISO_8859_1))
        bb.putLong(52L)
        bb.putInt(1)                         // formatVersion
        bb.putInt(0)                         // formatID = DSD raw
        bb.putInt(2)                         // channelType = stereo
        bb.putInt(channels)
        bb.putInt(dsdRate)
        bb.putInt(1)                         // bitsPerSample
        bb.putLong(dataBytes.toLong() * 8 / channels)  // sampleCount
        bb.putInt(blockSize)
        bb.putInt(0)                         // reserved

        // data chunk (12 + data)
        bb.put("data".toByteArray(Charsets.ISO_8859_1))
        bb.putLong((12 + dataBytes).toLong())
        data.copyInto(blob, 92)

        // 回填文件总长
        bb.putLong(12, blob.size.toLong())
        return blob
    }

    @Test
    fun `DSF 头解析出正确的采样率声道和块大小`() {
        val blob = buildDsf(dsdRate = 2822400, channels = 2, blockSize = 4096)
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!

        assertEquals(2822400, info.dsdRate)
        assertEquals(2, info.channels)
        assertEquals(4096, info.blockSize)
        assertTrue(info.isSupported)
        assertEquals(DsdInfo.Interleave.BLOCK, info.interleave)
        assertTrue("DSF 必须是 LSB-first", info.bitOrderLsbFirst)
    }

    @Test
    fun `DSF 数据偏移是 92 不是 80`() {
        val blob = buildDsf(dataBytes = 8192)
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!

        // 28 (DSD) + 52 (fmt) + 12 (data chunk 头) = 92
        assertEquals(92L, info.dataOffset)
        // 数据长度 = data chunk 总长 - 12
        assertEquals(8192L, info.dataSize)
    }

    @Test
    fun `DSF 时长按每声道采样数算`() {
        // 1 秒立体声 DSD64：2822400 bit/声道 = 352800 字节/声道
        val blob = buildDsf(dsdRate = 2822400, channels = 2, dataBytes = 352800 * 2)
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!

        assertEquals(1_000_000L, info.durationUs)
    }

    @Test
    fun `DSD64 和 DSD128 都被支持 DSD512 不支持`() {
        assertTrue(rateSupported(2822400))
        assertTrue(rateSupported(5644800))
        assertTrue(rateSupported(11289600))
        assertTrue(!rateSupported(22579200))   // DSD512 不做
    }

    private fun rateSupported(rate: Int): Boolean {
        val blob = buildDsf(dsdRate = rate, channels = 2, dataBytes = 4096)
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!
        return info.isSupported
    }

    @Test
    fun `嗅探能认出 DSF 和 DFF`() {
        val dsf = buildDsf()
        assertTrue(DsdContainerParser.sniff(dsf))

        // DFF: FRM8 头
        val dff = ByteArray(64)
        "FRM8".toByteArray(Charsets.ISO_8859_1).copyInto(dff, 0)
        assertTrue(DsdContainerParser.sniff(dff))

        // WAV 不该被当成 DSD
        val wav = ByteArray(64)
        "RIFF".toByteArray(Charsets.ISO_8859_1).copyInto(wav, 0)
        assertTrue(!DsdContainerParser.sniff(wav))
    }

    @Test
    fun `真实 DSF 文件能解析出正确倍率标签`() {
        val f = File(
            System.getProperty("user.home") + "/AppData/Local/hermes/cache/scratch/kmtest/dsd64_1k.dsf"
        )
        if (!f.exists()) return   // 测试文件不在就跳过，不让 CI 挂
        val blob = f.readBytes()
        val info = DsdContainerParser.parse(blob, blob.size, blob.size.toLong())!!
        assertEquals("DSD64", DsdInfo.rateLabel(info.dsdRate))
        assertEquals(2, info.channels)
        assertEquals(4096, info.blockSize)
        assertEquals(92L, info.dataOffset)
    }
}
