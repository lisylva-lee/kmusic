package com.kmusic.apkhd.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kmusic.apkhd.crypto.QmcV2Rc4
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载器：取母带/无损直链 → 流式解密 → 落盘到系统音乐目录。
 *
 * 酷我直链返回的是加密流（mflac/kflac），**不能直接存**，必须边下边解密，
 * 否则存下来的是没人能播的噪音文件。解密按文件绝对偏移对齐（见 QmcV2Rc4.decryptRange），
 * 所以这里必须持续维护 absoluteOffset。
 */
class DownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 下载目录名（系统「音乐」下的子目录） */
    private val albumDir = "KMusic"

    /**
     * 落盘扩展名。
     *
     * 以前只认 flac/mp3，WebDAV 的 .m4a 会被存成 .mp3 —— 文件内容没错，
     * 但别的播放器/扫描器会按扩展名选解码器，能播也容易被当成坏文件。
     * 所以优先用 URL 自带的扩展名，其次看音质标签，最后才兜底 mp3。
     */
    private fun extOf(qualityLabel: String, url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val fromUrl = path.substringAfterLast('.', "").lowercase()
        if (fromUrl in knownExt) return fromUrl
        return when {
            qualityLabel.contains("母带") || qualityLabel.contains("无损") -> "flac"
            else -> "mp3"
        }
    }

    private val knownExt = setOf("mp3", "flac", "m4a", "aac", "wav", "ape", "ogg", "opus", "wma")

    // ---------------- 断点续传 ----------------

    /**
     * .part 放在 App 私有缓存目录，**不直接写 MediaStore**。
     *
     * 两个原因：
     * 1. 断点续传要能追加写 —— MediaStore 的 IS_PENDING 条目追加写不可靠；
     * 2. 中途失败不会在系统音乐库里留下半截文件（用户会看到一首播不了的歌）。
     */
    private fun partFile(trackId: String): File {
        val dir = File(context.cacheDir, "dl")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, trackId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".part")
    }

    /** 已下载的字节数（用于判断能否续传） */
    fun partSize(trackId: String): Long = partFile(trackId).let { if (it.exists()) it.length() else 0L }

    fun clearPart(trackId: String) {
        runCatching { partFile(trackId).delete() }
    }

    /**
     * 下载到 .part，已存在的内容会被续传。
     *
     * ⚠️ 续传只对**位置相关**的流密码安全：解密按文件绝对偏移对齐，
     * 所以从第 N 字节接着解，结果和一次性解完完全一致。
     * 但前提是服务器真的给了 206 —— 若返回 200（不认 Range 的全量），
     * 必须从头重下，否则会把两段不同的数据拼在一起，产出噪音文件。
     */
    suspend fun fetchPart(
        trackId: String,
        url: String,
        ekey: String?,
        /** 取流时的 user —— ekey 解密要求与之一致，写死会导致母带解不开 */
        user: String,
        /** 额外请求头（WebDAV 的 Basic 鉴权走这里） */
        headers: Map<String, String> = emptyMap(),
        onProgress: (done: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val cipher: QmcV2Rc4? = ekey?.takeIf { it.isNotEmpty() }
            ?.let { com.kmusic.apkhd.crypto.KuwoCipher.createFromEkey(it, user) }
        val part = partFile(trackId)
        val have = if (part.exists()) part.length() else 0L

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.10.0")
        // 酷我的 Referer 只对酷我链有意义；WebDAV 别带，免得被 WAF 拦或泄漏来源
        if (ekey != null) builder.header("Referer", "http://www.kuwo.cn/")
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (have > 0) builder.header("Range", "bytes=$have-")

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("空响应体")

            val canResume = canResume(have, resp.code)
            val startAt = if (canResume) have else 0L
            if (!canResume && part.exists()) part.delete()

            val total = if (body.contentLength() > 0) body.contentLength() + startAt else -1L
            java.io.FileOutputStream(part, canResume).use { os ->
                val buf = ByteArray(64 * 1024)
                var offset = startAt
                var written = startAt
                while (true) {
                    val n = body.byteStream().read(buf)
                    if (n <= 0) break
                    cipher?.decryptRange(buf, 0, n, offset)
                    os.write(buf, 0, n)
                    offset += n
                    written += n
                    onProgress(written, total)
                }
                os.flush()
            }
            part
        }
    }

    /** 把下好的 .part 落进系统音乐目录，成功后删掉 .part */
    suspend fun publishPart(
        track: Track,
        part: File,
        qualityLabel: String,
        url: String
    ): String = withContext(Dispatchers.IO) {
        val fileName = buildFileName(track, extOf(qualityLabel, url))
        val sink = openSink(fileName)
        try {
            part.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                }
            }
            sink.finish()
        } catch (e: Exception) {
            sink.abort()
            throw e
        }
        part.delete()
        "$albumDir/$fileName"
    }

    // ---------------- 落盘抽象：API 29+ 走 MediaStore，以下走公共目录 ----------------

    private interface Sink {
        fun write(b: ByteArray, off: Int, len: Int)
        fun finish()
        fun abort()
    }

    private fun openSink(fileName: String): Sink {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$albumDir")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore 创建条目失败")
            val os = resolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入")
            object : Sink {
                override fun write(b: ByteArray, off: Int, len: Int) = os.write(b, off, len)
                override fun finish() {
                    os.flush(); os.close()
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                override fun abort() {
                    runCatching { os.close() }
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                albumDir
            )
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            val os = f.outputStream()
            object : Sink {
                override fun write(b: ByteArray, off: Int, len: Int) = os.write(b, off, len)
                override fun finish() { os.flush(); os.close() }
                override fun abort() { runCatching { os.close() }; runCatching { f.delete() } }
            }
        }
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".flac") -> "audio/flac"
        name.endsWith(".m4a") || name.endsWith(".aac") -> "audio/mp4"
        name.endsWith(".wav") -> "audio/wav"
        name.endsWith(".ogg") || name.endsWith(".opus") -> "audio/ogg"
        else -> "audio/mpeg"
    }

    companion object {
        /**
         * 能否从已有字节续传。
         *
         * **只有服务器回 206 Partial Content 才算数** —— 那才是真的按 Range 返回了剩余部分。
         * 回 200 说明 Range 被忽略（发回来的是完整文件），必须从头下：
         * 否则会把「从第 N 字节开始的完整文件」接到「已有的前 N 字节」后面，
         * 拼出一段错位的数据，用户得到一个能打开但全是噪音的文件。
         */
        fun canResume(existingBytes: Long, httpCode: Int): Boolean =
            existingBytes > 0 && httpCode == 206
    }

    private fun buildFileName(track: Track, ext: String): String {
        val base = if (track.artist.isNotEmpty()) "${track.artist} - ${track.name}" else track.name
        // 去掉路径非法字符
        val safe = base.replace(Regex("""[/\\:*?"<>|]"""), "_").take(80)
        return "$safe.$ext"
    }
}
