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
 * 网易云客户端 —— **只取推荐位元数据，不做播放**。
 *
 * 播放侧的成本（登录 + 自有加密链路复刻）远大于收益，
 * 所以这里拿到的歌曲一律标 `source = "netease"`，
 * 播放时由 [TrackMatcher] 严格三重校验后回退到酷我。
 */
class NeteaseClient {

    companion object {
        private const val TAG = "NeteaseClient"
        private const val BASE = "https://music.163.com/api"
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
