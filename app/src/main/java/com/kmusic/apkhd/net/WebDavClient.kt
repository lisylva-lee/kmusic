package com.kmusic.apkhd.net

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** 一个目录项（文件或文件夹） */
data class DavEntry(
    val name: String,
    /** 绝对 URL（已编码，可直接播放/下载） */
    val url: String,
    val isDir: Boolean,
    val size: Long = 0,
    val mtime: String = "",
    val mime: String = ""
) {
    val isAudio: Boolean
        get() {
            val n = name.lowercase()
            return AUDIO_EXT.any { n.endsWith(it) } || mime.startsWith("audio/")
        }

    companion object {
        val AUDIO_EXT = listOf(".mp3", ".flac", ".m4a", ".aac", ".wav", ".ape", ".ogg", ".opus", ".wma")
    }
}

/**
 * 极简 WebDAV 客户端：只做两件事 —— 列目录、给播放/下载拼鉴权头。
 *
 * 为什么不用 sardine 之类的库：只需要 PROPFIND Depth:1 和 Basic Auth，
 * 一个 XML 解析器 + OkHttp 就够了，少一个依赖、少一份体积。
 *
 * 服务器兼容性：
 *   - 兼容 207 Multi-Status 与少数返回 200 的实现；
 *   - href 可能是绝对 URL、绝对路径或相对路径，三种都处理；
 *   - 标签按 localName 匹配，不依赖命名空间前缀（d: / D: / lp1: 都见过）。
 */
