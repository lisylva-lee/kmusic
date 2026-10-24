package com.kmusic.apkhd.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 播放缓存（media3 SimpleCache）。
 *
 * 为什么要自己管：
 * - 酷我/咪咕给的是**带签名的临时直链**，同一首歌每次解析 URL 都不同；
 *   如果按 URL 缓存，永远 miss。解决办法是播放地址带上 `#k=<source>:<id>:<音质>` 片段，
 *   HTTP 会忽略片段，而 [androidx.media3.datasource.cache.CacheDataSource] 用它当缓存 key。
 * - 缓存必须包在 `KuwoEncryptedDataSource` **外层**：先解密再落盘，
 *   否则缓存里存的是密文，二次播放会得到噪音。
 *
 * 容量 0 表示关闭；切换容量会释放旧实例（下次播放生效）。
 */
@UnstableApi
object MediaCache {

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var currentGb: Int = -1

    @Synchronized
    fun get(context: Context, gb: Int): SimpleCache? {
        if (gb <= 0) {
            cache?.release()
            cache = null
            currentGb = -1
            return null
        }
        val existing = cache
        if (existing != null && currentGb == gb) return existing
        existing?.release()
        val dir = File(context.cacheDir, "media")
        val evictor = LeastRecentlyUsedCacheEvictor(gb.toLong() * 1024 * 1024 * 1024)
        val created = SimpleCache(dir, evictor, StandaloneDatabaseProvider(context))
        cache = created
        currentGb = gb
        return created
    }

    /** 清空所有缓存条目（保留目录和数据库） */
    fun clear() {
        val c = cache ?: return
        runCatching {
            c.keys.toList().forEach { key -> c.removeResource(key) }
        }
    }

    /** 当前缓存占用（字节） */
    fun sizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, "media")
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
