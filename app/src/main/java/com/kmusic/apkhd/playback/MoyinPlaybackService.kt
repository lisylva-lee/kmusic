package com.kmusic.apkhd.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.kmusic.apkhd.AppVisibility
import com.kmusic.apkhd.crypto.QmcV2Rc4
import com.kmusic.apkhd.audio.DsdExtractor
import com.kmusic.apkhd.data.DiagLog
import com.kmusic.apkhd.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.kmusic.apkhd.ui.MainActivity

/**
 * 进程内共享的当前解密器。
 *
 * 播放器由 [MoyinPlaybackService] 持有（同进程），而 ekey 是每首歌变的，
 * 所以用这个 holder 让 DataSource 动态读到当前密钥 —— 不能把 cipher 焊死进
 * MediaSourceFactory，否则换歌时会拿上一首的密钥解密。
 */
object KuwoCipherHolder {
    /**
     * 按**播放直链**索引解密器。
     *
     * 从「一个全局密钥」改成表：现在队列会整体进播放器的播放列表，
     * 多首加密曲目共存，一个全局槽位必然串密钥。每个直链唯一，用它做键最稳。
     */
    private val byUrl = java.util.concurrent.ConcurrentHashMap<String, QmcV2Rc4>()

    fun put(url: String, cipher: QmcV2Rc4?) {
        if (cipher != null && url.isNotEmpty()) byUrl[url] = cipher
    }

    fun get(url: String): QmcV2Rc4? = byUrl[url]

    fun clear() = byUrl.clear()
}

