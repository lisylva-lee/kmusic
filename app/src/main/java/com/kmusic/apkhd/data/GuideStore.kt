package com.kmusic.apkhd.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * AI 导赏的磁盘缓存（一个 trackId 一个文件）。
 *
 * ## 为什么不放 DataStore / SharedPreferences
 *
 * 这两者**每次写都要重写整个文件**（本项目 `PlaybackSnapshotStore` 的注释里
 * 就踩过这个坑）。导赏一篇约 1.5 KB，攒到 500 首就是 750 KB ——
 * 每生成一篇都要重写 750 KB，白烧闪存，还会随缓存增长越来越慢。
 *
 * 一个 trackId 一个文件：写一篇只写 1.5 KB，删一条只删一个文件，
 * 淘汰按 mtime 找最旧的即可。
 *
 * ## 为什么需要缓存
 *
 * 导赏按 token 计费。原来只缓存在内存，杀进程重进就没了，同一首歌会被反复收费；
 * 用户「看了一半关掉面板」的那半篇更是直接蒸发 —— 钱花了，字没了。
 *
 * ## 文件格式
 *
 * 第一行是标志位（`1` = 写完，`0` = 只有半篇），从第二个字节起是正文。
 * 用首行而不是文件头魔数，出问题时 `cat` 一下就能看懂。
 */
class GuideStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "guides").apply { if (!exists()) mkdirs() }

    /**
     * trackId → 文件名。
     *
     * trackId 形如 `kw:1044318`，含冒号；不同音源的 id 还可能出现斜杠。
     * 直接拿来当文件名在 Windows 侧和某些文件系统上会炸，所以取 MD5 十六进制。
     * 哈希碰撞在这点数据量下可以忽略（500 条 128 bit）。
     */
    private fun fileOf(trackId: String): File {
        val name = MessageDigest.getInstance("MD5")
            .digest(trackId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$name.txt")
    }

    /** 本条是否**写完过**（false = 只有半篇：用户中途停止或进程被杀） */
    suspend fun isComplete(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val f = fileOf(trackId)
        if (!f.exists()) return@withContext false
        runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull() == DONE
    }

    /** 命中返回正文（半篇也返回，界面自己标注）；未命中 null */
    suspend fun read(trackId: String): String? = withContext(Dispatchers.IO) {
        val f = fileOf(trackId)
        if (!f.exists()) return@withContext null
        runCatching {
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl < 0) text else text.substring(nl + 1)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun write(trackId: String, body: String, complete: Boolean = true) = withContext(Dispatchers.IO) {
        if (body.isBlank()) return@withContext
        val f = fileOf(trackId)
        runCatching {
            // 先写临时文件再 rename：写到一半被杀进程时，不会留下半截文件被当成完整缓存
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText((if (complete) DONE else PARTIAL) + "\n" + body)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            evictIfNeeded()
        }
        Unit
    }

    /** 超出上限：按最后修改时间删最旧的若干个（一次淘汰多删 10%，避免每写一篇都淘汰） */
    private fun evictIfNeeded() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES + MAX_ENTRIES / 10)
            .forEach { it.delete() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private companion object {
        /** 最多存多少首：500 × 1.5 KB ≈ 750 KB，够用且不会让目录扫描变慢 */
        const val MAX_ENTRIES = 500

        const val DONE = "1"
        const val PARTIAL = "0"
    }
}
