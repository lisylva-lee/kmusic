package com.kmusic.apkhd.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.kmusic.apkhd.data.DiagLog
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * 十段均衡器 + 低音 + 响度（v2.3.5 起取代 `DynamicsProcessing`）。
 *
 * ## 为什么不用系统音效了
 *
 * 旧实现把所有东西塞进一个 [android.media.audiofx.DynamicsProcessing]：
 *  - 它要占**系统音效池位**。真机日志里池位只剩 1 个，于是「开均衡器 → 低音/响度饿死」，
 *    还得在暂停 15 秒后把池位还回去、起播再抢回来 —— 一整套生命周期都是为池位服务的。
 *  - 它的 preEq 是 **FFT 分箱**（AOSP `DPFrequency.cpp`：`binStop = freq × blockSize / sampleRate`），
 *    默认 10ms 帧 @48kHz 只有 93.75 Hz/bin —— 31Hz 落 bin 0、62Hz 与 125Hz 撞在同一个 bin。
 *    在这种分辨率上画十段，低频三段调哪个都一样，UI 是骗人的。
 *
 * 现在跑在 media3 的 [AudioProcessor] 链上：每段一个 RBJ peaking biquad，
 * 中心频率精确到任意值，**不占任何系统音效池位**，也不依赖 ROM 实现了什么。
 *
 * ## 音质取舍
 *
 * Q = √2 ≈ 1.414 对应 1 个倍频程带宽；十段中心频率正好每段一个倍频程，
 * 于是相邻段首尾相接 —— 既不会在段间留出深谷，也不会叠出尖峰。
 * 转置直接 II 型，每段每声道 2 个状态变量，系数只在参数变化时重算。
 *
 * ## 关掉时真的不花钱
 *
 * 三项全关 → [isActive] 仍返回 true（否则要重配整条管线，会变成「改设置要等切歌才生效」），
 * 但 [queueInput] 只做一次 `ByteBuffer` 拷贝就返回。
 */
@UnstableApi
class EqProcessor : BaseAudioProcessor() {

    companion object {
        /**
         * 全进程唯一实例。
         *
         * 不跟 audio session 走：处理器活在 PCM 管线上，没有「音效槽」这个概念，
         * 因此不存在池位、也不需要切歌重建。播放器建 sink 时挂上它，
         * 界面改参数直接写这个对象 —— 立即生效，不用通知播放器。
         */
        val instance: EqProcessor by lazy { EqProcessor() }

        /** 每段带宽 = 1 个倍频程时 Q = √2 */
        private const val Q = 1.4142135f

        /** 低音搁架的拐点：62~125 Hz 是鼓/贝斯的基频区 */
        const val BASS_SHELF_HZ = 110f

        /** 低音满档的搁架增益（dB） */
        const val BASS_MAX_DB = 9f

        /** 软限幅的起始电平（线性），超过它才按 tanh 压 */
        private const val SOFT_KNEE = 0.72f

        /** 增益上下限（mB），与旧设备均衡器一致 */
        const val LEVEL_MIN = -1_500
        const val LEVEL_MAX = 1_500
    }

    // ── 参数：界面线程写、音频线程读 ──
    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var bypassed: Boolean = false

    @Volatile
    private var gainsMb: IntArray = IntArray(EqBands.COUNT)

    @Volatile
    private var bassStrength: Int = 0

    @Volatile
    private var preGainDb: Float = 0f

    /** 参数版本号：变了才重算系数 */
    @Volatile
    private var paramVersion: Int = 0

    private var appliedVersion: Int = -1

    // ── 运行态 ──
    @Volatile
    private var sampleRate: Int = 0

    @Volatile
    private var channelCount: Int = 0

    private var encoding: Int = C.ENCODING_INVALID

    /** [band][channel] 状态（转置直接 II 型：z1、z2） */
    private var z1 = Array(EqBands.COUNT) { FloatArray(0) }
    private var z2 = Array(EqBands.COUNT) { FloatArray(0) }

    /** 每段系数，整体替换（不原地改，避免音频线程读到半套系数） */
    private var coeffs = Array(EqBands.COUNT) { Biquad.IDENTITY }

    private var shelf = Biquad.IDENTITY
    private var shelfZ1 = FloatArray(0)
    private var shelfZ2 = FloatArray(0)

    // ── 对外只读状态（界面用它解释「为什么没变化」） ──

    /** 实际输出采样率（Hz），0 = 还没接进管线 */
    val currentSampleRate: Int get() = sampleRate

    /**
     * 接进管线 / 断开时回调（参数是采样率，0 = 已断开）。
     *
     * 处理器跑在音频线程，界面不知道它什么时候真正上链 —— 没有这个回调，
     * 用户起播后看到的状态会一直停在「起播后立刻生效」，明明已经生效了。
     * 回调里只允许写 State（[FxChain] 会切到主线程），不要做重活。
     */
    @Volatile
    var onFormatChanged: ((Int) -> Unit)? = null

