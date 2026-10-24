package com.kmusic.apkhd.crypto

/**
 * kwDES — 酷我自定义 DES 变体（um_crypto 版实现）
 *
 * 与标准 DES 的差异：
 * - LSB-first 位序
 * - S-box 按 to_be_bytes 逐字节索引
 * - 解密时子密钥倒序填充，但 shift 表仍正序遍历
 *
 * 移植来源：libs/um_crypto/um_crypto/kuwo/src/des/{core,helper,constants}.rs
 */
object KwDes {

    private val SBOXES: Array<IntArray> = SBOX_TABLE

    fun decryptBase64(data: String, key: ByteArray = SECRET_KEY): ByteArray {
        val raw = Base64.decode(data)
        require(raw.size % 8 == 0) { "invalid DES data size: ${raw.size}" }
        val decrypted = transform(raw, key, decrypt = true)
        // Rust: decrypt_ksing 返回前会 rstrip(b"\x00") 去掉尾部零填充
        var end = decrypted.size
        while (end > 0 && decrypted[end - 1] == 0.toByte()) end--
        return decrypted.copyOfRange(0, end)
    }



    /** 酷我 ekey 解码：kwDES 解密后去掉前 16 字节前缀 */
    fun decodeEkey(data: String, key: ByteArray = SECRET_KEY): String {
        val decrypted = decryptBase64(data, key)
        return String(decrypted, 16, decrypted.size - 16, Charsets.UTF_8)
    }

    private fun transform(data: ByteArray, key: ByteArray, decrypt: Boolean): ByteArray {
        val subkeys = subkeyExpansion(key, decrypt)
        val out = ByteArray(data.size)
        var i = 0
        while (i < data.size) {
            val block = bytesToU64(data, i)
            out.writeU64(transformBlock(block, subkeys), i)
            i += 8
        }
        return out
    }

    private fun subkeyExpansion(key: ByteArray, decrypt: Boolean): LongArray {
        val k = bytesToU64(key, 0)
        var param = mapU64(k, KEY_PERMUTATION_TABLE)
        val subkeys = LongArray(16)
        // Rust: subkeys.iter_mut().rev() —— 倒序遍历目标数组（第一轮结果写入 slot 15），
        // 而 zip 的 KEY_RND_SHIFTS 仍按正序遍历（第 i 轮用 shift[i]）。
        for (shiftIdx in 0 until 16) {
            val shl = KEY_RND_SHIFTS[shiftIdx]
            val mask = SHIFT_MASKS[shl]
            param = ((param and mask) shl (28 - shl)) or ((param and mask.inv()) shr shl)
            subkeys[if (decrypt) 15 - shiftIdx else shiftIdx] = mapU64(param, KEY_COMPRESSION)
        }
        return subkeys
    }

    private fun roundFn(state: Long, subkey: Long): Long {
        val oldLeft = (state ushr 32) and 0xFFFFFFFFL
        val oldRight = state and 0xFFFFFFFFL
        var s = mapU64(oldLeft, KEY_EXPANSION) xor subkey
        var right = 0L
        val be = u64ToBeBytes(s)
        for (i in 0 until 8) {
            right = ((right shl 4) or SBOXES[i][be[i].toInt() and 0xFF].toLong()) and 0xFFFFFFFFL
        }
        val f = mapU64(right, PBOX) xor oldRight
        return ((f shl 32) or oldLeft)
    }

    private fun transformBlock(data: Long, subkeys: LongArray): Long {
        var state = mapU64(data, IP)
        for (sk in subkeys) state = roundFn(state, sk)
        state = ((state ushr 32) or (state shl 32))
        return mapU64(state, IP_INV)
    }

    // ---------- 位操作工具 ----------

    private fun mapU64(data: Long, table: IntArray): Long {
        var ret = 0L
        for (i in table.indices) {
            val idx = table[i]
            if (idx == 255) continue
            if ((data shr idx) and 1L != 0L) ret = ret or (1L shl i)
        }
        return ret
    }

    private fun bytesToU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    private fun ByteArray.writeU64(v: Long, off: Int) {
        for (i in 0 until 8) this[off + i] = ((v ushr (i * 8)) and 0xFF).toByte()
    }

    private fun u64ToBeBytes(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
        return out
    }

    // ---------- 常量表（提取自 um_crypto constants.rs） ----------

    private val SECRET_KEY = "ylzsxkwm".toByteArray()

    private val KEY_RND_SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val SHIFT_MASKS = longArrayOf(0, 0x100001, 0x300003)

    private val IP = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6
    )

    private val IP_INV = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24
    )

    private val KEY_PERMUTATION_TABLE = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 62, 54, 46, 38,
        30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36,
        28, 20, 12, 4, 27, 19, 11, 3
    )

    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 255, 255, 2, 27, 14, 5, 20, 9, 255, 255,
        22, 18, 11, 3, 25, 7, 255, 255, 15, 6, 26, 19, 12, 1, 255, 255,
        40, 51, 30, 36, 46, 54, 255, 255, 29, 39, 50, 44, 32, 47, 255, 255,
        43, 48, 38, 55, 33, 52, 255, 255, 45, 41, 49, 35, 28, 31, 255, 255
    )

    private val KEY_EXPANSION = intArrayOf(
        31, 0, 1, 2, 3, 4, 255, 255, 3, 4, 5, 6, 7, 8, 255, 255,
        7, 8, 9, 10, 11, 12, 255, 255, 11, 12, 13, 14, 15, 16, 255, 255,
        15, 16, 17, 18, 19, 20, 255, 255, 19, 20, 21, 22, 23, 24, 255, 255,
        23, 24, 25, 26, 27, 28, 255, 255, 27, 28, 29, 30, 31, 30, 255, 255
    )

    private val PBOX = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24
    )
}
