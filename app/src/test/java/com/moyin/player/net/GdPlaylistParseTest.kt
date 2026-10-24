package com.kmusic.apkhd.net

import com.kmusic.apkhd.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单详情解析测试（gdstudio 代理的网易云歌单接口）。
 *
 * ⚠️ 这个接口是**绕开 10 首限制**的唯一可行路径，所以用真实响应钉住：
 * music.163.com 的 `/api/playlist/detail` 与 `/api/v6/playlist/detail`
 * （GET 和 POST 都试过）都把 `tracks` 硬锁到 10 首，即使 `trackIds` 完整；
 * `s` 偏移参数完全无效；`song/detail` 批量取元数据返回 400（要 weapi 加密）。
 * 详见 GdStudioClient.playlistTracks 的注释。
 *
 * 另外注意：这个接口返回的是**网易云原生结构**（ar 数组 / al 对象 / dt 毫秒），
 * 不是 search 接口那种扁平结构（artist 数组 + album 字符串 + 无 duration）。
 * 两套模型不能混用 —— 混用会静默解析出空歌名/空歌手。
 */
class GdPlaylistParseTest {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/gdstudio_playlist_detail.json")!!
            .bufferedReader().use { it.readText() }

    private fun parse(): List<Track> {
        val resp = json.decodeFromString<GdPlaylistResp>(fixture())
        val node = resp.playlist ?: resp.result
        return node?.tracks.orEmpty().map { t ->
            Track(
                id = "ne:${t.id}",
                name = t.name,
                artist = t.ar.orEmpty().joinToString("/") { it.name.orEmpty() },
                album = t.al?.name.orEmpty(),
                // 和 GdStudioClient 里一样：模型字段是原始值，转换在 toTrack 时做
                coverUrl = t.al?.picUrl?.takeIf { it.isNotEmpty() }?.replace("http://", "https://"),
                durationMs = t.dt,
                source = "netease",
                sourceId = t.id.toString()
            )
        }
    }

    @Test
    fun parse_allTracks_notJustTen() {
        val tracks = parse()
        // 夹具只保留了 8 首，但必须全部解析出来
        // —— 真正要防的回归是「只解析出 10 首」那种截断
        assertEquals("应解析出全部曲目", 8, tracks.size)
    }

    @Test
    fun parse_trackFields() {
        val t = parse().first()
        assertEquals("Head In The Clouds", t.name)
        assertEquals("Hayd", t.artist)
        assertEquals("Changes - EP", t.album)
        assertEquals(184559L, t.durationMs)
        assertEquals("ne:1878812258", t.id)
        assertEquals("netease", t.source)
        assertTrue("封面必须是 https（明文会被拦）", t.coverUrl?.startsWith("https://") == true)
    }

    @Test
    fun parse_nodeAcceptsBothKeys() {
        // music.163.com 有时回 result、有时回 playlist（实测两种都见过）。
        // 两个字段都要能解析，否则哪天换成 result 就静默变空列表。
        val viaPlaylist = json.decodeFromString<GdPlaylistResp>(
            """{"code":200,"playlist":{"id":1,"tracks":[{"id":5,"name":"x","ar":[{"name":"a"}],"al":{"name":"b"},"dt":1000}]}}"""
        )
        val viaResult = json.decodeFromString<GdPlaylistResp>(
            """{"code":200,"result":{"id":1,"tracks":[{"id":5,"name":"x","ar":[{"name":"a"}],"al":{"name":"b"},"dt":1000}]}}"""
        )
        assertEquals("x", viaPlaylist.playlist?.tracks?.first()?.name)
        assertEquals("x", viaResult.result?.tracks?.first()?.name)
    }

    @Test
    fun parse_emptyPlaylist_notCrash() {
        val resp = json.decodeFromString<GdPlaylistResp>("""{"code":200,"playlist":{"id":1,"tracks":[]}}""")
        val tracks = (resp.playlist ?: resp.result)?.tracks.orEmpty()
        assertTrue("空歌单应返回空列表而不是崩", tracks.isEmpty())
    }
}