    /** DSD / 省电播放时整条处理器透明通过 */
    var isBypassed: Boolean
        get() = bypassed
        set(on) {
            if (bypassed == on) return
            bypassed = on
            paramVersion++
        }

    /**
     * 此刻是否有任何一项在改声音。
     *
     * 曲线全零 + 无低音 + 无前级 = 恒等变换，[queueInput] 走透传。
     * ⚠️ 这里返回 false 时**不能**让 [isActive] 变 false —— 那要重配管线，
     * 会变成「关掉效果要等切歌才安静」。
     */
    val working: Boolean
        get() = !bypassed && (
            (enabled && gainsMb.any { it != 0 }) ||
                bassStrength > 0 ||
                abs(preGainDb) > 0.001f
            )

    /**
     * 让给 EQ 的前级余量（dB，正数）。
     *
     * 用解析幅频响应算整条曲线的峰值，而不是取十段里的最大值 ——
     * 相邻段在中间频率会叠加，取最大值会低估（见 [Biquad.peakBoostDb]）。
     */
    fun headroomDb(): Float {
        if (!working) return 0f
        val fs = sampleRate.toFloat().takeIf { it > 0f } ?: 48_000f
        return Biquad.peakBoostDb(gainsMb, bassStrength, fs, BASS_SHELF_HZ, BASS_MAX_DB, Q)
    }

