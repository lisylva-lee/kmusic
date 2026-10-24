package com.kmusic.apkhd.crypto

/**
 * QMC v2 RC4 流式解密器
 *
 * 分段策略：
 * - 首段 0x80 字节：key 字符索引异或
 * - 后续每 0x1400 字节：RC4 keystream（跳过 skip 字节）
 *
 * 移植来源：umc_qmc/src/v2_rc4
 */
class QmcV2Rc4(key: ByteArray) {

    companion object {
        private const val FIRST_SEGMENT_SIZE = 0x0080
        private const val OTHER_SEGMENT_SIZE = 0x1400
        private const val RC4_STREAM_CACHE_SIZE = OTHER_SEGMENT_SIZE + 512

        /** 与 Rust `(0..n).map(|i| i as u8)` 一致：长度 >256 时索引截断 */
        fun initState(n: Int): ByteArray = ByteArray(n) { (it and 0xFF).toByte() }

        fun hash(key: ByteArray): Double {
            var h = 1L
            for (v in key) {
                if (v == 0.toByte()) continue
                // 与 Rust u32 乘法一致：必须按 32 位截断，否则长 key 会溢出到 64 位导致索引全错
                val next = (h * (v.toLong() and 0xFF)) and 0xFFFFFFFFL
                if (next == 0L || next <= h) break
                h = next
            }
            return h.toDouble()
        }

        fun getSegmentKey(id: Long, seed: Byte, hash: Double): Long {
            if (seed == 0.toByte()) return 0
            val denom = ((id + 1) and 0xFFFFFFFFL) * (seed.toLong() and 0xFF)
            return (hash / denom.toDouble() * 100.0).toLong()
        }
    }

    private val key: ByteArray = key.copyOf()
    private val n: Int = key.size
    private val hashValue: Double = hash(key)
    private val keyStream: ByteArray = ByteArray(RC4_STREAM_CACHE_SIZE)

    init {
        require(n > 0) { "QMC key is empty" }
        val state = initState(n)
        var i = 0
        var j = 0
        // RC4 KSA
        while (i < n) {
            j = (j + (state[i].toInt() and 0xFF) + (key[i % n].toInt() and 0xFF)) % n
            val t = state[i]; state[i] = state[j]; state[j] = t
            i++
        }
        // PRGA 派生 keystream
        i = 0; j = 0
        var p = 0
        while (p < RC4_STREAM_CACHE_SIZE) {
            i = (i + 1) % n
            j = (j + (state[i].toInt() and 0xFF)) % n
            val t = state[i]; state[i] = state[j]; state[j] = t
            keyStream[p] = state[((state[i].toInt() and 0xFF) + (state[j].toInt() and 0xFF)) % n]
            p++
        }
    }

    /** 按文件绝对偏移原地解密，支持流式/断点续传 */
    fun decrypt(data: ByteArray, offset: Long) {
        decryptRange(data, 0, data.size, offset)
    }

    /** 解密缓冲区中 [pos, pos+len) 区段，absoluteOffset 为文件绝对偏移 */
    fun decryptRange(data: ByteArray, pos: Int, len: Int, absoluteOffset: Long) {
        if (pos == 0 && len == data.size) {
            decryptInternal(data, 0, len, absoluteOffset)
            return
        }
        val slice = ByteArray(len)
        System.arraycopy(data, pos, slice, 0, len)
        decryptInternal(slice, 0, len, absoluteOffset)
        System.arraycopy(slice, 0, data, pos, len)
    }

    private fun decryptInternal(data: ByteArray, pos: Int, len: Int, offset: Long) {
        var off = offset
        var p = pos
        val end = pos + len
        if (off < FIRST_SEGMENT_SIZE) {
            val n = minOf(FIRST_SEGMENT_SIZE - off.toInt(), end - p)
            processFirstSegment(data, p, n, off.toInt())
            p += n; off += n
        }
        val excess = (off % OTHER_SEGMENT_SIZE).toInt()
        if (excess != 0) {
            val n = minOf(OTHER_SEGMENT_SIZE - excess, end - p)
            processOtherSegment(data, p, n, off)
            p += n; off += n
        }
        while (p < end) {
            val n = minOf(OTHER_SEGMENT_SIZE, end - p)
            processOtherSegment(data, p, n, off)
            p += n; off += n
        }
    }

    private fun processFirstSegment(data: ByteArray, pos: Int, len: Int, startOffset: Int) {
        for (i in 0 until len) {
            val o = startOffset + i
            val idx = (getSegmentKey(o.toLong(), key[o % n], hashValue) % n.toLong()).toInt()
            data[pos + i] = (data[pos + i].toInt() xor (key[idx].toInt() and 0xFF)).toByte()
        }
    }

    private fun processOtherSegment(data: ByteArray, pos: Int, len: Int, offset: Long) {
        val id = offset / OTHER_SEGMENT_SIZE
        val blockOffset = (offset % OTHER_SEGMENT_SIZE).toInt()
        val seed = key[(id % n).toInt()]
        val skip = (getSegmentKey(id, seed, hashValue) and 0x1FF).toInt()
        for (i in 0 until len) {
            data[pos + i] = (data[pos + i].toInt() xor (keyStream[skip + blockOffset + i].toInt() and 0xFF)).toByte()
        }
    }
}

/**
 * 酷我母带解密总入口：ekey → QMC key → 文件解密器
 *
 * 完整链路（HotDownloader 路线）：
 * 1. kwDES 解 mobi.s 返回的 ekey
 * 2. 去掉 user 前缀（长度 = user 字符串长度）
 * 3. QMC ekey TEA 解密 → QMC key
 */
object KuwoCipher {

    fun createFromEkey(rawEkey: String, user: String): QmcV2Rc4? {
        return try {
            val decrypted = KwDes.decryptBase64(rawEkey)
            if (!String(decrypted, 0, user.length, Charsets.UTF_8).startsWith(user)) return null
            val qmcEkey = String(decrypted, user.length, decrypted.size - user.length, Charsets.UTF_8)
            val qmcKey = QmcEkey.decrypt(qmcEkey)
            QmcV2Rc4(qmcKey)
        } catch (e: Exception) {
            null
        }
    }
}
