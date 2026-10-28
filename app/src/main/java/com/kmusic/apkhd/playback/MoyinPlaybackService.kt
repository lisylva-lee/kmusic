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

    /** 播放器实例（class 字段，供 DSD 状态同步使用） */
    private var player: ExoPlayer? = null

    /** offload（省电直通）是否开启 —— 开了 PCM 不过本进程，音效随之失效 */
    private var offload: Boolean = false

    /** DSD 播放时是否自动旁路均衡器（用户设置，默认开） */
    private var dsdBypassEq: Boolean = true

    override fun onCreate() {
        super.onCreate()

        // DSD 容器头解析出真实倍率后回报（比猜文件名可靠）
        DsdExtractor.DsdPlaybackReporter.onLabel = { label ->
            DsdPlaybackState.update(true, label)
        }

        // DSD 播放状态变化 → 处理器切透传 / 恢复。
        //
        // v2.3.5 起这只是一次 flag 写入（EqProcessor.isBypassed），不拆管线、不重建：
        // 旧的 AudioEffect 链在 DSD 上要 release 再 attach，切歌会有几百毫秒的空窗；
        // 现在处理器始终在链上，只是那几帧不做浮点运算。
        DsdPlaybackState.setListener {
            val bypass = dsdBypassEq && DsdPlaybackState.isDsdPlaying
            FxChain.setDsdBypass(bypass)
        }

        // 播放缓存：容量来自设置（0=关闭，默认 1GB）。
        // 服务 onCreate 在主线程，DataStore 首读通常几毫秒 —— 加 800ms 兜底，
        // 读不到就按默认 1GB，绝不因为配置读失败而阻塞起播。
        val cacheGb = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).cacheSizeGb.first() }
            }
        }.getOrNull() ?: 1
        // offload：默认关。开了之后 PCM 直通 DSP，本进程的均衡器/低音/响度全部失效，
        // 所以它只能是个显式开关，不能默认替用户选。
        offload = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).audioOffload.first() }
            }
        }.getOrNull() ?: false
        FxChain.setOffload(offload)
        if (offload) {
            DiagLog.log("AUDIO", "offload 已开启：PCM 直通 DSP，音效本次不生效")
        }
        // DSD 输出采样率：44100（默认）/ 88200。
        // 读取方式和 offload 一样 —— 800ms 兜底，读不到就用默认值，绝不阻塞起播。
        val dsdOutRate = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).dsdOutRate.first() }
            }
        }.getOrNull() ?: 44100
        DiagLog.log("DSD", "DSD 输出采样率：$dsdOutRate Hz")

        // DSD 自动旁路 EQ：默认开。读了立刻同步到处理器（可能在 onCreate 之前就播过）
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

        p.addListener(object : Player.Listener {
            /**
             * 切歌时更新「当前是不是 DSD」。
             *
             * DSD 播放要自动旁路均衡器（解码链已经吃满 CPU，再跑十个 biquad 会挤掉音频线程），
             * 所以处理器必须知道现在播的是不是 DSD。
             */
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                updateDsdState(p)
            }

            /**
             * 处理器接进管线后采样率才知道（由它自己上报）。
             * 就绪时打一行日志当验收锚点 —— 有这行就说明 AudioProcessor 真的上链了。
             */
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY) return
                val proc = EqProcessor.instance
                DiagLog.log(
                    "EQ",
                    "播放就绪：处理器 ${proc.currentSampleRate}Hz 生效=${proc.working} 旁路=${proc.isBypassed}"
                )
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 用户从最近任务划掉 App：没在播就收摊，在播就继续（后台听歌不该被划掉打断） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        KuwoCipherHolder.clear()
        // 处理器不占任何系统资源，这里只是把参数清零，避免下次意外沿用
        FxChain.release()
        super.onDestroy()
    }
}
