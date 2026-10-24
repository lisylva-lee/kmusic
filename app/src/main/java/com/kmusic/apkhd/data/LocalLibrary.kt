package com.kmusic.apkhd.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.kmusic.apkhd.model.LocalFolder
import com.kmusic.apkhd.model.Track

/**
 * 本地音乐扫描 —— MediaStore 查询 + 元数据抽取 + 用户指定目录。
 *
 * ⚠️ READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE 是**运行时权限**，
 * 仅 Manifest 声明不够；未授权时 query() 会抛 SecurityException。
 * 所以 scan() 内部必须兜底，且调用前应检查 hasAudioPermission()。
 *
 * ⚠️ MediaStore **看不见别的 App 的私有目录**（安卓 11+ 分区存储起）：
 * 用户手机里有歌却一首扫不出来，基本就是这个原因，不是代码坏了。
 * 所以除了 MediaStore，还提供 [folders] 让用户挑选目录、[scanTree] 扫用户手动指定的目录。
 */
class LocalLibrary(private val context: Context) {

    /** 是否已获得读取本地音频的权限 */
    fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** API29+ 有 RELATIVE_PATH；更低版本只能从 DATA 推父目录 */
    private val isRelativePath: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val folderColumn: String
        get() = if (isRelativePath) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA

