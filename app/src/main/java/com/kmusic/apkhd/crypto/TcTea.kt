package com.kmusic.apkhd.crypto

import java.security.MessageDigest

/**
 * tc_tea — 腾讯改版 TEA（16 轮 CBC）
 *
 * 与标准 TEA 的差异：
 * - 16 轮（非 32 轮）
 * - CBC 模式带 IV 链
 * - 密文头部含 pad_size + salt，尾部 7 字节零填充
 *
 * 移植来源：tc_tea-0.2.1 crate
 */
object TcTea {

    private const val DELTA = -0x61c88647 // 0x9E3779B9 as signed int
    private const val ROUNDS = 16
    private const val SALT_LEN = 2
    private const val ZERO_LEN = 7

    private fun singleRound(value: Int, sum: Int, k1: Int, k2: Int): Int {
        val left = (value shl 4) + k1
        val right = (value ushr 5) + k2
        val mid = sum + value
        return left xor mid xor right
    }

    private fun ecbDecrypt(block: Long, key: IntArray): Long {
        var y = (block ushr 32).toInt()
        var z = block.toInt()
        var sum = DELTA * ROUNDS
        for (i in 0 until ROUNDS) {
            z -= singleRound(y, sum, key[2], key[3])
            y -= singleRound(z, sum, key[0], key[1])
            sum -= DELTA
        }
        return (y.toLong() and 0xFFFFFFFFL) shl 32 or (z.toLong() and 0xFFFFFFFFL)
    }

    /** CBC 解密，返回剥离头部 padding/salt 后的明文 */
    fun decrypt(cipher: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 16) { "key size mismatch: ${key.size}" }
        require(cipher.size >= 1 + SALT_LEN + ZERO_LEN && cipher.size % 8 == 0) {
            "invalid data size: ${cipher.size}"
        }
        val k = IntArray(4)
        for (i in 0 until 4) {
            k[i] = ((key[i * 4].toInt() and 0xFF) shl 24) or
                    ((key[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((key[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (key[i * 4 + 3].toInt() and 0xFF)
        }
        val plain = ByteArray(cipher.size)
        var iv1 = 0L
        var iv2 = 0L
        var off = 0
        while (off < cipher.size) {
            val cipherBlock = beReadU64(cipher, off)
            val result = cipherBlock xor iv2
            val nextIv2 = ecbDecrypt(result, k)
            val plainBlock = nextIv2 xor iv1
            beWriteU64(plain, off, plainBlock)
            iv1 = cipherBlock
            iv2 = nextIv2
            off += 8
        }
        val padSize = (plain[0].toInt() and 0b111).toLong()
        val startLoc = (1 + padSize + SALT_LEN).toInt()
        val endLoc = cipher.size - ZERO_LEN
        return plain.copyOfRange(startLoc, endLoc)
    }

    private fun beReadU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun beWriteU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
}

/**
 * QMC ekey 解密（umc_qmc::ekey）
 *
 * 支持 v1（QQ 音乐简单密钥）与 v2（双层 TEA + v1）
 */
object QmcEkey {

    private val V2_PREFIX = "UVFNdXNpYyBFbmNWMixLZXk6"  // "QQMusic EncV2,Key:"

    private val V2_KEY1 = byteArrayOf(
        0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
        0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28
    )

    private val V2_KEY2 = byteArrayOf(
        0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
        0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54
    )

    /** 简单密钥：106.0 + i*0.1 的 tan 绝对值 *100 取整 */
    private val SIMPLE_KEY: ByteArray = ByteArray(8) { i ->
        val v = 106.0 + i * 0.1
        (Math.abs(Math.tan(v)) * 100.0).toInt().toByte()
    }

    fun decrypt(ekey: String): ByteArray {
        return if (ekey.startsWith(V2_PREFIX)) {
            decryptV2(ekey.substring(V2_PREFIX.length))
        } else {
            decryptV1(ekey)
        }
    }

    private fun decryptV1(ekey: String): ByteArray {
        val raw = Base64.decode(ekey)
        require(raw.size >= 12) { "ekey too short" }
        val header = raw.copyOfRange(0, 8)
        val cipher = raw.copyOfRange(8, raw.size)
        // tea_key = SIMPLE_KEY 与 header 逐字节交错
        val teaKey = ByteArray(16)
        for (i in 0 until 8) {
            teaKey[i * 2] = SIMPLE_KEY[i]
            teaKey[i * 2 + 1] = header[i]
        }
        val plaintext = TcTea.decrypt(cipher, teaKey)
        return header + plaintext
    }

    private fun decryptV2(ekey: String): ByteArray {
        val raw = Base64.decode(ekey)
        var d = TcTea.decrypt(raw, V2_KEY1)
        d = TcTea.decrypt(d, V2_KEY2)
        val end = d.indexOfFirst { it == 0.toByte() }.let { if (it < 0) d.size else it }
        val v1 = String(d, 0, end, Charsets.UTF_8)
        return decryptV1(v1)
    }
}
