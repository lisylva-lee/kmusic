package com.kmusic.apkhd.crypto

/** 标准 Base64 编解码（不依赖 android.util，便于单元测试） */
object Base64 {
    private val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(s: String): ByteArray {
        val clean = s.filter { it != '=' && it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        val out = java.io.ByteArrayOutputStream((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = TABLE.indexOf(c)
            require(v >= 0) { "invalid base64 char: $c" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                sb.append(TABLE[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) sb.append(TABLE[(buffer shl (6 - bits)) and 0x3F])
        return sb.toString()
    }
}
