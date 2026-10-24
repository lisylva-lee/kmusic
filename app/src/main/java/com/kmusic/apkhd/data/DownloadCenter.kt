package com.kmusic.apkhd.data

import android.content.Context
import com.kmusic.apkhd.model.DownloadTask
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 下载中心 —— 单例，**由前台服务驱动**，不是 ViewModel。
 *
 * 之前下载跑在 viewModelScope 里：Activity 一销毁 / 系统一回收进程就断了，
 * 界面却还挂着「下载中…」，看起来像卡死。母带单曲上百 MB，这个体验不能接受。
 *
 * 现在字节搬运跑在 [MoyinDownloadService] 的协程里（前台常驻），退出 App 也继续下；
 * 进度通过 StateFlow 广播给 UI。
 *
 * **取链仍在 ViewModel 侧完成** —— 解析真实 URL 需要搜索、跨源匹配那一整套依赖，
 * 服务只负责「拿到 URL 之后把字节搬下来」，这样两边职责都单纯。
 *
 * ⚠️ 已知限制：待下载队列在内存里，进程被系统杀掉后不会恢复（已下到 .part 的
 * 部分不会丢，重新入队会从断点续传）。要彻底解决得把队列落库，另说。
 */
object DownloadCenter {

    /**
     * 「准备中…」占位状态。
     *
     * 点下载后要先做取链（搜索 + 跨源匹配，可能好几秒），这段时间界面之前**毫无变化**，
     * 用户以为没点上。现在点下去立刻放这个占位，取链完成由 [enqueue] 替换成「排队中…」。
     */
    const val PREPARING = "准备中…"

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var appContext: Context? = null

    private data class Req(
        val track: Track,
        val url: String,
        val ekey: String?,
        val label: String,
        /** 取流时的 user —— ekey 解密要用同一个值 */
        val user: String,
        /** WebDAV 等需要 Basic 的源：整条 Authorization 头 */
        val auth: String? = null
    )

    private val pending = ArrayDeque<Req>()

    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** 是否已在队列或已完成（**失败的算没有**，允许重新入队重试） */
    fun has(id: String): Boolean =
        _tasks.value.any { it.id == id && !it.status.startsWith("失败") }

    /**
     * 点下下载后**立刻上屏**的占位任务，让用户马上看到反应。
     * 取链完成后 [enqueue] 会用同 id 覆盖它；失败则由 [fail] 覆盖成失败原因。
     */
    fun prepare(track: Track) {
        if (has(track.id)) return
        _tasks.value = _tasks.value.filterNot { it.id == track.id } + DownloadTask(
            id = track.id, name = track.name, artist = track.artist,
            qualityLabel = "", progress = 0f, status = PREPARING, path = null
        )
    }

    fun enqueue(
        track: Track,
        url: String,
        ekey: String?,
        label: String,
        user: String,
        auth: String? = null
    ) {
        // 注意：占位状态「准备中…」不算「已排上」，否则 prepare() 之后 enqueue() 会被这里挡住
        if (_tasks.value.any {
                it.id == track.id && it.status != PREPARING && !it.status.startsWith("失败")
            }) return
        _tasks.value = _tasks.value.filterNot { it.id == track.id } + DownloadTask(
            id = track.id,
            name = track.name,
            artist = track.artist,
            qualityLabel = label,
            progress = 0f,
            status = "排队中…",
            path = null
        )
        synchronized(pending) { pending.addLast(Req(track, url, ekey, label, user, auth)) }
        kick()
    }

    /** 本地曲目本来就在本机，不用下载，直接标成已完成 */
    fun markLocal(track: Track, path: String) {
        _tasks.value = _tasks.value.filterNot { it.id == track.id } + DownloadTask(
            id = track.id, name = track.name, artist = track.artist,
            qualityLabel = "", progress = 1f, status = "已在本机", path = path
        )
    }

    /** 连取链都没过（没资源 / 仅 Wi-Fi 限制…），直接给出失败原因 */
    fun fail(track: Track, reason: String) {
        _tasks.value = _tasks.value.filterNot { it.id == track.id } + DownloadTask(
            id = track.id, name = track.name, artist = track.artist,
            qualityLabel = "", progress = 0f, status = "失败: $reason", path = null
        )
    }

    fun remove(id: String) {
        synchronized(pending) { pending.removeAll { it.track.id == id } }
        _tasks.value = _tasks.value.filterNot { it.id == id }
        appContext?.let { DownloadManager(it).clearPart(id) }
    }

    private fun update(id: String, f: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) f(it) else it }
    }

    private fun kick() {
        val ctx = appContext ?: return
        MoyinDownloadService.start(ctx)
        if (worker?.isActive == true) return
        worker = scope.launch {
            val mgr = DownloadManager(ctx)
            while (true) {
                val req = synchronized(pending) { pending.removeFirstOrNull() } ?: break
                runOne(mgr, req)
            }
            MoyinDownloadService.stop(ctx)
        }
    }

    private suspend fun runOne(mgr: DownloadManager, req: Req) {
        val id = req.track.id
        val resumeAt = mgr.partSize(id)
        update(id) { it.copy(status = if (resumeAt > 0) "续传中…" else "下载中…") }
        try {
            val extra = req.auth?.let { mapOf("Authorization" to it) } ?: emptyMap()
            val part = mgr.fetchPart(id, req.url, req.ekey, req.user, extra) { done, total ->
                if (total > 0) {
                    update(id) { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
            val path = mgr.publishPart(req.track, part, req.label, req.url)
            update(id) { it.copy(progress = 1f, status = "已完成", path = path) }
        } catch (e: Exception) {
            update(id) { it.copy(status = "失败: ${e.message ?: "未知错误"}") }
        }
    }
}