class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val pass: String,
    /** 有些服务器（或它前面的 WAF）会挑 UA；默认给一个可辨识的客户端标识 */
    private val userAgent: String = "KMusic/1.5.0 (Android)"
) {
    val base: String = normalize(baseUrl)
    val authHeader: String? =
        if (user.isNotEmpty() || pass.isNotEmpty()) Credentials.basic(user, pass) else null

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 占位文件 → 真直链 的缓存（URL → (直链, 过期毫秒)） */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** 列目录。dirUrl 为空则列根目录 */
    suspend fun list(dirUrl: String = base): Result<List<DavEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalize(dirUrl)
            val body = """<?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/><d:getcontentlength/><d:getcontenttype/>
                    <d:getlastmodified/><d:resourcetype/>
                  </d:prop>
                </d:propfind>""".trimIndent()

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .apply { authHeader?.let { header("Authorization", it) } }
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw java.io.IOException("鉴权失败（401），检查用户名/密码")
                if (resp.code == 404) throw java.io.IOException("路径不存在（404）")
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val xml = resp.body?.string().orEmpty()
                val entries = parseMultiStatus(xml, url)
                entries.sortedWith(compareByDescending<DavEntry> { it.isDir }.thenBy { it.name.lowercase() })
            }
        }
    }

    suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(base)
                .header("User-Agent", userAgent)
                .method("PROPFIND", "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/></d:prop></d:propfind>".toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")
                .apply { authHeader?.let { header("Authorization", it) } }
                .build()
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 401 -> throw java.io.IOException("鉴权失败（401）")
                    r.code == 404 -> throw java.io.IOException("路径不存在（404）")
                    !r.isSuccessful -> throw java.io.IOException("HTTP ${r.code}")
                    else -> "连接成功（HTTP ${r.code}）"
                }
            }
        }
    }

    /**
     * 把「占位 / STRM」条目解析成真正的直链。
     *
     * 典型链路：GET 占位文件 → 302 /api/strm302/redirect?pickcode=… → 302 115 CDN 签名地址。
     * 为什么要自己跟一遍：
     *   1. 播放器跟 302 时会把 Basic 凭据一起带到第三方 CDN，既没必要也不安全；
     *   2. 直连 CDN 少一次跳转，切歌快一拍。
     * 解析失败（HEAD 不支持等）就返回 null，调用方回退到原 URL —— 原链路本来就能播。
     */
    suspend fun resolveStream(url: String): String? = withContext(Dispatchers.IO) {
        cachedStream(url)?.let { return@withContext it }
        // 先 GET Range:0-0 再退回 HEAD：302 中转层对 HEAD 有时直接回 200 空体（不跟到 CDN），
        // GET 才会把整条 302 链走完 —— 这样拿到的才是最终的 CDN 直链。
        val r = runCatching { getFollow(url) }
            .recoverCatching { headFollow(url) }
            .getOrNull() ?: return@withContext null
        // 同主机且无重定向的普通 WebDAV 文件，没必要走缓存（URL 本身就是要播的地址）
        if (r != url) {
            cache[url] = r to expiryOf(r)
            val host = runCatching { r.toHttpUrlOrNull()?.host }.getOrNull().orEmpty()
            com.kmusic.apkhd.data.DiagLog.log("DAV_STRM", "占位直链已解析 → $host")
        }
        r
    }

    private fun headFollow(url: String): String {
        val req = Request.Builder().url(url).head()
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw java.io.IOException("HTTP ${resp.code}")
            resp.request.url.toString()
        }
    }

    private fun getFollow(url: String): String {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0")
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw java.io.IOException("HTTP ${resp.code}")
            resp.request.url.toString()
        }
    }

    /** 签名直链带 t=<unix 秒> 过期时间；提前 60s 失效，没有就按 5 分钟算 */
    private fun expiryOf(url: String): Long {
        val t = Regex("[?&]t=(\\d{9,})").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val now = System.currentTimeMillis()
        return if (t != null) (t * 1000L - 60_000L).coerceAtLeast(now + 30_000L) else now + 5 * 60_000L
    }

    private fun cachedStream(url: String): String? {
        val e = cache[url] ?: return null
        if (e.second <= System.currentTimeMillis()) { cache.remove(url); return null }
        return e.first
    }

    /**
     * 诊断：把「列目录 → 取流」整条链逐跳打出来。
     *
     * 手机上报「点播放没反应」时，后端日志可能一条都看不到 —— 到底是没发请求、
     * 401、还是 CDN 那一跳被拦，只能靠客户端自己把每跳的 code/host/耗时记下来。
     */
    suspend fun diagnose(startPath: String = base): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        line("UA: $userAgent")
        val noRedirect = client.newBuilder().followRedirects(false).build()

        // 1) 列目录
        val t0 = System.currentTimeMillis()
        var firstAudio: DavEntry? = null
        runCatching {
            val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>"""
            val req = Request.Builder().url(normalize(startPath)).header("User-Agent", userAgent)
                .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
                .header("Depth", "1")
                .apply { authHeader?.let { header("Authorization", it) } }
                .build()
            noRedirect.newCall(req).execute().use { r ->
                line("PROPFIND ${r.code}  ${System.currentTimeMillis() - t0}ms")
                val xml = r.body?.string().orEmpty()
                val entries = parseMultiStatus(xml, normalize(startPath))
                firstAudio = entries.firstOrNull { !it.isDir && it.isAudio }
                // 当前目录只有子目录时，往下走一层再找（否则根目录诊断永远“没有音频文件”）
                if (firstAudio == null) {
                    // 根目录通常只有分类文件夹（港剧/VIDEO/…），挨个看前 3 个，找到有音频的为止
                    for (sub in entries.filter { it.isDir }.take(3)) {
                        line("本层没有音频，进入子目录: ${sub.name}")
                        val req2 = Request.Builder().url(sub.url).header("User-Agent", userAgent)
                            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
                            .header("Depth", "1")
                            .apply { authHeader?.let { header("Authorization", it) } }
                            .build()
                        noRedirect.newCall(req2).execute().use { r2 ->
                            val xml2 = r2.body?.string().orEmpty()
                            firstAudio = parseMultiStatus(xml2, sub.url).firstOrNull { !it.isDir && it.isAudio }
                        }
                        if (firstAudio != null) break
                    }
                }
            }
        }.onFailure { line("PROPFIND 失败: ${it.javaClass.simpleName}: ${it.message}") }

        val target = firstAudio
        if (target == null) {
            line("目录里没有音频文件，诊断结束")
            return@withContext sb.toString()
        }
        line("测试文件: ${target.name}")

        // 2) 手动跟跳转，每跳记一行
        var url = target.url
        var headers = if (authHeader != null) mapOf("Authorization" to authHeader!!) else emptyMap()
        for (hop in 0..4) {
            val t = System.currentTimeMillis()
            val req = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-0")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val res = runCatching { noRedirect.newCall(req).execute() }.getOrElse {
                line("GET 失败: ${it.javaClass.simpleName}: ${it.message}")
                return@withContext sb.toString()
            }
            res.use { r ->
                val host = r.request.url.host
                val ct = r.header("Content-Type").orEmpty()
                line("跳$hop: ${r.code} $host  ${System.currentTimeMillis() - t}ms  ${if (ct.isNotEmpty()) ct else ""}")
                val loc = r.header("Location")
                if (loc == null || r.code !in 300..399) {
                    line("最终: ${r.request.url.host}  code=${r.code}")
                    return@withContext sb.toString()
                }
                url = r.request.url.resolve(loc)?.toString() ?: loc
                // 跨主机后不再带 WebDAV 凭据（和播放器行为一致）
                headers = if (url.startsWith(base)) headers else emptyMap()
            }
        }
        line("重定向超过 5 跳，放弃")
        sb.toString()
    }

    // ── 内部 ────────────────────────────────────────────────

    private fun parseMultiStatus(xml: String, requestUrl: String): List<DavEntry> {
        val out = mutableListOf<DavEntry>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var href = ""
        var isDir = false
        var size = 0L
        var mtime = ""
        var mime = ""
        var inResponse = false

        fun flush() {
            if (!inResponse || href.isBlank()) return
            val abs = resolve(href)
            // 跳过目录自身（有些服务器会把请求的目录也列出来）
            if (trimSlash(abs) != trimSlash(requestUrl)) {
                val name = displayName(href)
                if (name.isNotEmpty()) {
                    out += DavEntry(name, abs, isDir, size, mtime, mime)
                }
            }
            href = ""; isDir = false; size = 0L; mtime = ""; mime = ""
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':')?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "response" -> { inResponse = true; href = ""; isDir = false; size = 0; mtime = ""; mime = "" }
                    "href" -> href = parser.nextText().trim()
                    "collection" -> isDir = true
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> mtime = parser.nextText().trim()
                    "getcontenttype" -> mime = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (tag == "response") { flush(); inResponse = false }
            }
            event = parser.next()
        }
        return out
    }

    /** href → 绝对 URL；http 开头直接用，/ 开头补 origin，其余按 base 拼 */
    private fun resolve(href: String): String {
        val h = if (href.startsWith("http://") || href.startsWith("https://")) href else {
            val origin = base.substringBefore("/", "").let { scheme ->
                // base 形如 https://host/path/
                val idx = base.indexOf("://") + 3
                val slash = base.indexOf('/', idx)
                if (slash < 0) base else base.substring(0, slash)
            }
            if (href.startsWith("/")) origin + href else base + href
        }
        return h.replace(" ", "%20")
    }

    private fun displayName(href: String): String {
        val raw = trimSlash(href).substringAfterLast('/')
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    companion object {
        fun normalize(url: String): String {
            val t = url.trim()
            if (t.isEmpty()) return t
            return if (t.endsWith("/")) t else "$t/"
        }

        private fun trimSlash(s: String) = s.trimEnd('/')
    }
}
