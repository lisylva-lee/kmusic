package com.kmusic.apkhd.net

import com.kmusic.apkhd.data.DiagLog
import com.kmusic.apkhd.model.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 歌词仓库 —— lrclib.net 为主源。
 *
 * 选 lrclib 的理由：按「歌名 + 歌手」查询，与音源无关，
 * 所以酷我 / 本地 / Navidrome 三种来源都能用同一套逻辑取词，
 * 不像酷我歌词接口只认自己的 rid。
 *
 * 返回带时间轴的 LRC 行；找不到就返回空列表（UI 显示「暂无歌词」）。
 */
class LyricRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(title: String, artist: String, durationMs: Long = 0): List<LyricLine> =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) {
                DiagLog.log("LYRIC_ABORT", "歌名为空，直接放弃")
                return@withContext emptyList()
            }
            val wantSec = durationMs / 1000

            // 名字先归一化成多个候选（去掉 (Live)/feat./多歌手 这些后缀）。
            // 直接拿原始名去查 —— 尤其是聚合源带后缀的名字 —— 必然查不到，
            // 这就是「有些歌没有歌词」的主因。
            val cands = LyricQuery.candidates(title, artist)
            DiagLog.log(
                "LYRIC_QUERY",
                "歌名='$title' 歌手='$artist' 时长=${wantSec}s → 候选 ${cands.size} 个: " +
                    cands.joinToString(" | ") { "${it.first}/${it.second.ifEmpty { "不限歌手" }}" }
            )

            // 1. 逐个候选试精确接口（绝大多数命中发生在这里）
            //    两次之间留一点间隔 —— lrclib 对并发敏感（实测并发 10 个有 6 个 503），
            //    一次取词最多发 5 个请求，不控速就是自己把自己限流了。
            for ((t, a) in cands.take(3)) {
                val t0 = System.currentTimeMillis()
                val ex = runCatching { getExact(t, a, durationMs) }.getOrNull()
                DiagLog.log(
                    "LYRIC_EXACT",
                    "试 /get 歌名='$t' 歌手='${a.ifEmpty { "不限" }}' → " +
                        if (ex.isNullOrEmpty()) "无结果" else "命中 ${ex.size} 行",
                    System.currentTimeMillis() - t0
                )
                if (!ex.isNullOrEmpty()) return@withContext ex
                if (INTER_REQUEST_MS > 0) Thread.sleep(INTER_REQUEST_MS)
            }

            // 2. 精确都不行 → 兜底搜索。**必须逐条校验**，只接受确认是这首歌的
            val (candName, candArtist) = cands.first()
            val t1 = System.currentTimeMillis()
            val found = runCatching { searchVerified(candName, candArtist, title, artist, wantSec) }.getOrNull()
            DiagLog.log(
                "LYRIC_SEARCH",
                "试 /search 歌名='$candName' 歌手='${candArtist.ifEmpty { "不限" }}' → " +
                    if (found.isNullOrEmpty()) "无通过校验的结果" else "命中 ${found.size} 行",
                System.currentTimeMillis() - t1
            )
            if (!found.isNullOrEmpty()) return@withContext found

            // 3. 最后一搏：不限歌手
            val t2 = System.currentTimeMillis()
            val last = runCatching { searchVerified(candName, "", title, artist, wantSec) }.getOrNull()
            DiagLog.log(
                "LYRIC_SEARCH_NOARTIST",
                "试 /search 不限歌手 → " + if (last.isNullOrEmpty()) "无结果" else "命中 ${last.size} 行",
                System.currentTimeMillis() - t2
            )
            DiagLog.log("LYRIC_MISS", "全部来源均未找到歌词: '$title' / '$artist'")
            last ?: emptyList()
        }

    private fun getExact(title: String, artist: String, durationMs: Long): List<LyricLine> {
        val sb = StringBuilder(API + "/get?")
        sb.append("track_name=").append(enc(title))
        if (artist.isNotBlank()) sb.append("&artist_name=").append(enc(artist))
        if (durationMs > 0) sb.append("&duration=").append(durationMs / 1000)
        val j = JSONObject(get(sb.toString()))
        return parseLyrics(j.optString("syncedLyrics"), j.optString("plainLyrics"))
    }

    /**
     * 兜底搜索：把结果逐条过 [LyricQuery.verify]，**只接受确认是这首歌的**；
     * 多条通过时取时长最接近的一条。
     *
     * 之前这里是无条件取第一条有词的 —— 歌名相近但其实是别的歌时就会配错词，
     * 那比「暂无歌词」更糟：用户看到的是错的词，而且不知道是错的。
     */
    private fun searchVerified(
        candTitle: String,
        candArtist: String,
        wantTitle: String,
        wantArtist: String,
        wantSec: Long
    ): List<LyricLine> {
        var rejected = 0
        val sb = StringBuilder(API + "/search?")
        sb.append("track_name=").append(enc(candTitle))
        if (candArtist.isNotBlank()) sb.append("&artist_name=").append(enc(candArtist))
        val arr: JSONArray = JSONArray(get(sb.toString()))

        var best: List<LyricLine>? = null
        var bestDist = Long.MAX_VALUE
        DiagLog.log("LYRIC_SEARCH_RAW", "lrclib 返回 ${arr.length()} 条候选")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ok = LyricQuery.verify(
                o.optString("trackName"), o.optString("artistName"), wantTitle, wantArtist
            )
            if (!ok) {
                // 记下被否的前 3 条就够 —— 全是"校验没过"的话，一看就知道是判据太严还是真不匹配
                if (rejected < 3) {
                    DiagLog.log(
                        "LYRIC_REJECT",
                        "候选[$i] '${o.optString("trackName")}' / '${o.optString("artistName")}' " +
                            "dur=${o.optLong("duration")} → 校验未过"
                    )
                    rejected++
                }
                continue
            }
            val lines = parseLyrics(o.optString("syncedLyrics"), o.optString("plainLyrics"))
            if (lines.isEmpty()) continue
            val dist = LyricQuery.durationDistance(wantSec, o.optLong("duration", 0))
            if (dist < bestDist) {
                bestDist = dist
                best = lines
            }
        }
        return best ?: emptyList()
    }

    /** 优先用带时间轴的 syncedLyrics；没有就用 plainLyrics（无时间轴，平均铺开） */
    internal fun parseLyrics(synced: String, plain: String): List<LyricLine> {
        if (synced.isNotBlank()) {
            val out = mutableListOf<LyricLine>()
            synced.lines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val times = TIME_TAG.findAll(line).toList()
                if (times.isEmpty()) return@forEach
                val text = line.substring(times.last().range.last + 1).trim()
                if (text.isEmpty()) return@forEach
                times.forEach { m ->
                    out += LyricLine(timeMs = toMs(m.groupValues[1]), text = text)
                }
            }
            return out.sortedBy { it.timeMs }
        }
        if (plain.isNotBlank()) {
            // 无时间轴：按行平均分布，保证能滚动高亮
            val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val step = if (lines.isEmpty()) 0L else 4000L
            return lines.mapIndexed { i, t -> LyricLine(timeMs = i * step, text = t) }
        }
        return emptyList()
    }

    /** LRC 时间标签：[mm:ss.xx] 或 [mm:ss.xxx] 或 [mm:ss] */
    internal fun toMs(tag: String): Long {
        val parts = tag.split(":")
        if (parts.size < 2) return 0
        val min = parts[0].toLongOrNull() ?: 0
        val sec = parts[1].toDoubleOrNull() ?: 0.0
        return (min * 60_000 + (sec * 1000).toLong())
    }

    /**
     * 带重试的 GET。
     *
     * ⚠️ 重试不是可选的优化，是必需的功能。实测 lrclib 对**并发**极其敏感：
     * 串行请求 20 次全 200，一旦并发 10 个就有 6 个返回
     * `503 ServerOverloaded`。而一次取词最多要发 5 个请求（3 个候选 + 2 次搜索），
     * 自己就是并发源 —— 不重试的话，用户会看到「同一首歌有时有词有时没有」。
     *
     * 只对 503 / 429 / 5xx 重试；404 是真没有，重试也是白等。
     */
    private fun get(url: String): String {
        var last: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "KMusic/1.0")
                    .build()
                http.newCall(req).execute().use { resp ->
                    // 429/503 = 被限流，值得重试；其它 4xx 是请求本身的问题，重试无意义
                    if (resp.code == 429 || resp.code == 503 || resp.code >= 500) {
                        throw java.io.IOException("retryable HTTP ${resp.code}")
                    }
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    return resp.body?.string() ?: throw java.io.IOException("empty")
                }
            } catch (e: java.io.IOException) {
                // 非限流类错误（404/解析失败）不重试，直接抛
                if (e.message?.startsWith("retryable") != true) throw e
                last = e
                if (attempt < MAX_ATTEMPTS) {
                    // 指数退避：0.4s → 0.8s → 1.6s。同时给服务器喘口气
                    val backoff = 400L * (1L shl (attempt - 1))
                    Thread.sleep(backoff)
                }
            }
        }
        throw last ?: java.io.IOException(" lyric fetch failed")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val API = "https://lrclib.net/api"

        /** 限流（429/503）时的重试次数。总等待约 0.4+0.8+1.6 = 2.8s */
        private const val MAX_ATTEMPTS = 4

        /** 同一首曲目多次尝试之间的最小间隔（ms），避免自己触发限流 */
        private const val INTER_REQUEST_MS = 120L
        private val TIME_TAG = Regex("\\[(\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?)]")
    }
}
