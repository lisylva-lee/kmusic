package com.kmusic.apkhd.playback

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * 已解析直链的「最新版本」登记表。
 *
 * ## 为什么需要
 *
 * 播放器 timeline 里的 MediaItem 一旦铺进去，URL 就固定了。而在线源（酷我 / 网易云 GD /
 * 咪咕）给的都是带签名的临时地址，过一段时间就 403/404 —— 表现是「播到第 N 首突然停住」。
 * 重建 MediaItem 能换链，但会打断播放进度、也可能让通知栏/车机上的列表跳一下。
 *
 * 这里只做一件事：**记住每条曲目最近一次解析出的地址**，让
 * [KuwoEncryptedDataSource] 在 `open()` 时把老 URL 换成新 URL —— 对播放器完全透明。
 *
 * ## key 的取法
 *
 * 用和 `MoyinViewModel.cacheKeyed()` 完全相同的片段：`source:sourceId:qualityLabel`。
 * 它同时是 CacheDataSource 的缓存键，所以「换 URL」不会导致缓存失效重下。
 *
 * ## 时效
 *
 * 拿不到源站的签名时间戳（三家格式都不一样、且随时可能改），所以按**保守固定值**处理：
 * 解析成功即视为 10 分钟有效。[KuwoEncryptedDataSource] 只在登记表里有**未过期**的新地址时
 * 才替换，拿不准就保持原样 —— 宁可让播放器先试，失败由 ViewModel 的 onPlayerError 兜底。
 */
object PlaybackUrlRegistry {

    /** 保守新鲜期：三家签名地址实测都撑得过 10 分钟，超过就当作可疑 */
    const val DEFAULT_TTL_MS: Long = 10L * 60 * 1000

    private class Entry(
        val url: String,
        val resolvedAt: Long,
        val expiresAt: Long
    )

    private val map = ConcurrentHashMap<String, Entry>()

    /**
     * 时钟。单测里替换成可控的假时钟；生产走 `SystemClock.elapsedRealtime()`
     * （不用 wall clock：改系统时间不该让已解析地址突然「过期」或「复活」）。
     */
    internal var clock: (() -> Long)? = null

    private fun now(): Long = clock?.invoke() ?: SystemClock.elapsedRealtime()

    /** 登记（或覆盖）某条曲目的最新直链 */
    fun remember(key: String, url: String, ttlMs: Long = DEFAULT_TTL_MS) {
        if (key.isBlank() || url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val at = now()
        map[key] = Entry(url, at, at + ttlMs)
    }

    /** 最新地址；没登记或已过期返回 null */
    fun fresh(key: String): String? {
        val e = map[key] ?: return null
        return if (now() < e.expiresAt) e.url else null
    }

    /**
     * 「这次到底该打开哪条地址」。
     *
     * 登记表里有没过期的新地址就用它，否则原样用调用方给的（老地址 / 本地 file://）。
     * 单测覆盖这一个决策点，DataSource 里的分支就不用再猜。
     */
    fun pick(key: String, original: String): String = fresh(key) ?: original

    /** 距离过期还有多久（毫秒）；没登记返回 -1（可能为负 = 已过期） */
    fun remainingMs(key: String): Long {
        val e = map[key] ?: return -1
        return e.expiresAt - now()
    }

    /** 这条地址是多久以前解析的（毫秒）；没登记返回 -1 */
    fun ageMs(key: String): Long {
        val e = map[key] ?: return -1
        return now() - e.resolvedAt
    }

    fun forget(key: String) {
        map.remove(key)
    }

    fun clear() = map.clear()
}
