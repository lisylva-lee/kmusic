package com.kmusic.apkhd.data

import android.content.Context
import com.kmusic.apkhd.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地扫描结果的落盘缓存。
 *
 * 解决什么问题：用户点了「添加文件夹」之后，每次冷启都要把 SAF 目录整个走一遍、
 * 每个文件用 [android.media.MediaMetadataRetriever] 现读标签 —— 几百首就是几十秒，
 * 表现成「每次打开都要重新扫」。
 *
 * 缓存里存两样东西：
 * 1. **曲目本身** —— 启动时先把它塞进 UI，列表立刻就有内容（≈0.1s），
 *    用户在后台校验期间就能看到歌、甚至能直接播（PlayUrl 是 content:// 直链）。
 * 2. **文件指纹**（docId → 修改时间 + 大小）—— 下次扫描只对**变了**的文件重读标签，
 *    没变的直接复用曲目。这是「秒开」的关键，光缓存曲目不够。
 *
 * 为什么按 treeUri 分组：用户可能加多个目录，删掉一个不该让另一个的缓存失效。
 */
@Serializable
data class CachedSafEntry(
    val docId: String,
    val name: String,
    /** 文件最后修改时间（毫秒，DocumentFile 语义；0 = 提供方不报，此时一律重读） */
    val lastModified: Long,
    /** 文件字节数 */
    val size: Long,
    /** 解析好的曲目 —— 指纹未变时直接复用，省掉一次 MediaMetadataRetriever */
    val track: Track
)

@Serializable
data class ScanCacheData(
    val version: Int = CACHE_VERSION,
    /** treeUri → (docId → 条目) */
    val trees: Map<String, List<CachedSafEntry>> = emptyMap(),
    /** treeUri → 曲目数（给「扫描设置」页显示真实数量，不再是一直 -1） */
    val counts: Map<String, Int> = emptyMap(),
    /**
     * treeUri → 第一次「读到 0 项」的时间戳（epoch ms）。
     *
     * 为什么要落盘：用它区分「provider 还没就绪」（要保住旧曲目）和
     * 「用户真把目录清空了」（要跟着清，否则列表里全是点不开的幽灵歌）。
     * 判据是**持续多久都读不到**，而 App 进程随时会被杀 —— 只放内存的话
     * 每次冷启都从 0 开始计时，永远到不了阈值，幽灵歌就永远清不掉。
     */
    val emptySince: Map<String, Long> = emptyMap()
) {
    companion object {
        /** 结构一变就 +1，旧缓存自动作废（反序列化失败也会兜底重扫） */
        const val CACHE_VERSION = 1
    }
}

/**
 * 扫描缓存的读写。
 *
 * 用普通文件 + 原子替换，不引 Room：曲库是「一次写、多次读」的扁平结构，
 * 上数据库属于过度设计（47 首 vs 1000 首，读一个 JSON 都在 20ms 内）。
 */
class LocalScanCache(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true       // 版本升级加了字段也不至于让整个缓存报废
        encodeDefaults = true
    }

    private val file: File
        get() = File(context.filesDir, "local_scan_cache.json")

    /** 读缓存；文件不存在/损坏都返回空对象，不抛异常 */
    fun load(): ScanCacheData {
        val f = file
        if (!f.exists()) return ScanCacheData()
        return runCatching {
            val data = json.decodeFromString<ScanCacheData>(f.readText())
            // 版本不符 → 当作没有缓存（宁可重扫一次，也别读到结构错位的数据）
            if (data.version != ScanCacheData.CACHE_VERSION) ScanCacheData() else data
        }.getOrDefault(ScanCacheData())
    }

    /** 写缓存（原子替换：先写 .tmp 再 rename，避免写到一半进程被杀留下半截 JSON） */
    fun save(data: ScanCacheData) {
        runCatching {
            val tmp = File(context.filesDir, "local_scan_cache.json.tmp")
            tmp.writeText(json.encodeToString(ScanCacheData.serializer(), data))
            if (!tmp.renameTo(file)) {
                // 某些文件系统上 rename 到已存在的文件会失败，退化成覆盖写
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