    /**
     * 归一成「目录键」。两种来源的键不跨设备比较，只要同一台设备内一致即可。
     */
    private fun folderKey(raw: String?, relative: Boolean = isRelativePath): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return ""
        return if (relative) v.trimEnd('/', '\\') else (java.io.File(v).parent ?: v)
    }

    private fun folderName(key: String): String =
        if (key.isEmpty()) "（存储根目录）" else key.substringAfterLast('/').ifBlank { key }

    /**
     * MediaStore 里的音频按目录聚合 —— 给「扫描设置」列文件夹用。
     * 这是「让用户选择扫哪个文件夹」的数据来源。
     */
    fun folders(): List<LocalFolder> {
        if (!hasAudioPermission()) return emptyList()
        val counts = mutableMapOf<String, Int>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(folderColumn),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null, null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(folderColumn)
                while (c.moveToNext()) {
                    val k = folderKey(c.getString(idx))
                    counts[k] = (counts[k] ?: 0) + 1
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return counts.map { (k, n) -> LocalFolder(k, folderName(k), n) }
            .sortedWith(compareByDescending<LocalFolder> { it.trackCount }.thenBy { it.name })
    }

    /**
     * 扫描本地音频文件，返回曲目列表（按标题排序）。未授权或异常时返回空列表，不抛异常。
     *
     * @param enabledFolders 只扫这些目录；**空集合 = 不限制**（不是「全不扫」）
     */
    fun scan(enabledFolders: Set<String> = emptySet()): List<Track> {
        if (!hasAudioPermission()) return emptyList()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            folderColumn
        )
        val out = mutableListOf<Track>()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        // ⚠️ 别用 `TITLE COLLATE LOCALIZED`：Android 14+ 的 MediaProvider 开了严格 SQL 语法，
        // 不认 LOCALIZED 这个 collation，整条 query 直接抛 IllegalArgumentException，
        // 被下面的 catch 一吞就变成「一首歌都扫不到」。中文排序交给 Kotlin 侧做。
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sortOrder
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iFolder = c.getColumnIndexOrThrow(folderColumn)
                while (c.moveToNext()) {
                    if (enabledFolders.isNotEmpty() &&
                        folderKey(c.getString(iFolder)) !in enabledFolders
                    ) continue
                    val title = c.getString(iTitle)?.ifBlank { null } ?: continue
                    val albumId = c.getLong(iAlbumId)
                    out += Track(
                        id = "local:${c.getLong(iId)}",
                        name = title,
                        artist = c.getString(iArtist)?.ifBlank { "未知歌手" } ?: "未知歌手",
                        album = c.getString(iAlbum)?.ifBlank { "" } ?: "",
                        coverUrl = if (albumId > 0) albumArtUri(albumId) else null,
                        durationMs = c.getLong(iDur).coerceAtLeast(0),
                        source = "local",
                        sourceId = c.getLong(iId).toString()
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    /**
     * 扫描用户手动添加的目录（SAF tree uri，递归）。
     *
     * 为什么非要它：安卓 11+ 起别的 App 的私有目录不进 MediaStore，
     * 「手机里明明有歌却扫不到」没法靠权限解决，只能让用户把目录直接指给它。
     * 这类目录没有现成的媒体索引，元数据用 MediaMetadataRetriever 现读（慢，务必放 IO 线程）。
     */
    fun scanTree(treeUri: String): List<Track> = scanTreeIncremental(treeUri, null).tracks

    /**
     * 增量扫描一棵 SAF 目录树。
     *
     * 核心思路：**遍历照做，但只对变了的文件读标签**。
     * - 目录遍历本身很便宜（几千个文件也就几十毫秒）；
     * - [MediaMetadataRetriever.setDataSource] 才是贵的那个（普通文件 20–200ms，
     *   DSD 更慢 —— mmr 根本不解 DSF/DFF，每次都要走完"失败再兜底"的路径）。
     *
     * 指纹用 `docId + lastModified + size`：任何一个变了就重读。
     * 提供方不报 lastModified（返回 0）时**一律重读**，宁可慢也不能读到错数据。
     *
     * @param previous 上次的缓存（docId → 条目）；null = 没有缓存，全量读
     * @return 曲目列表 + 新指纹表（调用方负责落盘）。**[ok] 为 false 时前两项不可信**
     */
    fun scanTreeIncremental(
        treeUri: String,
        previous: Map<String, CachedSafEntry>?
    ): TreeScanResult {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: return TreeScanResult(emptyList(), emptyMap(), reused = 0, parsed = 0, ok = false)
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: return TreeScanResult(emptyList(), emptyMap(), reused = 0, parsed = 0, ok = false)

        // ⚠️ **必须显式判存在性**，不能只看 listFiles() 的结果。
        // 目录被删、SD 卡重新挂载、授权还没就绪（进程刚起来 SAF provider 尚未 ready）
        // 这些情况下 `DocumentFile.exists()` 为 false，而 `listFiles()` 会
        // **返回空数组而不是抛异常** —— 光看"扫到 0 首"根本分不清
        // 「目录真的是空的」和「这次读不到」。后者如果被当成前者，
        // 就会把正常曲库覆盖成 0 首、顺便把缓存也清空（v2.2.3 的真 bug）。
        if (!runCatching { root.exists() }.getOrDefault(false)) {
            return TreeScanResult(emptyList(), emptyMap(), reused = 0, parsed = 0, ok = false)
        }
        if (!runCatching { root.canRead() }.getOrDefault(false)) {
            return TreeScanResult(emptyList(), emptyMap(), reused = 0, parsed = 0, ok = false)
        }

        // ⚠️ **“目录可读但列出 0 项”必须和“目录真的是空的”分开**（真机上的真 bug）。
        // 真机冷启时 DocumentsProvider 常常还没把子项准备好：`exists()`=true、
        // `canRead()`=true，但 `listFiles()` 返回**空数组**。这时如果当成「空的」，
        // 就会把正常曲库判成 0 首，并把空结果写进缓存 → **以后每次冷启都是 0 首**，
        // 点「重新扫描」时 provider 已就绪才恢复（正是用户报的现象）。
        //
        // 判据：缓存里这棵树本来有 N 首，这次却列出 0 项 → 极可能是 provider 未就绪，
        // 先当作「读不到」（ok=false），让调用方保留旧数据 + 稍后重试。
        // 只有缓存里本来也是 0 首时，才接受「真的空」。
        // 注意：文件真被全删时也会走这条路，调用方靠「连续多轮都读不到」兜底失效缓存，
        // 见 MoyinViewModel 的 SAF_EMPTY_GIVE_UP_ROUNDS。
        val cachedCount = previous?.size ?: 0
        if (cachedCount > 0) {
            val listed = runCatching { root.listFiles().size }.getOrNull()
            if (listed == 0) {
                return TreeScanResult(emptyList(), emptyMap(), reused = 0, parsed = 0, ok = false)
            }
        }

        val tracks = mutableListOf<Track>()
        val entries = LinkedHashMap<String, CachedSafEntry>()
        var reused = 0
        var parsed = 0
        val complete = walkIncremental(root, previous, tracks, entries, 0, { reused++ }, { parsed++ })
        // 遍历中途某个子目录 listFiles() 失败 → 这棵树的结果是**残缺**的。
        // 残缺结果不能拿去覆盖缓存：被跳过的那些歌会在缓存里消失，
        // 下次就算读得到也要重新读一遍标签（白丢性能），更糟的是本轮 UI 会少歌。
        return TreeScanResult(tracks, entries, reused, parsed, ok = complete)
    }

    /**
     * 一棵树的扫描结果。[reused] / [parsed] 只用于诊断日志，界面不关心。
     *
     * [ok] = **这棵树是否被完整读到**。false 时 [tracks] / [entries] 不可用于覆盖旧数据。
     */
    data class TreeScanResult(
        val tracks: List<Track>,
        val entries: Map<String, CachedSafEntry>,
        val reused: Int,
        val parsed: Int,
        val ok: Boolean = true
    )

    /** @return 是否完整遍历（任何一层 listFiles() 失败都算不完整） */
    private fun walkIncremental(
        dir: DocumentFile,
        previous: Map<String, CachedSafEntry>?,
        out: MutableList<Track>,
        entries: MutableMap<String, CachedSafEntry>,
        depth: Int,
        onReused: () -> Unit,
        onParsed: () -> Unit
    ): Boolean {
        if (depth > 8) return true      // 防异常深的目录树把扫描拖死；深度上限本身不算失败
        val children = runCatching { dir.listFiles() }.getOrNull()
            ?: return false             // 读不到这一层 → 结果残缺
        var complete = true
        for (f in children) {
            if (f.isDirectory) {
                if (!walkIncremental(f, previous, out, entries, depth + 1, onReused, onParsed)) {
                    complete = false
                }
                continue
            }
            val name = f.name ?: continue
            if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXT) continue

            val docId = f.uri.toString()
            // length()/lastModified() 各是一次 provider 查询，但比读一次标签便宜一个数量级
            val lm = runCatching { f.lastModified() }.getOrDefault(0L)
            val sz = runCatching { f.length() }.getOrDefault(0L)
            val cached = previous?.get(docId)

            // 指纹一致 → 直接复用曲目对象，一次标签都不读
            if (cached != null && lm > 0 && cached.lastModified == lm && cached.size == sz) {
                out += cached.track
                entries[docId] = cached
                onReused()
                continue
            }

            val track = readSafTrack(docId, name)
            out += track
            entries[docId] = CachedSafEntry(docId, name, lm, sz, track)
            onParsed()
        }
        return complete
    }

    private fun readSafTrack(uriStr: String, fileName: String): Track {
        var title = fileName.substringBeforeLast('.')
        var artist = "未知歌手"
        var album = ""
        var durMs = 0L
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, Uri.parse(uriStr))
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.ifBlank { null }?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.ifBlank { null }?.let { artist = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.ifBlank { null }?.let { album = it }
            durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // 读不出标签就用文件名兜底 —— 宁可显示得糙，也别把歌丢掉
        } finally {
            runCatching { mmr.release() }
        }
        return Track(
            id = "local:$uriStr",
            name = title,
            artist = artist,
            album = album,
            coverUrl = null,
            durationMs = durMs,
            source = "local",
            sourceId = uriStr
        )
    }

    /** 专辑封面 content:// URI（系统提供，无需自己加载图片文件） */
    private fun albumArtUri(albumId: Long): String =
        "content://media/external/audio/albumart/$albumId"

    /** 单曲详细元数据 —— 只在需要时调用（比特率/采样率用于显示音质） */
    fun readQuality(uri: Uri): String? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val bits = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            val rate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            when {
                bits == null && rate == null -> null
                bits != null && bits >= 900_000 -> "臻品母带"
                bits != null && bits >= 600_000 -> "无损 FLAC"
                bits != null && bits >= 256_000 -> "高品质"
                else -> "标准"
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 本地曲目的可直接播放 URI。SAF 曲目的 sourceId 本身就是 content:// uri */
    fun trackUri(track: Track): Uri =
        if (track.sourceId.startsWith("content://")) Uri.parse(track.sourceId)
        else Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.sourceId)

    companion object {
        /** SAF 目录扫描认的扩展名 */
        private val AUDIO_EXT = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "mka", "dsf", "dff"
        )
    }
}
