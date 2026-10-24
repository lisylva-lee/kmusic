package com.kmusic.apkhd.audio

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 读音频文件里自带的 ReplayGain 标签。
 *
 * 为什么不用 MediaMetadataRetriever：它只认标准字段（标题/歌手/时长），
 * ReplayGain 是各家自己塞进注释块的私有键，必须自己解析容器。
 *
 * 只读文件头部的元数据区（FLAC 的 metadata block / MP3 的 ID3v2 / MP4 的 moov），
 * 不碰音频数据，几百毫秒内完成。
 */
object TagReader {

    data class Loudness(val trackGainDb: Float?, val albumGainDb: Float?, val trackPeak: Float?)

    fun readReplayGain(context: Context, uri: Uri): Loudness? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = readAtMost(input, 256 * 1024)
            when {
                head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                    head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> parseFlac(head)
                head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                    head[2] == '3'.code.toByte() -> parseId3(head)
                head.size >= 8 && isMp4(head) -> parseMp4(head)
                else -> null
            }
        }
    }.getOrNull()

    private fun isMp4(b: ByteArray): Boolean {
        val tag = String(b, 4, 4, Charsets.ISO_8859_1)
        return tag == "ftyp"
    }

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val n = input.read(buf, off, max - off)
            if (n <= 0) break
            off += n
        }
        return if (off == max) buf else buf.copyOf(off)
    }

    // ---------------- FLAC ----------------

    private fun parseFlac(b: ByteArray): Loudness? {
        var pos = 4
        while (pos + 4 <= b.size) {
            val header = b[pos].toInt() and 0xFF
            val last = (header and 0x80) != 0
            val type = header and 0x7F
            val len = ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or
                (b[pos + 3].toInt() and 0xFF)
            pos += 4
            if (type == 4) { // VORBIS_COMMENT
                val end = (pos + len).coerceAtMost(b.size)
                return parseVorbisComment(b, pos, end)
            }
            pos += len
            if (last) break
        }
        return null
    }

    private fun parseVorbisComment(b: ByteArray, start: Int, end: Int): Loudness? {
        var p = start
        if (p + 4 > end) return null
        val vendorLen = le32(b, p)
        p += 4 + vendorLen
        if (p + 4 > end) return null
        val count = le32(b, p)
        p += 4
        var track: Float? = null
        var album: Float? = null
        var peak: Float? = null
        repeat(count.coerceAtMost(4096)) {
            if (p + 4 > end) return@repeat
            val len = le32(b, p)
            p += 4
            if (len <= 0 || p + len > end) return@repeat
            val line = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = line.indexOf('=')
            if (eq <= 0) return@repeat
            val key = line.substring(0, eq).uppercase()
            val value = line.substring(eq + 1).trim()
            when {
                key == "REPLAYGAIN_TRACK_GAIN" -> track = parseDb(value)
                key == "REPLAYGAIN_ALBUM_GAIN" -> album = parseDb(value)
                key == "REPLAYGAIN_TRACK_PEAK" -> peak = value.toFloatOrNull()
            }
        }
        return if (track == null && album == null && peak == null) null
        else Loudness(track, album, peak)
    }

    /** "−6.53 dB" / "-6.53" / "+2.1 dB" 都要能认 */
    private fun parseDb(raw: String): Float? {
        val cleaned = raw.replace("dB", "", ignoreCase = true)
            .replace('\u2212', '-')
            .trim()
            .replace(',', '.')
        return cleaned.toFloatOrNull()
    }

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    // ---------------- ID3v2 (MP3) ----------------

    private fun parseId3(b: ByteArray): Loudness? {
        if (b.size < 10) return null
        val major = b[3].toInt() and 0xFF
        if (major < 3) return null // v2.2 用 3 字节帧 ID，音乐文件里极少
        val flags = b[5].toInt() and 0xFF
        val size = syncSafe(b, 6)
        var p = 10
        if ((flags and 0x40) != 0) { // 扩展头
            if (p + 4 > b.size) return null
            p += 4 + if (major >= 4) syncSafe(b, p) else be32(b, p)
        }
        val end = (10 + size).coerceAtMost(b.size)
        var track: Float? = null
        var album: Float? = null
        var peak: Float? = null
        while (p + 10 <= end) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = if (major >= 4) syncSafe(b, p + 4) else be32(b, p + 4)
            if (frameSize <= 0 || p + 10 + frameSize > end) break
            if (id == "TXXX") {
                val body = p + 10
                val enc = b[body].toInt() and 0xFF
                val (desc, value) = splitNullTerminated(b, body + 1, p + 10 + frameSize, enc)
                when (desc.uppercase()) {
                    "REPLAYGAIN_TRACK_GAIN" -> track = parseDb(value)
                    "REPLAYGAIN_ALBUM_GAIN" -> album = parseDb(value)
                    "REPLAYGAIN_TRACK_PEAK" -> peak = value.toFloatOrNull()
                }
            }
            p += 10 + frameSize
        }
        return if (track == null && album == null && peak == null) null
        else Loudness(track, album, peak)
    }

    private fun splitNullTerminated(b: ByteArray, start: Int, end: Int, enc: Int): Pair<String, String> {
        val wide = enc == 1 || enc == 2 // UTF-16
        var p = start
        if (wide) {
            while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
            val desc = String(b, start, p - start, Charsets.UTF_16).trim('\u0000', '\uFEFF')
            p += 2
            val value = if (p < end) String(b, p, end - p, Charsets.UTF_16).trim('\u0000', '\uFEFF') else ""
            return desc to value
        }
        while (p < end && b[p].toInt() != 0) p++
        val desc = String(b, start, p - start, Charsets.ISO_8859_1)
        p++
        val value = if (p < end) String(b, p, end - p, Charsets.ISO_8859_1).trim('\u0000') else ""
        return desc to value
    }

    private fun syncSafe(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0x7F) shl 21) or ((b[p + 1].toInt() and 0x7F) shl 14) or
            ((b[p + 2].toInt() and 0x7F) shl 7) or (b[p + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    // ---------------- MP4 / M4A ----------------

    /**
     * 走 atom 树找 moov/udta/meta/ilst 里的 `----` 自由原子。
     * iTunes 把 ReplayGain 写在这：`----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN`。
     */
    private fun parseMp4(b: ByteArray): Loudness? {
        val ilst = findAtom(b, 0, b.size, listOf("moov", "udta", "meta", "ilst")) ?: return null
        var track: Float? = null
        var album: Float? = null
        var peak: Float? = null
        var p = ilst.first
        while (p + 8 <= ilst.second) {
            val size = be32(b, p)
            if (size < 8 || p + size > ilst.second) break
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (type == "----") {
                val name = findFreeformName(b, p + 8, p + size)
                val data = findFreeformData(b, p + 8, p + size)
                if (name != null && data != null) {
                    when (name.uppercase()) {
                        "REPLAYGAIN_TRACK_GAIN" -> track = parseDb(data)
                        "REPLAYGAIN_ALBUM_GAIN" -> album = parseDb(data)
                        "REPLAYGAIN_TRACK_PEAK" -> peak = data.toFloatOrNull()
                    }
                }
            }
            p += size
        }
        return if (track == null && album == null && peak == null) null
        else Loudness(track, album, peak)
    }

    /** 返回 [内容起始, 内容结束)；找不到返回 null */
    private fun findAtom(b: ByteArray, start: Int, end: Int, path: List<String>): Pair<Int, Int>? {
        if (path.isEmpty()) return start to end
        var p = start
        while (p + 8 <= end) {
            val size = be32(b, p)
            if (size < 8 || p + size > end) return null
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (type == path[0]) {
                var body = p + 8
                // meta 是 full box：多 4 字节 version/flags
                if (type == "meta") body += 4
                return findAtom(b, body, p + size, path.drop(1))
            }
            p += size
        }
        return null
    }

    private fun findFreeformName(b: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 8 <= end) {
            val size = be32(b, p)
            if (size < 8 || p + size > end) return null
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (type == "name") {
                val body = p + 12 // 8 头 + 4 版本/flags
                return if (body < p + size) String(b, body, p + size - body, Charsets.UTF_8) else null
            }
            p += size
        }
        return null
    }

    private fun findFreeformData(b: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 8 <= end) {
            val size = be32(b, p)
            if (size < 8 || p + size > end) return null
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (type == "data") {
                val body = p + 16 // 8 头 + 4 类型 + 4 locale
                return if (body < p + size) String(b, body, p + size - body, Charsets.UTF_8).trim('\u0000') else null
            }
            p += size
        }
        return null
    }

    /** 调试用：确认字节序假设（小端）没错 */
    fun dumpLittleEndian(v: Int): ByteBuffer =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).also { it.flip() }
}
