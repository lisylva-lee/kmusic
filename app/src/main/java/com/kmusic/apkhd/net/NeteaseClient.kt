package com.kmusic.apkhd.net

import android.util.Log
import com.kmusic.apkhd.model.ArtistBrief
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 网易云客户端。
 *
 * v2.3.7 起不再只是「取推荐位元数据」—— 因为 gdstudio 的 url/search/pic/lyric
 * 四个接口已经 401（只有 playlist 可用），这几项改由本类直连 music.163.com。
 *
 * 直连的代价与边界，实测记在这里：
 * - `song/enhance/player/url` 对 VIP/付费曲返回 `code=-110`（`fee=1`），拿不到链。
 *   这是版权限制，不是 bug；这类歌要靠换源（酷我/咪咕），gdstudio 也帮不上。
 * - 无需登录、无需 weapi 加密、无需 Referer。
 * - `song/detail` 的 `ids` **必须是 JSON 数组**（`ids=[1,2,3]`）；
 *   写成逗号分隔 `ids=1,2,3` 会返回 400。这个坑踩过。
 */
class NeteaseClient {

    companion object {
        private const val TAG = "NeteaseClient"
        private const val BASE = "https://music.163.com/api"

        /**
         * `song/detail` 单次批量上限。
         *
         * 实测：传 100/200 个 id 都能回满；传 300/500 只回 **201** 条
         * （服务端有硬上限，不是网络问题）。所以卡 200 最稳。
         */
        private const val SONG_DETAIL_BATCH = 200
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 推荐歌单。
     *
     * ⚠️ 这个接口**每次请求都会返回不同内容**（实测连调 4 次，首个歌单全不一样），
     * 所以「刷新换一批」直接重调即可，不需要 offset。
     */
    suspend fun recommendPlaylists(limit: Int = 12): List<PlaylistBrief> = withContext(Dispatchers.IO) {
        val body = get("$BASE/personalized/playlist?limit=$limit") ?: return@withContext emptyList()
        runCatching {
            NeteaseJson.instance.decodeFromString<NeRecommendResponse>(body).result.orEmpty()
                .map {
                    PlaylistBrief(
                        id = it.id.toString(),
                        name = it.name.orEmpty(),
                        coverUrl = https(it.picUrl),
                        trackCount = it.trackCount,
                        playCount = it.playCount.toLong(),
                        source = "netease",
                        description = it.copywriter.orEmpty()
                    )
                }
        }.onFailure { Log.w(TAG, "recommendPlaylists: ${it.message}") }.getOrDefault(emptyList())
    }

    /** 推荐新歌 */
    suspend fun newSongs(limit: Int = 12): List<Track> = withContext(Dispatchers.IO) {
        val body = get("$BASE/personalized/newsong?limit=$limit") ?: return@withContext emptyList()
        runCatching {
            NeteaseJson.instance.decodeFromString<NeNewsongResponse>(body).result.orEmpty().mapNotNull { item ->
                val s = item.song ?: return@mapNotNull null
                Track(
                    id = "ne:${s.id}",
                    name = s.name ?: item.name.orEmpty(),
                    artist = s.artists.orEmpty().joinToString("/") { it.name.orEmpty() },
                    album = s.album?.name.orEmpty(),
                    coverUrl = https(item.picUrl ?: s.album?.picUrl),
                    durationMs = s.duration,
                    source = "netease",
                    sourceId = s.id.toString()
                )
            }
        }.onFailure { Log.w(TAG, "newSongs: ${it.message}") }.getOrDefault(emptyList())
    }

    /** 热门歌手 */
    /**
     * 音乐人列表。
     *
     * ⚠️ 和推荐歌单不同，这个接口**重复请求返回完全相同**（实测连调 4 次零变化），
     * 所以「刷新换一批」必须靠 offset 翻页。上层一次多拿一些，本地切片翻批。
     */
    suspend fun topArtists(limit: Int = 12, offset: Int = 0): List<ArtistBrief> = withContext(Dispatchers.IO) {
        val body = get("$BASE/artist/list?limit=$limit&offset=$offset") ?: return@withContext emptyList()
        runCatching {
            NeteaseJson.instance.decodeFromString<NeArtistListResponse>(body).artists.orEmpty()
                .map {
                    ArtistBrief(
                        id = it.id.toString(),
                        name = it.name.orEmpty(),
                        picUrl = https(it.picUrl),
                        songCount = it.musicSize,
                        albumCount = it.albumSize,
                        source = "netease"
                    )
                }
        }.onFailure { Log.w(TAG, "topArtists: ${it.message}") }.getOrDefault(emptyList())
    }

    /** 歌单内的歌曲（旧字段名：artists / album / duration） */
    suspend fun playlistTracks(pid: String): List<Track> = withContext(Dispatchers.IO) {
        val body = get("$BASE/playlist/detail?id=$pid") ?: return@withContext emptyList()
        runCatching {
            val detail = NeteaseJson.instance.decodeFromString<NePlaylistDetailResponse>(body).result
                ?: return@withContext emptyList()
            detail.tracks.orEmpty().map { s ->
                Track(
                    id = "ne:${s.id}",
                    name = s.name.orEmpty(),
                    artist = s.artists.orEmpty().joinToString("/") { it.name.orEmpty() },
                    album = s.album?.name.orEmpty(),
                    coverUrl = https(s.album?.picUrl),
                    durationMs = s.duration,
                    source = "netease",
                    sourceId = s.id.toString()
                )
            }
        }.onFailure { Log.w(TAG, "playlistTracks: ${it.message}") }.getOrDefault(emptyList())
    }

    /** 歌单详情里的封面（列表接口不返回，点进去才需要） */
    suspend fun playlistCover(pid: String): String? = withContext(Dispatchers.IO) {
        val body = get("$BASE/playlist/detail?id=$pid") ?: return@withContext null
        runCatching {
            https(NeteaseJson.instance.decodeFromString<NePlaylistDetailResponse>(body).result?.coverImgUrl)
        }.getOrNull()
    }

    // ---------------- v2.3.7 直连补充能力 ----------------

    /**
     * 完整歌单：`v6/playlist/detail` 拿 trackIds → `song/detail` 分批补元数据。
     *
     * 为什么不能只读 `v6.playlist.tracks`：实测 1268 首的歌单，`tracks` 字段
     * 只给 6 首（有的给 200），但 `trackIds` 是完整的。只读 tracks 就是
     * 「歌单显示 1268 首、点进去只有 6 首」。
     *
     * [SONG_DETAIL_BATCH] 是实测上限：一次传 300/500 个 id 只回 201 条，
     * 200 能稳定回满。
     *
     * @return 按歌单原顺序排列的曲目；取不到返回空列表（由上层决定怎么兜底）
     */
    suspend fun fullPlaylistTracks(pid: String): List<Track> = withContext(Dispatchers.IO) {
        val body = get("$BASE/v6/playlist/detail?id=$pid&n=1000") ?: return@withContext emptyList()
        val detail = runCatching {
            NeteaseJson.instance.decodeFromString<NeV6PlaylistResponse>(body).playlist
        }.onFailure { Log.w(TAG, "v6 playlist parse: ${it.message}") }.getOrNull()
            ?: return@withContext emptyList()

        val ids = detail.trackIds.orEmpty().map { it.id }.filter { it > 0 }
        if (ids.isEmpty()) {
            // 极少数歌单 v6 不给 trackIds（比如已下架），退回它自带的 tracks
            return@withContext detail.tracks.orEmpty().map { it.toTrack() }
        }

        val out = ArrayList<Track>(ids.size)
        for (chunk in ids.chunked(SONG_DETAIL_BATCH)) {
            val arr = chunk.joinToString(",", prefix = "[", postfix = "]")
            val sb = get("$BASE/song/detail?ids=$arr")
            val songs = sb?.let {
                runCatching { NeteaseJson.instance.decodeFromString<NeSongDetailResponse>(it).songs }
                    .onFailure { e -> Log.w(TAG, "song/detail parse: ${e.message}") }
                    .getOrNull()
            }.orEmpty()
            if (songs.isEmpty()) {
                // 某一批失败就放弃整条链路 —— 半截歌单比空歌单更糟（顺序会错乱）
                Log.w(TAG, "song/detail 批次失败，放弃直连（已取 ${out.size}/${ids.size}）")
                return@withContext emptyList()
            }
            out += songs.map { it.toTrack() }
        }
        // song/detail 不保证回传顺序与请求一致，按 ids 重排
        val byId = out.associateBy { it.sourceId }
        ids.mapNotNull { byId[it.toString()] }
    }

    /**
     * 播放直链。
     *
     * @return 可播 URL；无版权（404）/ 需 VIP（-110）时为 null，由上层换源。
     */
    suspend fun playUrl(songId: String, br: Int = 320_000): String? =
        withContext(Dispatchers.IO) {
            val body = get("$BASE/song/enhance/player/url?ids=[$songId]&br=$br")
                ?: return@withContext null
            runCatching {
                NeteaseJson.instance.decodeFromString<NePlayUrlResponse>(body).data?.firstOrNull()
            }.onFailure { Log.w(TAG, "playUrl parse: ${it.message}") }.getOrNull()
                ?.url?.takeIf { it.isNotBlank() }
        }

    /** 歌词（LRC）。含翻译时优先返回原文，调用方需要翻译可另取 [lyricWithTranslation]。 */
    suspend fun lyric(songId: String): String? = withContext(Dispatchers.IO) {
        val body = get("$BASE/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1")
            ?: return@withContext null
        runCatching {
            NeteaseJson.instance.decodeFromString<NeLyricResponse>(body).lrc?.lyric
        }.onFailure { Log.w(TAG, "lyric parse: ${it.message}") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 搜索（按歌名/歌手）。type=1 是单曲。 */
    suspend fun search(keyword: String, limit: Int = 10): List<Track> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            val body = get("$BASE/search/get/web?s=${enc(keyword)}&type=1&limit=$limit&offset=0")
                ?: return@withContext emptyList()
            runCatching {
                NeteaseJson.instance.decodeFromString<NeSearchResponse>(body).result?.songs.orEmpty()
                    .map { it.toTrack() }
            }.onFailure { Log.w(TAG, "search parse: ${it.message}") }.getOrDefault(emptyList())
        }

    /** 统一把网易云 song 结构转成 app 的 [Track] */
    private fun NeSong.toTrack() = Track(
        id = "ne:$id",
        name = name.orEmpty(),
        artist = artists.orEmpty().joinToString("/") { it.name.orEmpty() },
        album = album?.name.orEmpty(),
        coverUrl = https(album?.picUrl),
        durationMs = duration,
        source = "netease",
        sourceId = id.toString()
    )

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** 网易云图片 http→https，避免明文流量被拦 */
    private fun https(u: String?): String? =
        u?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }
    }
}