@UnstableApi
class MoyinPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /** 播放器实例（class 字段，供音效链调度使用） */
    private var player: ExoPlayer? = null

    /** offload（省电直通）是否开启 —— 开了就不挂音效链 */
    private var offload: Boolean = false

    /** DSD 播放时是否自动旁路均衡器（用户设置，默认开） */
    private var dsdBypassEq: Boolean = true

    companion object {
        /** 后台空闲多久后拆音效链（毫秒）。给「刚暂停就切走」留缓冲。 */
        const val FX_RELEASE_DELAY_MS = 15_000L
    }

    /** 前后台变化回调句柄，onDestroy 时反注册 */
    private var visibilityHandle: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()

        // DSD 容器头解析出真实倍率后回报（比猜文件名可靠）
        DsdExtractor.DsdPlaybackReporter.onLabel = { label ->
            DsdPlaybackState.update(true, label)
        }

        // DSD 播放状态变化 → 音效链旁路/恢复均衡器
        DsdPlaybackState.setListener {
            val bypass = dsdBypassEq && DsdPlaybackState.isDsdPlaying
            AudioEffectHolder.setDsdBypass(bypass)
        }

        // 前后台切换都要评估：
        // - 退到后台：判断是否该拆（配合 isPlaying）
        // - 回到前台：如果之前拆过，这里负责重建音效链
        //   （不回前台重建的话，用户切回来听歌会发现 EQ/低音全没了，
        //    要等下一次切歌 READY 才恢复 —— 表现为"音效时有时无"）
        visibilityHandle = AppVisibility.addListener { foreground ->
            if (!foreground) {
                DiagLog.log("EQ", "退到后台 → 开始计时（${FX_RELEASE_DELAY_MS / 1000}s 后评估释放）")
                scheduleFxRelease()
            } else if (AudioEffectHolder.releasedForBackground) {
                val id = player?.audioSessionId ?: 0
                if (id > 0) {
                    AudioEffectHolder.attach(id)
                    if (AudioEffectHolder.attached) {
                        DiagLog.log("EQ", "回到前台 → 重建音效链 session=$id")
                    } else {
                        // 池位可能被别的进程刚占住 —— 交给自动重试，用户不该看到这个
                        DiagLog.log("EQ", "回到前台 → 首次重建未成功，转自动重试 session=$id")
                        AudioEffectHolder.scheduleAutoRetry(id)
                    }
                    AudioEffectHolder.clearReleasedFlag()
                }
            }
        }

        // 播放缓存：容量来自设置（0=关闭，默认 1GB）。
        // 服务 onCreate 在主线程，DataStore 首读通常几毫秒 —— 加 800ms 兜底，
        // 读不到就按默认 1GB，绝不因为配置读失败而阻塞起播。
        val cacheGb = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).cacheSizeGb.first() }
            }
        }.getOrNull() ?: 1
        // offload：默认关。开了之后 EQ/低音/环绕全部失效（设备直通 DSP 不经音效链），
        // 所以它只能是个显式开关，不能默认替用户选。
        offload = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).audioOffload.first() }
            }
        }.getOrNull() ?: false
        if (offload) {
            DiagLog.log("AUDIO", "offload 已开启：音效链本次不挂载")
        }
        // DSD 输出采样率：44100（默认）/ 88200。
        // 读取方式和 offload 一样 —— 800ms 兜底，读不到就用默认值，绝不阻塞起播。
        val dsdOutRate = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).dsdOutRate.first() }
            }
        }.getOrNull() ?: 44100
        DiagLog.log("DSD", "DSD 输出采样率：$dsdOutRate Hz")

        // DSD 自动旁路 EQ：默认开。读了立刻同步到音效链（可能在 onCreate 之前就播过）
        dsdBypassEq = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).dsdBypassEq.first() }
            }
        }.getOrNull() ?: true

        val cache = MediaCache.get(this, cacheGb)
        val upstream: DataSource.Factory = KuwoEncryptedDataSource.Factory(this)
        val dataFactory: DataSource.Factory = if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                // 缓存包在解密之外：先解出明文再落盘，二次播放才不是噪音
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstream
        }

        val p = ExoPlayer.Builder(this)
            // ALAC（.m4a 苹果无损）优先软解 + 解码器回退：厂商硬解会「自称支持但解不出来」
            .setRenderersFactory(AudioRenderersFactory(this, allowOffload = offload))
            // cipher 通过 holder 动态获取，每次 open 都读当前值
            // DSD 用专用工厂（默认工厂不认识 DSF/DFF）
            .setMediaSourceFactory(DsdMediaSourceFactory(dataFactory, dsdOutRate))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 拔耳机自动暂停（否则会外放）
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = p

        // 均衡器挂在真实的 audio session 上 —— session 要等播放器建出音频轨才有，
        // 所以只能用这个回调拿（提前 setAudioSessionId 再挂，可能在 session
        // 还不存在时失败，表现为「均衡器静默无效」）
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // offload 直通 DSP，AudioEffect 挂上去也不会生效，反而让人以为「开了没效果」
                if (offload) return
                AudioEffectHolder.attach(audioSessionId)
                // DSD 旁路状态也要跟着 attach 走 —— 挂在 session 上之前先设好，
                // 否则第一次 apply 时 EQ 会被打开，DSD 播放中又要多跑一条 EQ
                AudioEffectHolder.setDsdBypass(dsdBypassEq && DsdPlaybackState.isDsdPlaying)
            }

            /**
             * 再补一条兜底。
             *
             * onAudioSessionIdChanged 在某些 ROM 上不会在首次起播时回调（session 是复用的，
             * id 没有「变化」），音效链就永远挂不上 —— 表现就是「均衡器只有开关」。
             * 播放器就绪时主动读一次当前 session id，没挂过就挂上。
             */
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY) return
                if (offload) return
                val id = p.audioSessionId
                if (id > 0 && !AudioEffectHolder.isAttached(id)) {
                    AudioEffectHolder.attach(id)
                }
            }

            /**
             * 播放状态一变就重新评估「要不要拆音效链」。
             *
             * 见 [AudioEffectHolder.releaseForBackground]：进程被杀时 AudioEffect
             * 的 native handle 永不 release，会在 AudioFlinger 里留下永久占位的孤儿链，
             * 漏几十次之后 effect 池就满了，音效项成片消失。
             */
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                scheduleFxRelease()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                scheduleFxRelease()
            }

            /**
             * 切歌时更新「当前是不是 DSD」。
             *
             * DSD 播放要自动旁路均衡器（解码链已经吃满 CPU，再挂 EQ 会挤掉音频线程），
             * 所以音效链必须知道现在播的是不是 DSD。
             */
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                updateDsdState(p)
            }
        })

        session = MediaSession.Builder(this, p)
            // 点通知回到 App
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    /**
     * 更新「当前是否在播 DSD」的状态。
     *
     * 只看扩展名 —— DSD 的容器头（`DSD ` / `FRM8`）在 MediaItem 里不可见，
     * 而扩展名是确定的。等真正起播后如果解码失败，日志里会看到具体原因。
     */
    private fun updateDsdState(p: ExoPlayer) {
        val item = p.currentMediaItem
        val uri = item?.localConfiguration?.uri
        val isDsd = uri != null && DsdMediaSourceFactory.isDsdUri(uri)
        // 倍率标签由 DsdExtractor 解析容器头后回报（这里只管「是不是 DSD」）
        if (!isDsd) DsdPlaybackState.update(false, null)
    }

    // ── 音效链后台释放 ──

    /**
     * 后台空闲时把音效链拆掉，把全局 effect 池位还给系统。
     *
     * 判定条件（三条同时满足才拆）：
     * 1. App 不在前台（没有可见 Activity）；
     * 2. **当前没有在播**（`!player.isPlaying`）—— 正在播歌时绝不拆，
     *    否则用户切到别的 App 继续听，音效就没了；
     * 3. 已经空闲满 15 秒 —— 给「刚暂停就切走」留出缓冲，不做急刹。
     *
     * 拆完只留配置（存在 [AudioEffectHolder] 的 pendingRestore 里），
     * 下次 attach 时自动恢复 —— 用户不用重新调一遍。
     */
    private fun scheduleFxRelease() {
        if (offload) return
        val p = player ?: return
        val foreground = AppVisibility.isForeground
        val playing = p.isPlaying
        if (foreground || playing) {
            // 回到前台或在播 → 取消释放计划；但如果之前拆过，这里要重建
            if (AudioEffectHolder.releasedForBackground) {
                val id = p.audioSessionId
                if (id > 0) {
                    AudioEffectHolder.attach(id)
                    DiagLog.log("EQ", "回到前台/开始播放 → 重建音效链 session=$id")
                }
            }
            fxReleaseRunnable?.let { handler.removeCallbacks(it) }
            fxReleaseRunnable = null
            return
        }
        // 后台且没在播 → 15 秒后拆
        if (fxReleaseRunnable != null) return
        val runnable = Runnable {
            fxReleaseRunnable = null
            val pp = player ?: return@Runnable
            if (AppVisibility.isForeground || pp.isPlaying) return@Runnable
            val id = pp.audioSessionId
            if (id > 0) AudioEffectHolder.releaseForBackground(id)
        }
        fxReleaseRunnable = runnable
        handler.postDelayed(runnable, FX_RELEASE_DELAY_MS)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var fxReleaseRunnable: Runnable? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 用户从最近任务划掉 App：没在播就收摊，在播就继续（后台听歌不该被划掉打断） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        visibilityHandle?.invoke()
        visibilityHandle = null
        fxReleaseRunnable?.let { handler.removeCallbacks(it) }
        fxReleaseRunnable = null
        session?.run {
            player.release()
            release()
        }
        session = null
        KuwoCipherHolder.clear()
        AudioEffectHolder.release()
        super.onDestroy()
    }
}
