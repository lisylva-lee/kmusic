package com.kmusic.apkhd.net

import android.util.Log
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 咪咕音乐客户端 —— 目前**唯一一个免签名、免登录的官方接口**。
 *
 * 两个域名分工：
 * - `app.u.nf.migu.cn`：搜索（v3）。⚠️ **不能**带 `channel` / `uid` 头，
 *   带了必返 `860002 系统繁忙`（实测），这是该接口自己的坑。
 * - `app.c.nf.migu.cn`：取链（MIGUM3.0 listen），需要 `channel` + `uid` 头。
 *
 * 音质上限：免登录只有 **PQ / 128k**。HQ、SQ 一律返回
 * `会员专属音质，请先登录`（cannotCode=440022），VIP 单曲只给 60 秒试听
 * （`auditionsLength=60`）。**这类响应一律判失败**，绝不把试听片段当正片播 ——
 * 上层会回退酷我，音质更好，也不会让用户听一半断掉。
 *
 * 接口无公开文档，随时可能变；设置页的「音源开关」就是为这个留的后路。
 */
class MiguClient {

    companion object {
        private const val TAG = "MiguClient"

        private const val API_U = "https://app.u.nf.migu.cn"
        private const val API_C = "https://app.c.nf.migu.cn"
        private const val CDN = "https://d.musicapp.migu.cn"

        /** 取链接口要求的渠道号，固定值即可 */
        private const val CHANNEL = "0146951"
        private const val UID = "1234"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun trackIdOf(copyrightId: String, contentId: String) = "migu:$copyrightId:$contentId"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---------------- 搜索 ----------------

    suspend fun search(keyword: String, count: Int = 15): List<Track> =
        withContext(Dispatchers.IO) {
            val url = "$API_U/pc/resource/song/item/search/v1.0" +
                "?text=${enc(keyword)}&pageNo=1&pageSize=$count"
            val body = get(url) ?: return@withContext emptyList()
            runCatching {
                json.decodeFromString<List<MiguSong>>(body)
                    .mapNotNull { it.toTrack() }
            }.onFailure { Log.w(TAG, "search parse: ${it.message}") }
                .getOrDefault(emptyList())
        }

    // ---------------- 取链 ----------------

    /**
     * 解析播放直链。失败（VIP / 无版权 / 接口变更）返回 null，由上层回退。
     */
    suspend fun resolve(t: Track): Track? = withContext(Dispatchers.IO) {
        val ids = parseId(t.sourceId) ?: return@withContext null
        val url = "$API_C/MIGUM3.0/strategy/pc/listen/v1.0" +
            "?scene=&netType=01&resourceType=2" +
            "&copyrightId=${ids.first}&contentId=${ids.second}&toneFlag=PQ"
        val body = get(url, mapOf("channel" to CHANNEL, "uid" to UID))
            ?: return@withContext null
        val resp = runCatching { json.decodeFromString<MiguListenResp>(body) }.getOrNull()
            ?: return@withContext null
        val play = resp.data?.url
        if (play.isNullOrBlank()) {
            // 典型：cannotCode=440022（会员音质）/ auditionsLength=60（VIP 试听）
            Log.i(
                TAG,
                "no url: code=${resp.code} cannot=${resp.data?.cannotCode} " +
                    "audition=${resp.data?.auditionsLength} '${t.name}'"
            )
            return@withContext null
        }
        t.copy(playUrl = play, qualityLabel = "128k")
    }

    // ---------------- 歌词 ----------------

    /** 抓搜索响应里自带的 lrcUrl（纯文本 LRC） */
    suspend fun lyric(url: String): String? = withContext(Dispatchers.IO) {
        val u = absolute(url) ?: return@withContext null
        get(u)?.takeIf { it.isNotBlank() }
    }

    // ---------------- 内部 ----------------

    private fun parseId(sourceId: String): Pair<String, String>? {
        val parts = sourceId.split(':')
        if (parts.size != 3 || parts[0] != "migu") return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null
        return parts[1] to parts[2]
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** 接口里封面常是 `/data/oss/...` 相对路径，需要补 CDN 主机 */
    private fun absolute(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$CDN$u"
        else -> u
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
                null
            } else {
                resp.body?.string()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun MiguSong.toTrack(): Track? {
        val cid = copyrightId.orEmpty()
        val content = contentId.orEmpty()
        if (cid.isBlank() || content.isBlank()) return null
        val cover = absolute(img1) ?: absolute(img2) ?: absolute(img3)
        return Track(
            id = trackIdOf(cid, content),
            name = songName.orEmpty().ifBlank { "未知歌曲" },
            artist = singerList.orEmpty()
                .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .joinToString("/")
                .ifBlank { "未知歌手" },
            album = album.orEmpty(),
            coverUrl = cover,
            durationMs = (duration ?: 0).toLong() * 1000L,
            source = "migu",
            sourceId = trackIdOf(cid, content),
            qualityLabel = "128k",
            lyricUrl = absolute(lrcUrl)
        )
    }
}

@Serializable
private data class MiguSong(
    val songName: String? = null,
    val copyrightId: String? = null,
    val contentId: String? = null,
    val album: String? = null,
    val duration: Int? = null,
    val singerList: List<MiguSinger>? = null,
    val img1: String? = null,
    val img2: String? = null,
    val img3: String? = null,
    val lrcUrl: String? = null
)

@Serializable
private data class MiguSinger(val name: String? = null)

@Serializable
private data class MiguListenResp(
    val code: String? = null,
    val data: MiguListenData? = null
)

@Serializable
private data class MiguListenData(
    val url: String? = null,
    val playUrl: String? = null,
    val cannotCode: String? = null,
    val auditionsLength: Int? = null
)
