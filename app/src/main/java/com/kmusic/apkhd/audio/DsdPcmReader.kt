package com.kmusic.apkhd.audio

import androidx.media3.common.util.UnstableApi

/**
 * DSD 数据 → 16bit PCM 的读取器。
 *
 * 职责：把容器里的 DSD 字节流**去交织**成每声道独立的连续比特流，
 * 再交给 [DsdDecimator.Decimator] 抽取成 PCM，最后交错输出。
 *
 * ## 两种交织
 *
 * - **DSF（块交织）**：`blockSizePerChannel` 字节为一个单位，
 *   数据排列是 `ch0[0..B) ch1[0..B) ch2[0..B) ...` 循环。
 *   所以必须**攒够 B×channels 字节**才能解出一轮。
 * - **DFF（字节交织）**：`ch0[0] ch1[0] ch2[0] ch0[1] ch1[1] ...`，
 *   每个字节就是一个声道的一次采样（8 个 bit）。
 *
 * ## 比特序
 *
 * - DSF：**LSB-first** —— 字节的第 0 位对应时间上最早的 bit。
 * - DFF：**MSB-first** —— 字节的第 7 位对应时间上最早的 bit。
 *
 * 两者相反，写反了会得到完全失真的音频。
 */
@UnstableApi
class DsdPcmReader(
    private val info: DsdInfo,
    /** 目标 PCM 采样率 */
    private val outRate: Int
) {
    /** 每声道的抽取器 */
    private val decimators: Array<DsdDecimator.Decimator> =
        Array(info.channels) { DsdDecimator.Decimator(info.dsdRate, outRate) }

    /** DSF 块大小（字节/声道） */
    private val blockSize: Int = if (info.interleave == DsdInfo.Interleave.BLOCK) {
        if (info.blockSize > 0) info.blockSize else DSF_BLOCK_SIZE
    } else {
        0
    }

    /** DSF 的累积缓冲：blockSize × channels */
    private val blockBuf: ByteArray = if (blockSize > 0) ByteArray(blockSize * info.channels) else ByteArray(0)

    /** blockBuf 已填字节数 */
    private var blockFill = 0

    /** 输出 PCM 缓冲（交错，16bit 小端） */
    private val pcmOut = ByteArray(512 * 1024)

    /**
     * 上一次没处理完的源数据。
     *
     * 输出缓冲快满时会提前 break，如果这时把剩余源数据丢掉就等于丢音频。
     * 所以剩下的存到这里，下次 read 优先处理。
     */
    private var pending = ByteArray(0)
    private var pendingLen = 0

    /**
     * 读一段 DSD 数据，转成 PCM。
     *
     * @param src   DSD 原始字节
     * @param len   有效长度
     * @param out   输出缓冲（16bit 小端交错 PCM）
     * @return 写入 out 的字节数
     */
    fun read(src: ByteArray, len: Int, out: ByteArray): Int {
        // 把上次的残留和本次数据接起来
        val data: ByteArray
        val dataLen: Int
        if (pendingLen > 0) {
            data = ByteArray(pendingLen + len)
            System.arraycopy(pending, 0, data, 0, pendingLen)
            System.arraycopy(src, 0, data, pendingLen, len)
            dataLen = pendingLen + len
            pendingLen = 0
        } else {
            data = src
            dataLen = len
        }

        val produced = if (info.interleave == DsdInfo.Interleave.BLOCK) {
            readBlockInterleaved(data, dataLen, out)
        } else {
            readByteInterleaved(data, dataLen, out)
        }
        return produced
    }

    /** DSF：块交织 —— ch0 整块、ch1 整块，循环 */
    private fun readBlockInterleaved(src: ByteArray, len: Int, out: ByteArray): Int {
        var si = 0
        var outPos = 0
        while (si < len) {
            // 输出缓冲快满了就停 —— 剩下的源数据存进 pending，下次继续
            if (outPos >= out.size - 8192) {
                stash(src, si, len - si)
                break
            }

            val need = blockBuf.size - blockFill
            val take = minOf(need, len - si)
            System.arraycopy(src, si, blockBuf, blockFill, take)
            blockFill += take
            si += take
            if (blockFill < blockBuf.size) break

            // 一轮完整：每个声道 blockSize 字节（ch0 在前，ch1 在后…）
            for (ch in 0 until info.channels) {
                val base = ch * blockSize
                for (i in 0 until blockSize) {
                    val b = blockBuf[base + i].toInt() and 0xFF
                    decimators[ch].feedByte(b)
                }
            }
            blockFill = 0

            outPos += drain(out, outPos)
        }
        return outPos
    }

    /** 把未处理的源数据存起来，下次 read 接着用 */
    private fun stash(src: ByteArray, from: Int, count: Int) {
        if (count <= 0) return
        if (pending.size < count) pending = ByteArray(count)
        System.arraycopy(src, from, pending, 0, count)
        pendingLen = count
    }

    /** DFF：字节交织 —— ch0 1 字节、ch1 1 字节交替 */
    private fun readByteInterleaved(src: ByteArray, len: Int, out: ByteArray): Int {
        var si = 0
        var outPos = 0
        var ch = 0
        while (si < len) {
            if (outPos >= out.size - 8192) {
                stash(src, si, len - si)
                break
            }
            val b = src[si].toInt() and 0xFF
            // DFF 是 MSB-first → 位反转成 LSB-first（抽取器的输入约定）
            decimators[ch].feedByte(reverseBits(b))
            ch++
            if (ch >= info.channels) ch = 0
            si++
        }
        outPos += drain(out, outPos)
        return outPos
    }

    /** 8 位反转 */
    private fun reverseBits(b: Int): Int {
        var v = b and 0xFF
        v = ((v and 0xF0) shr 4) or ((v and 0x0F) shl 4)
        v = ((v and 0xCC) shr 2) or ((v and 0x33) shl 2)
        v = ((v and 0xAA) shr 1) or ((v and 0x55) shl 1)
        return v and 0xFF
    }

    /** 把各声道抽取出的 PCM 交错成 16bit 输出 */
    private fun drain(out: ByteArray, outPos: Int): Int {
        val bufs = Array(info.channels) { decimators[it].read() }
        var n = Int.MAX_VALUE
        for (b in bufs) n = minOf(n, b.size)
        if (n == Int.MAX_VALUE || n == 0) return 0

        var p = outPos
        for (i in 0 until n) {
            for (ch in 0 until info.channels) {
                if (p + 2 > out.size) return p - outPos
                val s = toPcm16(bufs[ch][i])
                out[p] = (s and 0xFF).toByte()
                out[p + 1] = ((s shr 8) and 0xFF).toByte()
                p += 2
            }
        }
        return p - outPos
    }

    /** float (−1..+1) → 16bit，带限幅 */
    private fun toPcm16(v: Float): Int {
        val scaled = (v * 32767f).toInt()
        return when {
            scaled > 32767 -> 32767
            scaled < -32768 -> -32768
            else -> scaled
        }
    }

    /** 重置（seek 后调用） */
    fun reset() {
        blockFill = 0
        pendingLen = 0
        decimators.forEach { it.reset() }
    }

    companion object {
        /**
         * DSF 的块大小（字节/声道）。
         *
         * 规范默认 4096，但**容器头里没有这个字段**（DSF 的 fmt chunk 不带
         * blockSizePerChannel —— 那是 SACD 的布局）。DSF 规范（Sony）规定
         * 块大小固定为 4096 字节/声道。
         */
        const val DSF_BLOCK_SIZE = 4096
    }
}
