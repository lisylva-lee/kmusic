package com.kmusic.apkhd.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实接口抓下来的响应**做解析测试。
 *
 * 夹具位于 `src/test/resources/fixtures/`，是各接口的真实返回。
 * 这样可以在不联网的前提下验证模型字段名/类型是否和线上一致 ——
 * 酷我/网易云的字段名经常改，字段对不上会静默解析成 null（列表空白但没报错）。
 */
class ApiParsingTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    // ---------------- 酷我 ----------------

    @Test
    fun parse_kuwoChart_realResponse() {
        val resp = KuwoJson.instance.decodeFromString<KuwoChartResponse>(fixture("kuwo_chart.json"))
        assertEquals("酷我热歌榜", resp.name)
        val songs = resp.musiclist.orEmpty()
        assertTrue("榜单应至少解析出 1 首", songs.isNotEmpty())
        val first = songs.first()
        assertNotNull("榜单条目必须能解析出 id（否则无法播放）", first.id)
        assertNotNull("榜单条目必须能解析出歌名", first.name)
        assertNotNull("榜单条目必须能解析出歌手", first.artist)
    }

    @Test
    fun parse_kuwoPlaylistDetail_realResponse() {
        val d = KuwoJson.instance.decodeFromString<KuwoPlaylistDetail>(fixture("kuwo_playlist.json"))
        assertTrue("歌单要有标题", !d.title.isNullOrEmpty())
        assertTrue("歌单总数应 > 0", d.total > 0)
        val songs = d.musiclist.orEmpty()
        assertTrue("歌单歌曲应非空", songs.isNotEmpty())
        assertNotNull("歌单条目必须有 id", songs.first().id)
        assertNotNull("歌单条目必须有歌名", songs.first().name)
    }

    @Test
    fun parse_kuwoPlaylistDetail_durationIsTrustworthy() {
        // 歌单详情的 duration 实测与搜索一致（可信），这是它和榜单的关键差别
        val d = KuwoJson.instance.decodeFromString<KuwoPlaylistDetail>(fixture("kuwo_playlist.json"))
        val top = d.musiclist!!.first()
        val sec = top.duration!!.toLong()
        assertTrue("歌单首条时长应在合理区间(60~600s)，实际 $sec", sec in 60..600)
    }

    @Test
    fun parse_kuwoArtistSearch_realResponse() {
        val resp = KuwoJson.instance.decodeFromString<KuwoArtistSearchResponse>(
            KuwoClient().normalizeJson(fixture("kuwo_artist_search.json"))
        )
        val artists = resp.abslist.orEmpty()
        assertTrue("歌手搜索应有结果", artists.isNotEmpty())
        val a = artists.first()
        assertNotNull("歌手必须有 ARTISTID", a.artistId)
        assertNotNull("歌手必须有名字", a.artist)
        assertNotNull("歌手应有头像路径", a.picPath)
    }

    @Test
    fun parse_kuwoPlaylistSearch_realResponse() {
        val resp = KuwoJson.instance.decodeFromString<KuwoPlaylistSearchResponse>(
            KuwoClient().normalizeJson(fixture("kuwo_playlist_search.json"))
        )
        val lists = resp.abslist.orEmpty()
        assertTrue("歌单搜索应有结果", lists.isNotEmpty())
        val p = lists.first()
        assertNotNull("歌单必须有 id", p.targetId)
        assertNotNull("歌单必须有名字", p.name)
    }

    @Test
    fun parse_kuwoSongSearch_hasDurationForMatching() {
        // 跨源回退依赖搜索结果的时长做三重校验，这个字段必须能解析出来
        val resp = KuwoJson.instance.decodeFromString<KuwoSearchResponse>(
            KuwoClient().normalizeJson(fixture("kuwo_song_search.json"))
        )
        val songs = resp.abslist.orEmpty()
        assertTrue("搜索应有结果", songs.isNotEmpty())
        val withDur = songs.filter { (it.durationSec?.toLongOrNull() ?: 0L) > 0 }
        assertTrue("搜索结果必须带 DURATION，否则跨源匹配无法做时长校验", withDur.isNotEmpty())
    }

    // ---------------- 封面 URL 修正 ----------------

    @Test
    fun coverUrl_stripsEmbeddedSizePrefix() {
        val c = KuwoClient()
        // 值自带 120/ 前缀，早先直接拼 500 会变成 500120/（实测返回 2048px 大图）
        val url = c.coverUrl("120/s4s81/95/2497366108.jpg")
        assertEquals("https://img1.kuwo.cn/star/albumcover/500/s4s81/95/2497366108.jpg", url)
    }

    @Test
    fun coverUrl_handlesMissingPrefix() {
        val c = KuwoClient()
        assertEquals(
            "https://img1.kuwo.cn/star/albumcover/500/abc/1.jpg",
            c.coverUrl("abc/1.jpg")
        )
    }

    @Test
    fun coverUrl_blankReturnsNull() {
        val c = KuwoClient()
        assertEquals(null, c.coverUrl(""))
        assertEquals(null, c.coverUrl(null))
    }

    // ---------------- 网易云 ----------------

    @Test
    fun parse_neteaseRecommendPlaylists_realResponse() {
        val resp = NeteaseJson.instance.decodeFromString<NeRecommendResponse>(
            fixture("netease_recommend.json")
        )
        assertEquals(200, resp.code)
        val lists = resp.result.orEmpty()
        assertTrue("推荐歌单应非空", lists.isNotEmpty())
        val p = lists.first()
        assertTrue("歌单要有 id", p.id > 0)
        assertTrue("歌单要有名字", !p.name.isNullOrEmpty())
        assertTrue("歌单要有封面（UI 靠它撑门面）", !p.picUrl.isNullOrEmpty())
    }

    @Test
    fun parse_neteaseArtists_realResponse() {
        val resp = NeteaseJson.instance.decodeFromString<NeArtistListResponse>(
            fixture("netease_artists.json")
        )
        val artists = resp.artists.orEmpty()
        assertTrue("歌手列表应非空", artists.isNotEmpty())
        val a = artists.first()
        assertTrue("歌手要有 id", a.id > 0)
        assertTrue("歌手要有名字", !a.name.isNullOrEmpty())
        assertTrue("歌手要有头像", !a.picUrl.isNullOrEmpty())
    }

    @Test
    fun parse_neteaseNewsong_realResponse() {
        val resp = NeteaseJson.instance.decodeFromString<NeNewsongResponse>(
            fixture("netease_newsong.json")
        )
        val items = resp.result.orEmpty()
        assertTrue("新歌推荐应非空", items.isNotEmpty())
        val s = items.first().song
        assertNotNull("嵌套 song 对象必须能解析", s)
        assertTrue("歌曲必须有毫秒时长（跨源匹配要用）", (s?.duration ?: 0L) > 0)
        assertTrue("歌曲必须有歌手", !s?.artists.isNullOrEmpty())
    }

    @Test
    fun parse_neteasePlaylistDetail_usesLegacyFieldNames() {
        // 这个接口用的是 artists/album/duration（非 ar/al/dt），解析错了就是空白列表
        val resp = NeteaseJson.instance.decodeFromString<NePlaylistDetailResponse>(
            fixture("netease_playlist_detail.json")
        )
        val d = resp.result
        assertNotNull("歌单详情必须能解析", d)
        assertTrue("歌单要有名字", !d?.name.isNullOrEmpty())
        assertTrue("歌单要有封面", !d?.coverImgUrl.isNullOrEmpty())
        val tracks = d?.tracks.orEmpty()
        assertTrue("歌单歌曲应非空", tracks.isNotEmpty())
        val t = tracks.first()
        assertTrue("歌曲要有毫秒时长", t.duration > 0)
        assertTrue("歌曲要有歌手（artists 字段）", !t.artists.isNullOrEmpty())
        assertTrue("歌曲要有专辑封面（album.picUrl 字段）", !t.album?.picUrl.isNullOrEmpty())
    }

    // ---------------- gdstudio（网易云播放链路） ----------------

    private val gdJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true
    }

    @Test
    fun parse_gdstudioSearch_realResponse() {
        val list = gdJson.decodeFromString<List<GdTrack>>(fixture("gdstudio_search.json"))
        assertTrue("搜索结果应非空", list.isNotEmpty())
        val t = list.first()
        assertTrue("必须有 id", t.id.isNotEmpty())
        assertTrue("必须有歌名", t.name.isNotEmpty())
        // artist 是数组不是字符串，解析错会得到空数组
        assertTrue("artist 必须解析成非空数组", !t.artist.isNullOrEmpty())
        assertTrue("必须有 url_id（取链要用）", !t.urlId.isNullOrEmpty())
    }

    @Test
    fun gdTrack_toTrack_doesNotFabricateCover() {
        val list = gdJson.decodeFromString<List<GdTrack>>(fixture("gdstudio_search.json"))
        val track = list.first().toTrack()
        // pic_id 无法直接拼成可用封面（pN.music.126.net/<id>.jpg 一律 404），
        // 必须留空由上层补，绝不能拼一个必然 404 的 URL
        assertEquals(null, track.coverUrl)
        assertEquals("netease", track.source)
        assertTrue("sourceId 必须用 url_id", track.sourceId == list.first().urlId)
    }

    @Test
    fun parse_gdstudioUrl_realResponse() {
        val u = gdJson.decodeFromString<GdUrl>(fixture("gdstudio_url.json"))
        assertTrue("必须返回直链", !u.url.isNullOrEmpty())
        assertTrue("直链应是音频 CDN", u.url!!.contains("music.126.net"))
        assertTrue("应带码率", u.br > 0)
    }

    @Test
    fun parse_gdstudioPic_realResponse() {
        val u = gdJson.decodeFromString<GdUrl>(fixture("gdstudio_pic.json"))
        assertTrue("必须返回封面 URL", !u.url.isNullOrEmpty())
        // 真实封面 URL 带 hash 段，形如 p2.music.126.net/<hash>/<id>.jpg
        assertTrue("封面 URL 应含 hash 路径段", u.url!!.contains("126.net/") && u.url.contains(".jpg"))
    }

    // ---------------- 榜单：封面 / 时长 两个实测坑 ----------------

    @Test
    fun chart_bang93_picIsBrokenDirectory_soMustUseV9Pic2() {
        val d = KuwoJson.instance.decodeFromString<KuwoChartResponse>(fixture("kuwo_chart_bang93.json"))
        // 飙升榜的 pic 是**没有文件名的目录**，直接当 URL 用必然坏图
        val pic = d.pic.orEmpty()
        assertTrue("pic 应确实是个坏目录路径", pic.endsWith("BangPic/"))
        // v9_pic2 才是完整可用的 URL
        assertTrue("v9_pic2 必须是完整图片 URL", !d.v9Pic2.isNullOrEmpty() && d.v9Pic2!!.endsWith(".jpg"))
    }

    @Test
    fun chart_bang16_picIsFullPath() {
        val d = KuwoJson.instance.decodeFromString<KuwoChartResponse>(fixture("kuwo_chart_bang16.json"))
        assertTrue("热歌榜的 pic 反而是完整路径（所以只看 pic 会时好时坏）",
            d.pic.orEmpty().endsWith(".jpg"))
        assertTrue("v9_pic2 同样可用", !d.v9Pic2.isNullOrEmpty())
    }

    @Test
    fun chart_durationMustComeFromSongDuration() {
        for (f in listOf("kuwo_chart_bang93.json", "kuwo_chart_bang16.json")) {
            val d = KuwoJson.instance.decodeFromString<KuwoChartResponse>(fixture(f))
            val s = d.musiclist!!.first()
            val songDur = s.songDuration!!.toLong()
            val badDur = s.duration!!.toLong()
            assertTrue("$f: duration 应是垃圾值（实测 2/78），真实时长远大于它", songDur > badDur)
            assertTrue("$f: song_duration 必须是可信的分钟级时长", songDur in 60..900)
            // durationMsSafe 必须取 song_duration
            assertEquals(songDur * 1000L, s.durationMsSafe())
        }
    }

    @Test
    fun v9PicSizeRewrite() {
        // 与 KuwoClient.v9Pic 同规则：锚定 /albumcover/<数字>/ 换成 500，不能碰到路径里其它数字
        val raw = "http://img4.kuwo.cn/star/albumcover/120/s4s6/89/4105923142.jpg"
        val out = Regex("(/albumcover/)\\d+(/)").replaceFirst(raw, "$1500$2")
        assertEquals("http://img4.kuwo.cn/star/albumcover/500/s4s6/89/4105923142.jpg", out)
        // 路径里其它数字不能被改掉
        assertTrue(out.contains("s4s6/89/4105923142.jpg"))
    }
}
