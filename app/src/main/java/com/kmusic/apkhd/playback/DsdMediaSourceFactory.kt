package com.kmusic.apkhd.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import com.kmusic.apkhd.audio.DsdExtractor
import com.kmusic.apkhd.data.DiagLog

/**
 * DSD 播放状态。
 *
 * 播放 DSD 时把「当前正在播 DSD」这件事广播出去，让音效链知道要旁路 EQ。
 * 用进程内 holder 而不是回调，是因为音效链在 `AudioEffectHolder` 里，
 * 而播放服务是另一个生命周期，两者都要能随时查。
 */
@UnstableApi
object DsdPlaybackState {
    /** 当前正在播放的是 DSD */
    @Volatile
    var isDsdPlaying: Boolean = false
        private set

    /** 当前 DSD 的倍率标签（DSD64 / DSD128 / DSD256），非 DSD 时为空 */
    @Volatile
    var currentLabel: String? = null
        private set

    /** 通知状态变化（音效链用它重新评估 EQ 挂载） */
    private var listener: (() -> Unit)? = null

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    /**
     * 设置当前播放状态。
     *
     * @param isDsd 是否在播 DSD
     * @param label DSD 倍率标签
     */
    fun update(isDsd: Boolean, label: String? = null) {
        if (isDsdPlaying == isDsd && currentLabel == label) return
        isDsdPlaying = isDsd
        currentLabel = label
        DiagLog.log("DSD", if (isDsd) "开始播放 $label" else "退出 DSD 播放")
        listener?.invoke()
    }
}

/**
 * 支持 DSD 的 MediaSource 工厂。
 *
 * media3 默认的 `DefaultMediaSourceFactory` 不认识 DSF/DFF（没有对应的
 * Extractor），所以这里为 `.dsf` / `.dff` 单独挂 [DsdExtractor]。
 *
 * `DefaultMediaSourceFactory` 在 media3 1.7 里是 **final**，不能继承，
 * 所以这里用组合：内部持一个默认工厂，遇到 DSD 才走自己的分支。
 *
 * 判断依据是**文件扩展名**：DSD 的容器头（`DSD ` / `FRM8`）不在媒体类型里，
 * 而扩展名在 MediaItem 上是确定的。
 */
@UnstableApi
class DsdMediaSourceFactory(
    upstream: DataSource.Factory,
    /** DSD 输出采样率：44100 / 88200 */
    private val dsdOutRate: Int
) : MediaSourceFactory {

    /** 非 DSD 走默认逻辑 */
    private val defaultFactory = DefaultMediaSourceFactory(upstream)

    /** DSD 走 ProgressiveMediaSource + 专用 Extractor */
    private val dsdFactory = ProgressiveMediaSource.Factory(
        upstream,
        ExtractorsFactory { arrayOf(DsdExtractor(dsdOutRate)) }
    )

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri != null && isDsdUri(uri)) {
            DiagLog.log("DSD", "DSD 媒体源：$uri → ${dsdOutRate} Hz PCM")
            return dsdFactory.createMediaSource(mediaItem)
        }
        return defaultFactory.createMediaSource(mediaItem)
    }

    // ── MediaSource.Factory 的其余抽象方法：转发给默认工厂 ──

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider
    ): MediaSourceFactory {
        defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        dsdFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
    ): MediaSourceFactory {
        defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        dsdFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray {
        val a = defaultFactory.getSupportedTypes()
        val b = dsdFactory.getSupportedTypes()
        return (a + b).distinct().toIntArray()
    }

    companion object {
        /** 判断 URI 是不是 DSD 文件 */
        fun isDsdUri(uri: Uri): Boolean {
            val path = uri.path ?: return false
            val lower = path.lowercase()
            return lower.endsWith(".dsf") || lower.endsWith(".dff")
        }
    }
}
