package com.kmusic.apkhd.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.kmusic.apkhd.data.DiagLog

/**
 * 音频渲染器工厂：给 ALAC 挂一个真正的软解。
 *
 * 起因（真机报错）：
 * `ERROR_CODE_DECODING_FAILED ... audio/alac ... format_supported=YES`
 * 文件是 **ALAC**（苹果无损，李宗盛这批 .m4a 全是）。AOSP 根本没有 ALAC 软解器，
 * 手机上是厂商硬解：它自称支持（所以 format_supported=YES），真喂数据就崩。
 * 模拟器/PC 侧没这个解码器，反而一路正常，所以之前一直没暴露。
 *
 * 做法：
 *   1. 让平台解码器**不认领** ALAC（selector 对 audio/alac 直接返回空），
 *      其它格式（mp3/flac/aac…）仍走系统解码，不牺牲兼容性和功耗；
 *   2. 在渲染器列表末尾追加 FFmpeg 音频渲染器（org.jellyfin.media3 预编译版），
 *      ExoPlayer 会按顺序挑第一个能处理该格式的渲染器 —— ALAC 就落到 FFmpeg 上。
 *   3. 保留解码器回退：平台解码器初始化/运行失败会自动换下一个候选。
 */
@UnstableApi
class AudioRenderersFactory(
    context: Context,
    /** 省电播放（offload）：允许把 PCM 直接交给 DSP，音效链随之失效 */
    private val allowOffload: Boolean = false
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
    }

    /**
     * offload 开关。
     *
     * media3 没有「打开 offload」的布尔开关 —— 它是**自动**的：只要设备报告该格式
     * 可直通、且没有音频处理器在跑，就走 offload。所以这里做的是反过来的事：
     * 关掉的时候把「可直通」直接判成不支持，让它永远走普通 PCM 通路。
     *
     * 为什么不索性不管：不强制的话，用户关掉 offload 后设备仍可能自己直通，
     * 音效就莫名其妙失效 —— 开关必须是确定的。
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            // ⚠️ float 输出固定关掉，**不透传** DefaultRenderersFactory 的设置。
            // media3 1.7.1 的 `DefaultAudioSink.configure()` 里 float 输出走另一条分支，
            // 那条分支**不插入 audioProcessorChain**（只有 int 分支插）。一旦开了 float，
            // 高解析源（24/32bit/flac）就会绕过我们这条 EQ 链 —— 表现为
            // 「某些歌有效果、某些歌没有」，是最难查的那种 bug。
            // 代价：内部按 16bit 整数处理，biquad 中间态精度略降。
            // 播放器（不是母带工作站）这个取舍可以接受；真出问题也是「精度差一点」，
            // 而不是「功能时有时无」。
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        if (!allowOffload) {
            // 自研十段 EQ / 低音 / 响度，全部挂在 PCM 管线上，不占系统音效池位。
            // 传进去的处理器会排在 ToInt16/ChannelMapping/Trimming 之后、
            // 静音跳过与变速（Sonic）之前 —— 顺序由 DefaultAudioProcessorChain 保证。
            builder.setAudioProcessors(arrayOf(EqProcessor.instance))
            // 有处理器在链上时 media3 本来就不会走 offload，这条是显式声明，
            // 防止将来 media3 改了判定顺序后悄悄直通、把音效绕过去。
            builder.setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
        }
        // allowOffload = 用户选了省电播放：**不挂处理器**，让 media3 真走 DSP 直通。
        // 如果这时仍挂着处理器，offload 会被抑制（getAudioProcessors() 非空即不走），
        // 开关就变成「只是把音效关掉，还骗人说是省电」—— 语义不彻底，不如不挂。
        // 代价是音效真的没了，这也正是开关上写明的事。
        return builder.build()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,   // 扩展渲染器由下面手动加，顺便控制顺序
            ALAC_NOT_CLAIMED,
            true,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        if (FfmpegLibrary.isAvailable()) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
            DiagLog.log("CODEC", "FFmpeg 音频渲染器已加载（ALAC 软解）")
        } else {
            DiagLog.log("CODEC", "FFmpeg 音频渲染器不可用：ALAC 只能靠系统解码器")
        }
    }

    companion object {
        /** 平台解码器不要认领 ALAC：厂商硬解「说支持但解不了」，必须让 FFmpeg 接 */
        private val ALAC_NOT_CLAIMED = MediaCodecSelector { mime, secure, tunneling ->
            if (mime == MimeTypes.AUDIO_ALAC) {
                DiagLog.log("CODEC", "audio/alac → 交给 FFmpeg（平台解码器不参与）")
                emptyList()
            } else {
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            }
        }
    }
}
