package com.kmusic.apkhd.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 导赏 —— 走标准 OpenAI 兼容的 `/chat/completions`，**流式**返回。
 *
 * 为什么必须流式：一篇导赏 300~450 字，非流式下用户要盯着空白等十几秒，
 * 移动网络上一次请求还容易超时。流式第一个字通常一秒内就到，
 * 观感上像「有人在当场写」，等待焦虑也小得多。
 *
 * 兼容范围：只要对方是 OpenAI 格式即可 —— DeepSeek / 通义 / 智谱 /
 * Moonshot / 本地 vLLM 都能直接填地址。
 */
class AiService(
    private val client: OkHttpClient = defaultClient()
) {

    data class Config(val endpoint: String, val apiKey: String, val model: String)

    /**
     * 逐段产出导赏文本。调用方 collect 到 StringBuilder 里就是打字机效果。
     *
     * @throws IOException 网络失败、HTTP 非 2xx、或未配置 key
     */
    fun analyze(cfg: Config, name: String, artist: String, lyrics: String): Flow<String> {
        // 歌词只取前 35 行：整首塞进去既贵，也容易让模型从「赏析」滑成「复述」
        val excerpt = lyrics.lineSequence()
            .filter { it.isNotBlank() }
            .take(35)
            .joinToString("\n")
        val user = buildString {
            append("歌名：《").append(name).append("》\n")
            append("歌手：").append(artist).append("\n\n歌词节选：\n")
            append(excerpt.ifBlank { "（暂无歌词数据）" })
        }
        return chat(cfg, REVIEW_PROMPT, user)
    }

    /**
     * 把口语化的收听需求改写成检索关键词。
     *
     * 例：「适合深夜一个人听的安静粤语歌」→「粤语 安静 抒情」
     *
     * 只改写查询，不让模型直接产出歌单：我们的曲库在云端，
     * 本地没有完整索引，模型凭空列出来的歌名九成是编的。
     */
    fun rewriteQuery(cfg: Config, query: String): Flow<String> =
        chat(cfg, REWRITE_PROMPT, query)

    /**
     * 逐行翻译歌词。
     *
     * 输入输出都用「序号|文本」的纯文本格式，而不是 JSON：
     *  - JSON 在流式传输时不完整，没闭合就解析不了
     *  - 这种一行一条的格式，坏了哪行就丢哪行，不会整篇作废
     */
    fun translateLyrics(cfg: Config, lines: List<String>): Flow<String> =
        chat(
            cfg,
            TRANSLATE_PROMPT,
            lines.mapIndexed { i, l -> "${i + 1}|$l" }.joinToString("\n")
        )

    /**
     * 直接让模型给出歌单曲目（歌名|歌手）。
     *
     * 和 [pickTracks] 的区别：那个是从**已有候选**里挑，这个是从模型的知识里出题。
     * 单独用会编歌名，所以调用方必须逐首去曲库验证、验不过就丢 —— 见 ViewModel 的实现。
     *
     * 用这条路的原因是：靠关键词去曲库「碰」，碰出来的是搜索相关性，不是歌单质量。
     * 想要「适合开车听的粤语老歌」这种歌单，必须先知道该有哪些歌。
     */
    fun proposeTracks(cfg: Config, description: String, count: Int): Flow<String> =
        chat(cfg, PROPOSE_PROMPT, "$description\n\n请推荐 $count 首。")

    /**
     * 从候选曲目里挑歌。
     *
     * 模型只能返回**序号**，不能返回歌名 —— 序号是我们给的、能对应回真实曲目的东西，
     * 歌名是它可以随便编的。这是整个 AI 歌单不产生幻觉的前提。
     */
    fun pickTracks(cfg: Config, description: String, candidates: String): Flow<String> =
        chat(
            cfg,
            PICK_PROMPT,
            "需求：$description\n\n候选列表（序号|歌名|歌手）：\n$candidates"
        )

    private fun chat(cfg: Config, systemPrompt: String, userContent: String): Flow<String> = flow {
        if (cfg.apiKey.isBlank()) throw IOException("尚未配置 API Key")

        val base = try {
            validatedEndpoint(cfg.endpoint)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message)
        }
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        val body = buildJsonObject {
            put("model", cfg.model)
            put("stream", true)
            put("temperature", 0.7)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userContent)
                }
            }
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(300)
                throw IOException("HTTP ${resp.code}：$err")
            }
            val src = resp.body?.source() ?: throw IOException("响应为空")

            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                val delta = runCatching {
                    Json.parseToJsonElement(payload).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull
                }.getOrNull()

                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 端点校验。
     *
     * 用户填的地址会直接拿去发 Bearer key，所以必须先确认这地址没被引到坑里：
     *  - 只认 http/https
     *  - 必须有主机名
     *  - 不许在 URL 里塞 user:pass（会被当凭据发出去）
     *  - 不许带 query / fragment（拼 /chat/completions 时会拼坏）
     *  - **http 只允许局域网**：公网明文传 key 等于把 key 送人，这一条是底线
     *
     * 规则参照 Primuse 的 AIRemoteEndpointPolicy。
     */
    private fun validatedEndpoint(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw IllegalArgumentException("接口地址为空")

        val uri = try {
            java.net.URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("接口地址格式不正确")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw IllegalArgumentException("接口地址必须以 http:// 或 https:// 开头")
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("接口地址缺少主机名")
        if (uri.userInfo != null) throw IllegalArgumentException("接口地址不能包含用户名和密码")
        if (uri.query != null || uri.fragment != null) {
            throw IllegalArgumentException("接口地址不能带参数或 # 锚点")
        }
        if (scheme == "http" && !isPrivateHost(host)) {
            throw IllegalArgumentException("公网地址请用 https —— http 会明文传输 API Key")
        }
        return trimmed
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val p = h.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return when {
            p[0] == 10 || p[0] == 127 -> true
            p[0] == 192 && p[1] == 168 -> true
            p[0] == 172 && p[1] in 16..31 -> true
            else -> false
        }
    }

    companion object {
        /**
         * 优先 IPv4 的 DNS。
         *
         * 踩过的坑：`gapi.5kk.cc.cd` 这类走 Cloudflare 的域名会同时返回 A 和 AAAA，
         * 而宿主机只有内网 ULA（`fd17::`）、没有公网 IPv6 出口时，OkHttp 仍会先连
         * 公网 AAAA（`2606:4700::`）→ 直接 `Failed to connect`，而 IPv4 明明是通的。
         * 手机上「WiFi 有 IPv6 但实际出不去」的网络很常见。
         *
         * **只排序不够**：OkHttp 的连接失败重试会绕过我们给的顺序（实测仍然先连 AAAA）。
         * 必须直接过滤掉 IPv6 —— 只有在完全没有 IPv4 结果时才退回 IPv6，
         * 这样纯 IPv6 / NAT64 环境也还能用。
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 流式生成可能持续几十秒，读超时放宽；真正的卡死由界面取消兜底
            .readTimeout(120, TimeUnit.SECONDS)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val all = okhttp3.Dns.SYSTEM.lookup(hostname)
                    val v4 = all.filterIsInstance<java.net.Inet4Address>()
                    return v4.ifEmpty { all }
                }
            })
            .build()

        /**
         * 提示词沿用「三个维度 + 禁止套话」的骨架：
         * 不给维度的模型会写成百度百科，给了维度才像乐评。
         */
        private val PROPOSE_PROMPT = """
            你是资深音乐编辑。根据用户的描述，推荐最合适的歌曲。
            规则：
            - 每行一首，格式严格为「歌名|歌手」，不要序号、不要书名号、不要解释
            - 只推荐真实存在、广为人知的录音室版本
            - 避开 Live / 翻唱 / 伴奏 / DJ 版 / 纯音乐改编
            - 歌手写主要演唱者；多人合作只写前两位
            - **歌名和歌手一律用简体中文**（曲库是简体；粤语/港台歌也要简体，例如写
              「千千阙歌」「张国荣」，不要写「千千闕歌」「張國榮」）
            - 宁缺毋滥，不确定的不要写
        """.trimIndent()

        private val PICK_PROMPT = """
            你是歌单编排器。用户描述想要的歌单，你从给定候选里挑选最符合的。
            规则：
            - 只能从候选里选，用序号回答，绝不能输出候选之外的歌
            - 只输出一个 JSON 数组，例如 [0,3,7,12]
            - 选 8~12 首；候选里没有合适的就少选，宁缺毋滥
            - 不要输出解释、不要输出歌名
        """.trimIndent()

        private val TRANSLATE_PROMPT = """
            你是歌词翻译器。输入每行格式为「序号|原文」。
            把每行翻译成简体中文，输出格式必须严格是「序号|译文」。
            要求：
            - 行数与顺序必须与输入完全一致，一行对一行
            - 译文要像歌词，通顺有韵律，不要直译腔
            - 人名、地名、语气词可以保留原样
            - 不要输出解释、不要加引号、不要合并或拆分行
        """.trimIndent()

        private val REWRITE_PROMPT = """
            你是音乐检索词改写器。用户会用口语描述想听什么，你要把它变成适合音乐搜索引擎的关键词。
            规则：
            - 只输出关键词，空格分隔，最多 5 个
            - 保留语言、年代、流派、情绪、场景这类可检索的词
            - 不要输出歌名、歌手（除非用户明确点名）
            - 不要解释、不要引号、不要标点
            例：输入「适合深夜一个人听的安静粤语歌」→ 输出「粤语 安静 抒情」
        """.trimIndent()

        private val REVIEW_PROMPT = """
            你是一位学识深厚、文字优雅敏锐的音乐评论家与文化学者。
            请对用户正在收听的这首音乐写一篇凝练、深刻的沉浸式导赏（250~450 字）。
            请从以下三个维度有条理地切入，避免套话与空洞抒情：
            1. 【创作背景与内核】：时代氛围、创作动机，或这首作品真正想表达的核心命题。
            2. 【词作与隐喻解读】：结合关键意象或具体语句，剖析其文学性与藏起来的情感。
            3. 【听觉与编曲亮点】：配器走向、旋律风格，或某个具体的听觉细节为什么动人。
            排版清晰，适合在手机播放器里沉浸阅读。
            不要复述歌词，不要出现「这首歌」「总之」这类空转句，直接进入内容。

            排版约定（严格遵守，直接输出正文，不要加解释、不要用代码块包裹）：
            - 第一行写一句话主旨，不超过 20 字，结尾不加标点。
            - 空一行后，三个维度各用一行以「## 」开头的标题，如「## 创作背景与内核」。
            - 每个标题下写 1~2 段正文，段与段之间空一行。
            - 值得单独拎出来的原句，以「> 」开头单独成行，全篇最多两句。
            - 不要使用表格、不要使用代码块。
        """.trimIndent()
    }
}
