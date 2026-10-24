package com.kmusic.apkhd.playback

/**
 * 播放器的 WebDAV 鉴权上下文。
 *
 * ExoPlayer 的 MediaItem 没地方塞 HTTP 头，所以把「哪个前缀 + 哪条 Authorization」
 * 放在这里，由 [KuwoEncryptedDataSource] 在 open 时按 URL 前缀判定后注入。
 * 只对 WebDAV 前缀生效，酷我链路的请求不会带上这枚头。
 */
object DavAuth {
    @Volatile
    var prefix: String = ""

    @Volatile
    var header: String? = null

    fun applies(url: String): Boolean =
        prefix.isNotEmpty() && header != null && url.startsWith(prefix)
}
