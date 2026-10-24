package com.kmusic.apkhd.net

import android.util.Log
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * gdstudio 聚合 API 客户端（`music-api.gdstudio.xyz`）—— 洛雪音源生态在用的公开接口。
 *
 * **它把网易云的播放链路解决了**：实测能返回真实可播的直链
 * （FLAC 24bit/48kHz，下载后 `file` 判定为合法 FLAC bitstream），
 * 而网易云官方 /api/song/url 不登录只给 128k 或 null。
 *
 * 支持的 source（实测穷举）：仅 `netease` 与 `joox`。
 * 酷我/QQ/酷狗/咪咕都已下线，那些要么自建解析服务器 + VIP cookie，要么无解。
 *
 * 可靠性注意：该接口是**第三方公开服务**，会偶发 503。所有调用都做了
 * runCatching 兜底，失败即返回 null，由上层回退到酷我。
 */
class GdStudioClient {

    companion object {
        private const val TAG = "GdStudioClient"
        private const val API = "https://music-api.gdstudio.xyz/api.php"

        /** 音质档位：999=flac24bit / 740=flac / 320 / 128 */
        const val BR_FLAC24 = 999
        const val BR_FLAC = 740
        const val BR_320 = 320
        const val BR_128 = 128
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(keyword: String, count: Int = 10): List<Track> = withContext(Dispatchers.IO) {
        val url = "$API?types=search&source=netease&name=${enc(keyword)}&count=$count"
        val body = get(url) ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<GdTrack>>(body).map { it.toTrack() }
        }.onFailure { Log.w(TAG, "search parse: ${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * 取歌单全部歌曲（网易云歌单 id）。
     *
     * ⚠️ 为什么走 gdstudio 而不是 music.163.com：实测 `/api/playlist/detail` 与
     * `/api/v6/playlist/detail`（GET 和 POST 都试过）都硬锁 `tracks` 到 10 首，
     * 即使 `trackIds` 是完整的；`s` 偏移参数完全无效（试过 0/1/2/3/4/5/10/20/50/
     * 100/200/300，永远是同样 10 首）；`song/detail` 批量取元数据返回 code=400
     * （要 weapi 加密）。而 gdstudio 代理这个接口返回**完整** tracks。
     *
     * 实测：367 首的歌单拿到 367 首，带歌名/歌手/时长/封面，抽样 11/12 可播。
     *
     * 注意返回的是**网易云原生结构**（ar 数组 / al 对象 / dt 毫秒），
     * 不是 search 接口那种扁平结构，所以用单独的模型。
     */
    /** 网易云图片 http→https，避免明文流量被拦 */
    private fun https(u: String?): String? =
        u?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")

    suspend fun playlistTracks(id: String): List<Track> = withContext(Dispatchers.IO) {
        val body = get("$API?types=playlist&source=netease&id=$id") ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<GdPlaylistResp>(body)
                .let { it.playlist ?: it.result }
                ?.tracks.orEmpty()
                .map { t ->
                    Track(
                        id = "ne:${t.id}",
                        name = t.name,
                        artist = t.ar.orEmpty().joinToString("/") { it.name.orEmpty() },
                        album = t.al?.name.orEmpty(),
                        coverUrl = https(t.al?.picUrl),
                        durationMs = t.dt,
                        source = "netease",
                        sourceId = t.id.toString()
                    )
                }
        }.onFailure { Log.w(TAG, "playlistTracks parse: ${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * 取播放直链，按音质从高到低自动降级。
     * 返回 null 表示该曲目在 gdstudio 拿不到链接（上层应回退酷我）。
     */
    suspend fun url(id: String, brs: List<Int> = listOf(BR_FLAC24, BR_FLAC, BR_320, BR_128)): String? =
        withContext(Dispatchers.IO) {
            for (br in brs) {
                val body = get("$API?types=url&source=netease&id=$id&br=$br")
                if (body != null) {
                    val u = runCatching { json.decodeFromString<GdUrl>(body).url }.getOrNull()
                    if (!u.isNullOrEmpty()) return@withContext u
                }
            }
            null
        }

    /** 封面（传入网易云的 pic_id） */
    suspend fun pic(picId: String, size: Int = 500): String? = withContext(Dispatchers.IO) {
        val body = get("$API?types=pic&source=netease&id=$picId&size=$size") ?: return@withContext null
        runCatching { json.decodeFromString<GdUrl>(body).url }.getOrNull()?.ifEmpty { null }
    }

    /** 歌词（LRC 文本），网易云歌词覆盖比 lrclib 好 */
    suspend fun lyric(songId: String): String? = withContext(Dispatchers.IO) {
        val body = get("$API?types=lyric&source=netease&id=$songId") ?: return@withContext null
        runCatching { json.decodeFromString<GdLyric>(body).lyric }.getOrNull()?.ifEmpty { null }
    }

    /**
     * 按「歌名 + 歌手」搜索并取词。
     *
     * 为什么需要它：酷我源的歌**没有网易云 id**，直接拿 sourceId 调 [lyric] 无从下手。
     * 而这类歌在网易云往往有（实测 9 首里 8 首能严格匹配到，含 Live 版）。
     *
     * ⚠️ 校验必须严格，用的是和 lrclib 那条路同一套 [LyricQuery.verify] ——
     * 否则会把「同名但别人的版本」的词配上来，那比「暂无歌词」更糟。
     *
     * @return 命中的歌词文本；没有严格匹配的结果就返回 null
     */
    suspend fun lyricByName(
        title: String,
        artist: String,
        wantSec: Long = 0
    ): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val body = runCatching {
            get("$API?types=search&source=netease&name=${enc(title)}&count=10&pages=1")
        }.getOrNull() ?: return@withContext null

        val list = runCatching {
            json.decodeFromString<List<GdTrack>>(body)
        }.getOrNull() ?: return@withContext null

        // 严格匹配的候选，按时长接近度挑最像的
        var bestText: String? = null
        var bestDist = Long.MAX_VALUE
        for (x in list) {
            val ar = x.artist.orEmpty().joinToString("/")
            if (!LyricQuery.verify(x.name, ar, title, artist)) continue
            val id = (x.urlId ?: x.id).ifEmpty { null } ?: continue
            val text = runCatching { lyric(id) }.getOrNull()
            if (text.isNullOrEmpty()) continue
            val dist = LyricQuery.durationDistance(wantSec, 0)   // gdstudio 不返回时长
            if (dist < bestDist) {
                bestDist = dist
                bestText = text
            }
        }
        bestText
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
                null
            } else {
                resp.body?.string()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}")
        null
    }
}

@Serializable
data class GdTrack(
    val id: String = "",
    // picId 保留：补封面时要用它调 types=pic
    val name: String = "",
    /** 该接口返回的是数组，不是字符串 */
    val artist: List<String>? = null,
    val album: String? = null,
    @SerialName("pic_id") val picId: String? = null,
    @SerialName("url_id") val urlId: String? = null,
    @SerialName("lyric_id") val lyricId: String? = null,
    val source: String? = null
)

@Serializable
data class GdUrl(val url: String? = null, val br: Int = 0, val size: Long = 0)

@Serializable
data class GdLyric(val lyric: String? = null)

// ---------------- 歌单详情（网易云原生结构，非扁平） ----------------

@Serializable
data class GdPlaylistResp(
    val playlist: GdPlaylistNode? = null,
    val result: GdPlaylistNode? = null
)

@Serializable
data class GdPlaylistNode(
    val id: Long = 0,
    val name: String? = null,
    val trackCount: Int = 0,
    val coverImgUrl: String? = null,
    val description: String? = null,
    val tracks: List<GdPlTrack>? = null
)

@Serializable
data class GdPlTrack(
    val id: Long = 0,
    val name: String = "",
    val ar: List<GdPlArtist>? = null,
    val al: GdPlAlbum? = null,
    /** 时长，毫秒 */
    val dt: Long = 0
)

@Serializable
data class GdPlArtist(val id: Long = 0, val name: String? = null)

@Serializable
data class GdPlAlbum(val id: Long = 0, val name: String? = null, val picUrl: String? = null)

/** 把 gdstudio 的搜索结果转成统一 Track（source = netease） */
fun GdTrack.toTrack(): Track = Track(
    id = "gd:${id}",
    name = name,
    artist = artist.orEmpty().joinToString("/"),
    album = album.orEmpty(),
    // 封面**不能**由 pic_id 直接拼：实测 pN.music.126.net/<id>.jpg 一律 404，
    // 真实 URL 带一段不可推导的 hash，只能再调 types=pic 取。
    // 这里留空，由上层并发补封面（见 enrichNeteaseCovers）。
    coverUrl = null,
    source = "netease",
    // url_id 才是取链用的 id，个别条目和 id 不同
    sourceId = (urlId ?: id).ifEmpty { id }
)