    // ── 参数写入 ──

    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        paramVersion++
    }

    /** 写入十段增益（mB）。长度不对会按对数轴插值（见 [EqBands.normalize]） */
    fun setGains(milliBel: IntArray) {
        val next = EqBands.normalize(milliBel)
        if (next.contentEquals(gainsMb)) return
        gainsMb = next
        paramVersion++
    }

    /** 低音增强强度 0..1000（0 = 关） */
    fun setBass(strength: Int) {
        val v = strength.coerceIn(0, 1_000)
        if (v == bassStrength) return
        bassStrength = v
        paramVersion++
    }

    /** 前级增益（dB）。响度 + 让给 EQ 的余量已由调用方算好 */
    fun setPreGainDb(db: Float) {
        val v = db.coerceIn(-24f, 24f)
        if (abs(v - preGainDb) < 0.001f) return
        preGainDb = v
        paramVersion++
    }

    /** 一次性写入全部参数（避免连发几次 paramVersion 各重算一遍系数） */
    fun applyParams(
        enabled: Boolean,
        gainsMb: IntArray,
        bassStrength: Int,
        preGainDb: Float
    ) {
        this.enabled = enabled
        this.gainsMb = EqBands.normalize(gainsMb)
        this.bassStrength = bassStrength.coerceIn(0, 1_000)
        this.preGainDb = preGainDb.coerceIn(-24f, 24f)
        paramVersion++
    }

    // ── AudioProcessor ──

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 只处理线性 PCM。不认识的编码就声明「我不处理」（NOT_SET）——
        // 假装处理然后吐噪音是最坏的结果。
        if (!isSupportedPcm(inputAudioFormat.encoding) ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            DiagLog.log(
                "EQ",
                "处理器跳过：enc=${inputAudioFormat.encoding} " +
                    "sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}"
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        z1 = Array(EqBands.COUNT) { FloatArray(channelCount) }
        z2 = Array(EqBands.COUNT) { FloatArray(channelCount) }
        shelfZ1 = FloatArray(channelCount)
        shelfZ2 = FloatArray(channelCount)
        appliedVersion = -1
        DiagLog.log(
            "EQ",
            "十段处理器接入：${sampleRate}Hz ${channelCount}ch ${encodingName(encoding)}"
        )
        onFormatChanged?.invoke(sampleRate)
        // 输出格式与输入一致：只改振幅，不改采样率、不改声道数
        return inputAudioFormat
    }

    override fun onFlush() {
        clearState()
        appliedVersion = -1
    }

    override fun onReset() {
        clearState()
        sampleRate = 0
        channelCount = 0
        appliedVersion = -1
        onFormatChanged?.invoke(0)
    }

    /**
     * 永远 active。
     *
     * 若按「有没有参数」动态返回 false，media3 会在 configure/flush 时把这条处理器
     * 从管线里摘掉，于是「播放中打开均衡器」要等切歌才生效 —— 最容易被当成开关坏了。
     * 透传路径只多一次拷贝，代价可忽略。
     */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val bps = bytesPerSample(encoding)
        val frameSize = bps * channelCount
        if (size <= 0) return

        val passthrough = bypassed || !working || sampleRate <= 0 ||
            channelCount <= 0 || frameSize <= 0 || size < frameSize

        if (passthrough) {
            val out = replaceOutputBuffer(size)
            out.put(inputBuffer)
            out.flip()
            inputBuffer.position(inputBuffer.limit())
            return
        }

        refreshCoefficientsIfNeeded()

        val out = replaceOutputBuffer(size)
        val frames = size / frameSize
        val start = inputBuffer.position()
        val gain = 10f.pow(preGainDb / 20f)
        val bassOn = bassStrength > 0
        val bands = EqBands.COUNT
        val ch = channelCount
        val cf = coeffs
        val sh = shelf

        var base = start
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var x = readSample(inputBuffer, base + c * bps)
                var b = 0
                while (b < bands) {
                    val k = cf[b]
                    val y = k[0] * x + z1[b][c]
                    z1[b][c] = k[1] * x - k[2] * y + z2[b][c]
                    z2[b][c] = k[3] * x - k[4] * y
                    x = y
                    b++
                }
                if (bassOn) {
                    val y = sh[0] * x + shelfZ1[c]
                    shelfZ1[c] = sh[1] * x - sh[2] * y + shelfZ2[c]
                    shelfZ2[c] = sh[3] * x - sh[4] * y
                    x = y
                }
                x *= gain
                if (x > SOFT_KNEE || x < -SOFT_KNEE) x = softClip(x)
                writeSample(out, x)
            }
            base += frameSize
        }
        // 不足一帧的尾巴原样带过去（正常不会发生，防御性）
        val tail = size - frames * frameSize
        for (i in 0 until tail) out.put(inputBuffer.get(start + frames * frameSize + i))
        out.flip()
        // ⚠️ 必须把输入缓冲「消费掉」：管线靠 position 前进来判断还有没有数据，
        // 不前进就会把同一个 buffer 再喂一遍 —— 声音叠两遍，还越叠越多。
        inputBuffer.position(inputBuffer.limit())
    }

    // ── 内部 ──

    private fun clearState() {
        z1.forEach { it.fill(0f) }
        z2.forEach { it.fill(0f) }
        shelfZ1.fill(0f)
        shelfZ2.fill(0f)
    }

    private fun refreshCoefficientsIfNeeded() {
        val v = paramVersion
        if (v == appliedVersion) return
        appliedVersion = v
        val fs = sampleRate.toFloat()
        val next = Array(EqBands.COUNT) { b ->
            val db = if (enabled) gainsMb.getOrElse(b) { 0 } / 100f else 0f
            Biquad.peaking(EqBands.FREQS[b].toFloat(), db, Q, fs)
        }
        coeffs = next
        val bassDb = if (bassStrength > 0) bassStrength / 1_000f * BASS_MAX_DB else 0f
        shelf = Biquad.lowShelf(BASS_SHELF_HZ, bassDb, 0.8f, fs)
    }

    /**
     * 软限幅：超过 [SOFT_KNEE] 的部分按 tanh 压，不会像硬削波那样炸出刺耳高次谐波。
     * 只在真要溢出时才走这条路 —— 正常音量下就是无损直通。
     */
    private fun softClip(x: Float): Float {
        val drive = (abs(x) - SOFT_KNEE) / (1f - SOFT_KNEE)
        val shaped = 0.7f * tanhApprox(drive) * (1f - SOFT_KNEE) + SOFT_KNEE * sign(x)
        // 极小概率出现 Inf/NaN 时退回硬限幅：绝不让一个坏样本污染整个缓冲
        return if (shaped.isFinite()) shaped.coerceIn(-1f, 1f) else sign(x)
    }

    /** tanh 的有理近似（|x|<3 时误差 <1e-3），比 Math.tanh 快一个数量级 */
    private fun tanhApprox(x: Float): Float {
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }

    private fun isSupportedPcm(enc: Int): Boolean =
        enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_24BIT ||
            enc == C.ENCODING_PCM_32BIT || enc == C.ENCODING_PCM_FLOAT

    private fun bytesPerSample(enc: Int): Int = when (enc) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    private fun readSample(buf: ByteBuffer, index: Int): Float = when (encoding) {
        C.ENCODING_PCM_FLOAT -> buf.getFloat(index)
        C.ENCODING_PCM_24BIT -> {
            val lo = buf.get(index).toInt() and 0xFF
            val mid = buf.get(index + 1).toInt() and 0xFF
            val hi = buf.get(index + 2).toInt() // 有符号
            ((hi shl 16) or (mid shl 8) or lo) / 8_388_608f
        }
        C.ENCODING_PCM_32BIT -> buf.getInt(index) / 2_147_483_648f
        else -> buf.getShort(index) / 32_768f
    }

    /** 相对写入（自动前进 position，配合 flip() 定界） */
    private fun writeSample(buf: ByteBuffer, v: Float) {
        val x = if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> buf.putFloat(x)
            C.ENCODING_PCM_24BIT -> {
                val i = (x * 8_388_607f).toInt().coerceIn(-8_388_608, 8_388_607)
                buf.put((i and 0xFF).toByte())
                buf.put(((i shr 8) and 0xFF).toByte())
                buf.put(((i shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> buf.putInt((x * 2_147_483_647f).toInt())
            else -> buf.putShort((x * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
        }
    }

    private fun encodingName(enc: Int): String = when (enc) {
        C.ENCODING_PCM_16BIT -> "16bit"
        C.ENCODING_PCM_24BIT -> "24bit"
        C.ENCODING_PCM_32BIT -> "32bit"
        C.ENCODING_PCM_FLOAT -> "float"
        else -> "enc$enc"
    }
}
