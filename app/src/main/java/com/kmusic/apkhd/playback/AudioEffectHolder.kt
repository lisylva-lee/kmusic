package com.kmusic.apkhd.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kmusic.apkhd.data.DiagLog

/**
 * 系统音效链。全部挂在**播放器真实的 audio session** 上，而不是全局输出混音（0 号 session）——
 * 后者会作用到设备上所有声音，包括通知音和别的 App。
 *
 * 状态放在内存里：服务启动时播放器创建 audio session → 拿到 id 后
 * 按这里记着的开关/预设应用。若服务比界面先起来，界面稍后同步一次即可。
 *
 * 每种效果在设备上**可能根本不存在**（低端机常见只给均衡器），
 * 所以每个都有 available 判断 —— 界面按它决定显示还是隐藏，不做假开关。
 */
object AudioEffectHolder {

    private const val TAG = "MoyinEQ"

    /** 音效链的用户配置快照（后台释放 → 重建时恢复） */
    data class FxSettings(
        val enabled: Boolean,
        val presetIndex: Int,
        val customBands: Boolean,
        val manualLevels: Map<Int, Int>,
        val bassStrength: Int,
        val virtualStrength: Int,
        val loudnessGain: Int,
        val reverbPreset: Int,
        val dynamicsLevel: Int
    )

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null
    private var dynamics: DynamicsProcessing? = null

    /**
     * 音效链挂载版本号。播放器建出 audio session 才能创建这些效果，
     * 而那一刻发生在 Service 里、ViewModel 看不到 —— 用这个计数器让界面知道「可以刷新了」。
     */
    var sessionVersion by mutableIntStateOf(0)
        private set

    /**
     * 失败导致的界面刷新信号。
     *
     * 单独开一个而不是复用 sessionVersion：sessionVersion 有订阅者会回灌
     * （pushFx → applyToDevice → ensureXxx），失败时自增它会变成无限递归。
     * 这个只被界面读取，没有任何逻辑依赖它，安全。
     */
    var effectFailedTick by mutableIntStateOf(0)
        private set

    /** 当前已挂载的 audio session；0 表示还没挂 */
    private var attachedSessionId: Int = 0

    /** 最近一次挂载失败的原因，界面可以据此告诉用户「为什么没有调节」 */
    var lastError: String? = null
        private set

    /**
     * 本次挂载里，哪些项是因为 effect 池满（ERROR_NO_INIT / NO_MEMORY）挂不上的。
     *
     * 和「设备根本不给这个 effect」必须分开：前者重挂一次就能好，后者重挂一万次也没用。
     * 界面据此显示「重挂音效链」入口，只在真有可能救回来的时候出现。
     */
    var starvedTypes: Set<java.util.UUID> = emptySet()
        private set

    /** 已经重挂过几次 —— 池满时连续重挂没有意义，界面用它决定还提不提示 */
    var reattachCount: Int = 0
        private set

    /** 这个 session 已经挂过了吗 —— 避免每次 READY 都把整条链拆了重建 */
    fun isAttached(sessionId: Int): Boolean = eq != null && attachedSessionId == sessionId

    // ── 后台自动释放 ──
    //
    // 为什么需要：进程被系统强杀时 onDestroy 不跑，AudioEffect 的 native handle
    // 永远不 release，AudioFlinger 里就留下一条 clients=0 的**孤儿链** ——
    // 它永久占着全局 effect 池，而且**不回收**。实测每杀一次进程漏 2 个池位，
    // 漏几十次之后池子就满了，音效项会成片消失（表现为「资源占用」）。
    //
    // 关键事实（实测）：**进程内正常切歌不漏**（20 次 delta=0）。
    // 泄漏只发生在「进程死了」这条路径上。而系统杀进程绝大多数都发生在
    // 「长时间后台且没在播」的时候 —— 所以这里在后台空闲时主动拆链，
    // 把「我们自己造成的孤儿链」数量压到 0。
    //
    // 注意：**正在播歌时绝不拆**。切到别的 App 继续听歌时音效必须还在。

    /** 音效链是否已被后台策略拆除（下次起播需要重建） */
    @Volatile
    var releasedForBackground: Boolean = false
        private set

    /** 标记「已重建过」，避免回前台时反复走重建分支 */
    fun clearReleasedFlag() {
        releasedForBackground = false
    }

    /** 记录「用户配置」，供重建时恢复 */
    fun snapshotSettings(): FxSettings = FxSettings(
        enabled = enabled,
        presetIndex = presetIndex,
        customBands = customBands,
        manualLevels = manualLevels,
        bassStrength = bassStrength,
        virtualStrength = virtualStrength,
        loudnessGain = loudnessGain,
        reverbPreset = reverbPreset,
        dynamicsLevel = dynamicsLevel
    )

    /** 恢复用户配置（重建实例后调用） */
    fun restoreSettings(s: FxSettings) {
        enabled = s.enabled
        presetIndex = s.presetIndex
        customBands = s.customBands
        manualLevels = s.manualLevels
        bassStrength = s.bassStrength
        virtualStrength = s.virtualStrength
        loudnessGain = s.loudnessGain
        reverbPreset = s.reverbPreset
        dynamicsLevel = s.dynamicsLevel
    }

    /**
     * 后台空闲释放：拆掉整条音效链，把池位还给系统。
     *
     * @param sessionId 当前 audio session；释放后要靠它重建
     * @return true 表示确实拆了
     */
    fun releaseForBackground(sessionId: Int): Boolean {
        if (attachedSessionId <= 0) return false
        // 记录配置 —— 重建时要用
        val snapshot = snapshotSettings()
        release()
        releasedForBackground = true
        DiagLog.log(
            "EQ",
            "后台空闲释放音效链（session=$sessionId），" +
                "已保存配置 eq=${snapshot.enabled} bass=${snapshot.bassStrength} " +
                "virt=${snapshot.virtualStrength} loud=${snapshot.loudnessGain} " +
                "reverb=${snapshot.reverbPreset} dyn=${snapshot.dynamicsLevel}"
        )
        pendingRestore = snapshot
        return true
    }

    /** 后台释放后保存的配置；下次 attach 时用它恢复 */
    @Volatile
    private var pendingRestore: FxSettings? = null

    /** 取回待恢复的配置（只取一次） */
    fun takePendingRestore(): FxSettings? {
        val s = pendingRestore
        pendingRestore = null
        return s
    }

    // ── 均衡器 ──
    var enabled: Boolean = false
        private set

    /**
     * 当前是否因为「正在播 DSD」而旁路了均衡器。
     *
     * 只影响设备上的开关，**不改变** [enabled] / [presetIndex] / [customBands]
     * 这些用户配置 —— 切回普通格式立刻恢复原样。
     */
    @Volatile
    var eqBypassedByDsd: Boolean = false
        private set

    /**
     * 设置 DSD 旁路状态，并重新应用。
     *
     * DSD 起播/结束、或者用户改了「DSD 自动旁路均衡器」设置时调用。
     */
    fun setDsdBypass(bypass: Boolean) {
        if (eqBypassedByDsd == bypass) return
        eqBypassedByDsd = bypass
        applyToDevice()
        sessionVersion++
        Log.i(TAG, "DSD 旁路均衡器：$bypass")
    }
    var presetIndex: Int = 0
        private set

    /**
     * 用户手动拖过频段 → true。
     *
     * 一旦进入自定义，就不能再 `usePreset()`，否则拖完滑块下一次 apply 又会被预设整表覆盖，
     * 用户会觉得「调了没用」。
     */
    var customBands: Boolean = false
        private set

    /** band 下标 → 增益（mB），仅 customBands 时生效 */
    private var manualLevels: Map<Int, Int> = emptyMap()

    // ── 增强效果 ──
    /** 低音增强强度，0..1000，0 视为关闭 */
    var bassStrength: Int = 0
        private set
    /** 环绕强度，0..1000，0 视为关闭 */
    var virtualStrength: Int = 0
        private set
    /** 响度增强增益，单位 mB（1000 mB = 10 dB），0 视为关闭 */
    var loudnessGain: Int = 0
        private set
    /** 混响预设：-1 关闭，0..6 见 PresetReverb 常量 */
    var reverbPreset: Int = -1
        private set
    /** 动态范围处理档位：0 关 / 1 轻 / 2 中 / 3 强 */
    var dynamicsLevel: Int = 0
        private set

    // ── 均衡器频段 ──

    /** 设备均衡器的频段数量；0 = 没挂上 */
    val bandCount: Int get() = runCatching { eq?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)

    /** 可调范围，元素为 [最小 mB, 最大 mB]（常见 ±1500） */
    fun bandRange(): IntArray = runCatching {
        eq?.bandLevelRange?.let { intArrayOf(it[0].toInt(), it[1].toInt()) }
    }.getOrNull() ?: intArrayOf(-1500, 1500)

    /**
     * 各频段中心频率（**Hz**），下标与 band 对应。
     *
     * ⚠️ [Equalizer.getCenterFreq] 返回的单位是 **mHz（毫赫兹）**，不是 Hz。
     * 平台文档写的是「中心频率，单位 mHz」，实测返回 60000 / 230000 / 910000 /
     * 3600000 / 14000000 —— 对应真实的 60 / 230 / 910 / 3.6k / 14k Hz，
     * 正好是标准 5 段均衡器的频段。
     * 当成 Hz 用会让标签全部大 1000 倍（显示 60.0k 实际是 60Hz），
     * 看起来就像「频段窄得离谱、EQ 基本没用」。
     */
    fun bandFreqs(): IntArray = runCatching {
        val e = eq ?: return IntArray(0)
        IntArray(e.numberOfBands.toInt()) { (e.getCenterFreq(it.toShort()).toInt() + 500) / 1000 }
    }.getOrDefault(IntArray(0))

    /** 各频段当前增益（mB） */
    fun bandLevels(): IntArray = runCatching {
        val e = eq ?: return IntArray(0)
        IntArray(e.numberOfBands.toInt()) { e.getBandLevel(it.toShort()).toInt() }
    }.getOrDefault(IntArray(0))

    /**
     * 手动设置某一段增益（mB）。
     * 会自动打开均衡器并切到自定义 —— 用户拖了滑块却听不到变化是最糟的体验。
     */
    fun setBandLevel(band: Int, levelMiliBel: Int) {
        val range = bandRange()
        val v = levelMiliBel.coerceIn(range[0], range[1])
        customBands = true
        enabled = true
        manualLevels = manualLevels + (band to v)
        runCatching {
            eq?.let { e ->
                e.enabled = true
                e.setBandLevel(band.toShort(), v.toShort())
            }
        }.onFailure { Log.w(TAG, "设置频段失败: ${it.message}") }
        com.kmusic.apkhd.data.DiagLog.log("EQ", "band[$band] = ${v}mB")
    }

    /** 回到预设模式，丢掉的已自定义频段 */
    fun usePresetMode(index: Int) {
        presetIndex = index
        customBands = false
        manualLevels = emptyMap()
        applyToDevice()
    }

    /**
     * 系统预设的英文名 → 中文名。
     *
     * 只换显示文本，**不动索引**：系统预设的下标要和 `Equalizer.usePreset(index)` 一一对应，
     * 一旦排序或插入新项，选中项就会错位到别的音色上。
     * 不同芯片/ROM 暴露的预设集合不一样，认不出的名字原样返回，不会变成空白。
     */
    private fun presetNameCn(raw: String): String = when (raw.trim().lowercase()) {
        "normal", "none" -> "原声"
        "classical" -> "古典"
        "dance" -> "舞曲"
        "flat" -> "平坦"
        "folk" -> "民谣"
        "heavy metal", "heavymetal" -> "重金属"
        "hip hop", "hiphop" -> "嘻哈"
        "jazz" -> "爵士"
        "pop" -> "流行"
        "rock" -> "摇滚"
        "ballad" -> "抒情"
        "bass boost", "bassboost" -> "低音增强"
        "treble boost", "trebleboost" -> "高音增强"
        "vocal" -> "人声"
        "loudness" -> "响度"
        "custom", "user" -> "自定义"
        else -> raw
    }

    /** 设备支持的预设名（中文）。没挂上（或设备不支持均衡器）时返回空表 */
    fun presetNames(): List<String> = runCatching {
        val e = eq ?: return emptyList()
        val n = e.numberOfPresets.toInt()
        if (n <= 0) return emptyList()
        (0 until n).map { presetNameCn(e.getPresetName(it.toShort())) }
    }.getOrDefault(emptyList())

    val attached: Boolean get() = eq != null

    /**
     * 设备固件里装了哪些 effect。
     *
     * `queryEffects()` 只读系统已注册的效果库清单，**不占 effect 池** ——
     * 这正是我们需要的：判断「这台机器有没有低音增强」不该靠「建一个试试」，
     * 建失败的代价是占掉一个池位，在池子小的机器上会把别的效果挤掉。
     *
     * 某些 ROM 上查不到清单，此时返回空集，调用方按「不否定」处理（交给创建去试）。
     */
    private val deviceTypes: Set<java.util.UUID> by lazy {
        runCatching {
            AudioEffect.queryEffects()?.filterNotNull()?.map { it.type }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }

    /** 设备有没有这个 effect。查不到清单时不做否定判断 —— 宁可试一次也不要误判成不支持 */
    private fun deviceSupports(type: java.util.UUID): Boolean =
        deviceTypes.isEmpty() || deviceTypes.contains(type)

    /** 试过、确实建不出来的类型 —— 之后再问就是「本机不支持」，不重复试 */
    private var failedTypes: Set<java.util.UUID> = emptySet()

    /**
     * 本次进程里**真的建成过**的类型。
     *
     * 这是区分「池满抢不到」和「本机不支持」的**唯一可靠依据** —— 光看错误码分不出来：
     * 两种情况都报 -3 / ERROR_NO_INIT。
     *
     * 实测结论（真机 + 模拟器一致）：
     * - 响度增强（LoudnessEnhancer）由 **Android 框架自己实现**（不是厂商 HAL 效果），
     *   任何机器上都能建 —— 所以它永远成功，用户看到的就是「响度一直在，不用重挂」；
     * - 低音 / 环绕 / 混响 是 OEM 的 HAL 效果，很多 ROM 只允许挂全局 session（session 0），
     *   不给 per-session 挂 —— 这种**建一万次也建不出来**，跟池满毫无关系。
     *
     * 判定规则：建成功过 → 这次失败 = 真·池满（可重挂）；
     * 从没建成过 + 失败 ≥2 次 → 本机不支持（灰置，不再提示重挂）。
     */
    private var everBuilt: Set<java.util.UUID> = emptySet()

    /**
     * 把「建成过」的事实写进磁盘。
     *
     * 为什么必须持久化：这个判定依赖的是**跨进程的历史**。模拟器/真机实测里，
     * 刚装好第一次启动 6 个效果全建成；杀掉进程再启动，同一台机器上
     * 低音/环绕/混响立刻全报 -3 —— 因为 AudioFlinger 里还挂着上一次的孤儿链，
     * 池位没回收。如果 everBuilt 只活在内存里，重启后被判成「本机不支持」，
     * 用户就再也点不亮了（而其实等池位回收就能建）。
     *
     * 只记 UUID 字符串，不记失败次数 —— 失败次数是本次进程内的事，
     * 重启后重新计数没有意义。
     */
    private fun loadEverBuilt(): Set<java.util.UUID> {
        val ctx = DiagLog.appContextOrNull() ?: return everBuilt
        val raw = ctx.getSharedPreferences("fx_history", android.content.Context.MODE_PRIVATE)
            .getStringSet("ever_built", emptySet()) ?: emptySet()
        return raw.mapNotNull { runCatching { java.util.UUID.fromString(it) }.getOrNull() }.toSet()
    }

    private fun saveEverBuilt() {
        val ctx = DiagLog.appContextOrNull() ?: return
        ctx.getSharedPreferences("fx_history", android.content.Context.MODE_PRIVATE).edit()
            .putStringSet("ever_built", everBuilt.map { it.toString() }.toSet())
            .apply()
    }

    /** 各类型连续失败次数。≥2 且从未建成过 → 判定本机不支持 */
    private val failStreak = HashMap<java.util.UUID, Int>()

    /**
     * 「本机不支持」的判定阈值 —— **跨进程累计**，不是单次会话。
     *
     * 为什么必须跨进程：AudioFlinger 的 effect 池位会被**孤儿链**长期占住。
     * 实测：进程被系统杀掉后，`dumpsys media.audio_flinger` 仍能看到
     * `Orphan Effect Chains: 6 effects for session N`，池位不回收；
     * 新进程再来建的时候一律 -3。这种「池位被上一个进程的尸体占着」的情况
     * 在单次会话里和「ROM 没实现这个 effect」长得一模一样，
     * 只有多跑几次、跨过重启，才能看出到底是机器不行还是池子没还。
     *
     * 阈值取 3：单次会话内最多再补试一次（1 次手动 + 1 次自动），
     * 连续三个会话都建不出来，才认定这台机器确实没有。
     */
    private fun failThreshold(): Int = 3

    /** everBuilt 是否已从磁盘载入（每个进程只读一次） */
    private var everBuiltLoaded = false

    /** 读取跨进程的失败次数（进程内已有值则以内存为准） */
    private fun loadFailStreak(): Map<java.util.UUID, Int> {
        val ctx = DiagLog.appContextOrNull() ?: return emptyMap()
        val prefs = ctx.getSharedPreferences("fx_history", android.content.Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith("fail_")) return@mapNotNull null
            val id = runCatching { java.util.UUID.fromString(k.removePrefix("fail_")) }.getOrNull()
            val n = (v as? Int) ?: return@mapNotNull null
            if (id != null) id to n else null
        }.toMap()
    }

    private fun saveFailStreak(type: java.util.UUID, n: Int) {
        val ctx = DiagLog.appContextOrNull() ?: return
        ctx.getSharedPreferences("fx_history", android.content.Context.MODE_PRIVATE).edit()
            .putInt("fail_$type", n).apply()
    }

    private fun clearFailStreak(type: java.util.UUID) {
        val ctx = DiagLog.appContextOrNull() ?: return
        ctx.getSharedPreferences("fx_history", android.content.Context.MODE_PRIVATE).edit()
            .remove("fail_$type").apply()
    }

    private val bassType = AudioEffect.EFFECT_TYPE_BASS_BOOST
    private val virtType = AudioEffect.EFFECT_TYPE_VIRTUALIZER
    private val loudType = AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER
    private val reverbType = AudioEffect.EFFECT_TYPE_PRESET_REVERB
    private val dynamicsType = AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING

    val bassAvailable: Boolean get() = deviceSupports(bassType) && bassType !in failedTypes
    val virtualAvailable: Boolean get() = deviceSupports(virtType) && virtType !in failedTypes
    val loudnessAvailable: Boolean get() = deviceSupports(loudType) && loudType !in failedTypes
    val reverbAvailable: Boolean get() = deviceSupports(reverbType) && reverbType !in failedTypes
    val dynamicsAvailable: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= 28 && dynamicsType !in failedTypes

    /**
     * 这一项本次是不是因为池满而没建成。
     *
     * 和 available 分开：available 回答「这台机器有没有」，
     * starved 回答「这次抢到没有」。池满时 available 仍为 true（重挂还有机会），
     * 但界面必须如实显示「资源占用」，否则用户点了「强」却什么都没发生。
     */
    val eqStarved: Boolean get() = eq == null && attachedSessionId > 0
    val bassStarved: Boolean get() = bassType in starvedTypes
    val virtualStarved: Boolean get() = virtType in starvedTypes
    val loudnessStarved: Boolean get() = loudType in starvedTypes
    val reverbStarved: Boolean get() = reverbType in starvedTypes
    val dynamicsStarved: Boolean get() = dynamicsType in starvedTypes

    /** 实例是否已经建出来（界面不关心，调试和诊断用） */
    val bassReady: Boolean get() = bass != null
    val virtualReady: Boolean get() = virt != null
    val loudnessReady: Boolean get() = loud != null
    val reverbReady: Boolean get() = reverb != null
    val dynamicsReady: Boolean get() = dynamics != null

    /** 混响预设名，下标与 reverbPreset 对应；第 0 位是「关」 */
    val reverbNames: List<String> = listOf("关", "小房间", "中房间", "大房间", "中厅", "大厅", "板式")

    fun attach(sessionId: Int) {
        if (sessionId <= 0) return
        if (isAttached(sessionId)) return
        release()
        attachedSessionId = sessionId
        failedTypes = emptySet()
        starvedTypes = emptySet()
        // 每个进程首次挂链时读一次「历史上建成过哪些」。
        // 进程内后续 attach 不必重读（还会覆盖刚发生的 markBuilt）。
        if (!everBuiltLoaded) {
            everBuilt = loadEverBuilt()
            failStreak.putAll(loadFailStreak())
            everBuiltLoaded = true
            if (everBuilt.isNotEmpty()) {
                DiagLog.log("EQ", "载入音效历史：曾建成 ${everBuilt.size} 类")
            }
        }
        // 后台释放后重新挂载：先把用户配置放回来，再建实例
        takePendingRestore()?.let { restoreSettings(it) }
        // 逐个独立 try：某一项不被芯片支持时，不能连累其他效果一起消失。
        // priority 0 被占用时退回 1 —— 有的 ROM 上 0 号优先级会直接拒绝。
        eq = runCatching { Equalizer(0, sessionId) }
            .recoverCatching { Equalizer(1, sessionId) }
            .onFailure {
                lastError = it.message ?: it.javaClass.simpleName
                Log.w(TAG, "均衡器不可用: $lastError")
            }
            .getOrNull()
        if (eq != null) markBuilt(AudioEffect.EFFECT_TYPE_EQUALIZER)
        // ⚠️ 只建均衡器，其余按需建（见 applyToDevice）。
        // 一次挂 6 个 effect 在池子小的机器上会直接把配额吃光 —— 之后所有 effect
        // 都返回 Error -3，表现就是「音效项莫名消失」。默认全关时一个都不该占位。
        if (bassStrength > 0) ensureBass()
        if (virtualStrength > 0) ensureVirtual()
        if (loudnessGain > 0) ensureLoud()
        if (reverbPreset >= 0) ensureReverb()
        if (dynamicsLevel > 0) ensureDynamics()
        applyToDevice()
        sessionVersion++
        // 设备到底给了什么，只有真机知道 —— 记进诊断日志，出问题时能直接看
        com.kmusic.apkhd.data.DiagLog.log(
            "EQ",
            "attach session=$sessionId eq=${eq != null} bass=${bass != null} " +
                "virt=${virt != null} loud=${loud != null} reverb=${reverb != null} " +
                "dyn=${dynamics != null} " +
                "bands=${runCatching { eq?.numberOfBands }.getOrNull()} " +
                "presets=${runCatching { eq?.numberOfPresets }.getOrNull()} " +
                "deviceTypes=${deviceTypes.size}"
        )
    }

    /**
     * 记一次创建失败，并区分「池子满了」和「本机没有」。
     *
     * -3 (ERROR_NO_INIT) / -6 (ERROR_NO_MEMORY) 是资源不足，重挂或重启 App 有机会恢复；
     * 其余（-4 BAD_VALUE / -5 INVALID_OPERATION）说明这个 effect 在这台机器上就是不能建，
     * 记进 failedTypes 之后不再重试，界面显示「本机不支持」。
     */
    /** 记一次「真的建成了」—— 这是后面区分池满/不支持的依据 */
    private fun markBuilt(type: java.util.UUID) {
        if (type in everBuilt) { failStreak.remove(type); return }
        everBuilt = everBuilt + type
        failStreak.remove(type)
        clearFailStreak(type)
        // 判定的历史必须能跨进程活下来（见 loadEverBuilt 的注释）
        saveEverBuilt()
        // 之前被判成「本机不支持」的，现在建成了 —— 必须撤回误判
        failedTypes = failedTypes - type
        starvedTypes = starvedTypes - type
    }

    /** 重试路径里置 true：失败只记日志，不累计、不再排补试（防自我放大） */
    @Volatile
    private var inRetryPass = false

    /** 同一类型失败处理的递归保护 —— 防止任何回灌路径把 noteFailure 套成环 */
    private val failingGuard = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>()

    private fun noteFailure(type: java.util.UUID, label: String, e: Throwable) {
        if (!failingGuard.add(type)) return
        try {
            noteFailureInner(type, label, e)
        } finally {
            failingGuard.remove(type)
        }
    }

    private fun noteFailureInner(type: java.util.UUID, label: String, e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        // 错误码本身分不出「池满」和「本机没实现」—— 两者都是 -3 (ERROR_NO_INIT)。
        // 真正能分的是历史：建成过却突然建不出来 = 池满；从来没建成过 = 本机没这功能。
        val looksLikeResource = msg.contains("-3") || msg.contains("-6")
        val builtBefore = type in everBuilt
        val streak = if (inRetryPass) (failStreak[type] ?: 0) else (failStreak[type] ?: 0) + 1
        failStreak[type] = streak
        if (!inRetryPass) saveFailStreak(type, streak)

        // 只在「同一个会话里刚失败过一次」时才补试；跨会话的失败说明池位真的没还，
        // 立刻重试只会又报一次 -3，让用户看到闪烁的「资源占用」。
        // 只有「格式不对 / 操作非法」这种**确定性与设备无关**的错误才判本机不支持。
        // -3 (ERROR_NO_INIT) 绝不能判死：实测它在池位被孤儿链占住时也会出现，
        // 同一台机器换个进程就能建成功 —— 判死等于把可恢复的问题写成永久故障。
        val hardUnsupported = !looksLikeResource
        val retryable = !hardUnsupported
        if (retryable) {
            starvedTypes = starvedTypes + type
            failedTypes = failedTypes - type
        } else {
            failedTypes = failedTypes + type
            starvedTypes = starvedTypes - type
        }
        lastError = msg
        Log.w(
            TAG,
            "${label}不可用（${if (retryable) "effect 池已满，可重挂" else "本机不支持"}）: $msg " +
                "builtBefore=$builtBefore streak=$streak"
        )
        DiagLog.log(
            "EQ",
            "$label 创建失败：$msg（${if (retryable) "可重挂" else "判定本机不支持"}，" +
                "曾建成=$builtBefore，连续失败=$streak）"
        )
        // ⚠️ 这里**绝对不能**碰 sessionVersion。
        // 它被 ViewModel 的 snapshotFlow 收集，收集者会调 pushFx() → applyToDevice()
        // → 又回来建这个效果 → 又失败 → 又自增 …… 实测一秒内能把计数刷到 3000+，
        // CPU 直接烧满。失败的界面刷新走 effectFailedTick（下面那个），
        // 它只被 Compose 读取，不会被任何逻辑回灌。
        effectFailedTick++

        // 第一次失败且从未建成过 → 立刻在后台补一次尝试。
        // 为什么：只靠用户再点一次开关来凑满「失败 2 次」是不可靠的 ——
        // 用户点一次看到「资源占用」就不会再点了，于是永远停在误导性的状态。
        // 补试成功后一切照常；补试仍失败就落进 failedTypes，界面如实写「本机不支持」。
        if (retryable && !builtBefore && streak == 1 && !inRetryPass) {
            retryOnceLater(type)
        }
    }

    /** 首次失败后的后台补试（400ms 后）。同类只排一次，避免连点开关时堆积线程。 */
    private fun retryOnceLater(type: java.util.UUID) {
        if (!pendingFirstRetry.add(type)) return
        Thread {
            try {
                Thread.sleep(400)
                inRetryPass = true
                val s = attachedSessionId
                if (s <= 0) return@Thread
                when (type) {
                    bassType -> if (bass == null) ensureBass()
                    virtType -> if (virt == null) ensureVirtual()
                    loudType -> if (loud == null) ensureLoud()
                    reverbType -> if (reverb == null) ensureReverb()
                    dynamicsType -> if (dynamics == null) ensureDynamics()
                }
            } catch (_: InterruptedException) {
            } finally {
                inRetryPass = false
                pendingFirstRetry.remove(type)
            }
        }.apply { isDaemon = true; name = "fx-firstretry" }.start()
    }

    private val pendingFirstRetry = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>()

    /** 低音增强实例：用到才建 */
    fun ensureBass(): BassBoost? {
        if (bass != null) return bass
        // 只拦「已判定本机不支持」的；starvedTypes 只是「这次没抢到」，必须允许再试 ——
        // 拦住它会导致失败次数永远停在 1，永远到不了「判定不支持」那一步，
        // 于是一直显示「资源占用」，用户点多少次都没用。
        if (attachedSessionId <= 0 || bassType in failedTypes || !bassAvailable) return null
        bass = runCatching { BassBoost(0, attachedSessionId) }
            .onFailure { noteFailure(bassType, "低音增强", it) }
            .getOrNull()
        if (bass != null) markBuilt(bassType)
        return bass
    }

    fun ensureVirtual(): Virtualizer? {
        if (virt != null) return virt
        if (attachedSessionId <= 0 || virtType in failedTypes || !virtualAvailable) return null
        virt = runCatching { Virtualizer(0, attachedSessionId) }
            .onFailure { noteFailure(virtType, "环绕", it) }
            .getOrNull()
        if (virt != null) markBuilt(virtType)
        return virt
    }

    fun ensureLoud(): LoudnessEnhancer? {
        if (loud != null) return loud
        if (attachedSessionId <= 0 || loudType in failedTypes || !loudnessAvailable) return null
        loud = runCatching { LoudnessEnhancer(attachedSessionId) }
            .onFailure { noteFailure(loudType, "响度增强", it) }
            .getOrNull()
        if (loud != null) markBuilt(loudType)
        return loud
    }

    fun ensureReverb(): PresetReverb? {
        if (reverb != null) return reverb
        if (attachedSessionId <= 0 || reverbType in failedTypes || !reverbAvailable) return null
        reverb = runCatching { PresetReverb(0, attachedSessionId) }
            .onFailure { noteFailure(reverbType, "混响", it) }
            .getOrNull()
        if (reverb != null) markBuilt(reverbType)
        return reverb
    }

    fun ensureDynamics(): DynamicsProcessing? {
        if (dynamics != null) return dynamics
        if (attachedSessionId <= 0 || dynamicsType in failedTypes || !dynamicsAvailable) return null
        dynamics = runCatching { buildDynamics(attachedSessionId) }
            .onFailure { noteFailure(dynamicsType, "动态范围", it) }
            .getOrNull()
        if (dynamics != null) markBuilt(dynamicsType)
        return dynamics
    }

    /**
     * 挂载失败时的自动退避重试（后台线程，不阻塞调用方）。
     *
     * 场景：后台释放后回前台、或某次 attach 正好撞上别的 App 抢占池位。
     * 用户不该知道「池位」这回事 —— 抢得回来自己抢，抢不回来就按本机不支持处理。
     *
     * 重试序列 400ms / 1200ms：第一次等池位回收，第二次给系统更大的缓冲。
     * 只在**均衡器没建出来**时才重试（低音/环绕/混响建不出来是 ROM 限制，重试无意义）。
     */
    fun scheduleAutoRetry(sessionId: Int) {
        if (sessionId <= 0 || eq != null) return
        if (autoRetryPending) return
        autoRetryPending = true
        // 重试途中产生的失败不累计进 failStreak —— 否则一次点击就可能把计数推到上千，
        // 从此每次启动都在补试，越补越多，成了自我放大的循环。
        Thread {
            try {
                for ((i, delay) in longArrayOf(600L).withIndex()) {
                    Thread.sleep(delay)
                    if (eq != null) return@Thread      // 期间已被别的路径挂上了
                    val s = attachedSessionId
                    if (s <= 0) return@Thread
                    inRetryPass = true
                    attach(s)
                    inRetryPass = false
                    if (eq != null) {
                        DiagLog.log("EQ", "自动重试第 ${i + 1} 次成功（等待 ${delay}ms）")
                        return@Thread
                    }
                    attachedSessionId = 0
                }
                DiagLog.log("EQ", "自动重试两次仍失败 → 保持当前状态")
            } catch (_: InterruptedException) {
                // 进程退出，正常忽略
            } finally {
                inRetryPass = false
                autoRetryPending = false
            }
        }.apply { isDaemon = true; name = "fx-autoretry" }.start()
    }

    @Volatile
    private var autoRetryPending = false

    /**
     * 重挂整条音效链：先把自己占的池位全还回去，再按当前设置重建。
     *
     * 给「池子被别的进程/孤儿链占满」这种情况一个自救入口 ——
     * 用户不用杀 App 重启，点一下就能把抢不回来的效果抢回来。
     */
    fun reattach(): Boolean {
        val s = attachedSessionId
        if (s <= 0) return false
        reattachCount++
        // ⚠️ release() 只是把 handle 交还给框架，AudioFlinger 真正回收池位是**异步**的。
        // 以前这里是 release() 紧接着 attach() —— 几乎必然抢不到，又报一次 -3，
        // 于是显示「资源占用」，用户点重挂觉得「没作用」。这里必须等一拍。
        runCatching { release() }
        DiagLog.log("EQ", "reattach #$reattachCount session=$s 释放旧链，等待池位回收")
        val ok = waitAndAttach(s, listOf(0L, 250L, 500L))
        DiagLog.log(
            "EQ", "reattach #$reattachCount session=$s 结果 eq=${eq != null} " +
                "bass=${bass != null} virt=${virt != null} loud=${loud != null} " +
                "reverb=${reverb != null} dyn=${dynamics != null} starved=${starvedTypes.size}"
        )
        return ok
    }

    /**
     * 按退避序列重试挂载，直到均衡器建出来（或次数用尽）。
     *
     * 为什么以均衡器为准：它是唯一在所有设备上都实现的 per-session 效果
     * （AOSP 自带），它建出来就说明这条 session 的链是通的；
     * 低音/环绕/混响 建不出来多半是 ROM 不给挂，等再久也没用（走 failedTypes 逻辑）。
     *
     * @param delays 每次尝试前的等待毫秒数；第一项是 0（立即试一次）
     */
    private fun waitAndAttach(sessionId: Int, delays: List<Long>): Boolean {
        for ((i, delay) in delays.withIndex()) {
            if (delay > 0) {
                runCatching { Thread.sleep(delay) }
            }
            attach(sessionId)
            if (eq != null) {
                if (i > 0) DiagLog.log("EQ", "重挂第 ${i + 1} 次尝试成功（等待 ${delay}ms 后）")
                return true
            }
            // 失败要清掉 attachedSessionId，否则下一次 attach 会以为「已经挂过了」直接返回
            attachedSessionId = 0
        }
        return false
    }

    /**
     * 动态范围处理（API 28+）。
     *
     * 结构：3 段压缩 → 末端限幅。
     * 压缩把「小声的地方抬起来、大声的地方压住」，限幅兜底防削波 ——
     * 车机环境噪音大，这一项比均衡器更直接地改善「听不清」。
     *
     * 档位只给四挡，不做旋钮：轻 / 中 / 强 对应不同的阈值与压缩比。
     * 设备不提供这个 effect 时返回 null，界面整块隐藏。
     */
    private fun buildDynamics(sessionId: Int): DynamicsProcessing? {
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        val channels = 2
        val bands = 3
        // 变体不是随便挑的：HAL 只实现其中一个时，另一个会抛 "variant N not available for creation"
        // （MuMu 就是只有 0）。所以两个都试，谁成用谁。
        val variants = intArrayOf(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION
        )
        for (variant in variants) {
            val built = runCatching {
                val builder = DynamicsProcessing.Config.Builder(
                    variant,
                    channels,
                    /* preEqInUse = */ false, 0,
                    /* mbcInUse = */ true, bands,
                    /* postEqInUse = */ false, 0,
                    /* limiterInUse = */ true
                )
                // ⚠️ 参数必须在 build() 之前进 Builder：Config 构造时做校验，
                // 事后用 Mbc.setBand 改是改不进去的（也不该改）。
                fillDynamics(builder, bands, dynamicsLevel)
                val dp = DynamicsProcessing(0, sessionId, builder.build())
                dp.enabled = dynamicsLevel > 0
                dp
            }.onFailure {
                Log.w(TAG, "动态范围 variant=$variant 创建失败: ${it.message}")
            }.getOrNull()
            if (built != null) {
                DiagLog.log("EQ", "DynamicsProcessing 已创建 variant=$variant bands=$bands level=$dynamicsLevel")
                return built
            }
        }
        return null
    }

    /**
     * 把某一档参数写进 Config.Builder。
     *
     * 档位只动「阈值 + 压缩比」，分频点/时间常数固定 —— 三个旋钮联动，
     * 用户多调只会调出破音。
     */
    private fun fillDynamics(
        builder: DynamicsProcessing.Config.Builder,
        bands: Int,
        level: Int
    ) {
        val threshold = when (level) {
            1 -> -18f
            2 -> -24f
            else -> -30f
        }
        val ratio = when (level) {
            1 -> 1.8f
            2 -> 2.5f
            else -> 3.5f
        }
        // 截止频率必须严格递增；最后一段的上界由实现按 Nyquist 处理
        val cutoffs = floatArrayOf(200f, 2000f, 8000f)
        val mbc = DynamicsProcessing.Mbc(/* inUse = */ true, /* enabled = */ true, bands)
        for (b in 0 until bands) {
            mbc.setBand(
                b,
                DynamicsProcessing.MbcBand(
                    /* inUse = */ true,
                    /* cutoffFrequency = */ cutoffs[b],
                    /* attackTime = */ 15f,
                    /* releaseTime = */ 200f,
                    /* ratio = */ ratio,
                    /* threshold = */ threshold,
                    /* kneeWidth = */ 6f,
                    /* noiseGateThreshold = */ -70f,
                    /* expanderRatio = */ 1f,
                    /* preGain = */ 0f,
                    /* postGain = */ 0f
                )
            )
        }
        builder.setMbcAllChannelsTo(mbc)
        builder.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                /* inUse = */ true,
                /* enabled = */ true,
                /* linkGroup = */ 0,
                /* attackTime = */ 1f,
                /* releaseTime = */ 60f,
                /* ratio = */ 10f,
                /* threshold = */ -1f,
                /* postGain = */ 0f
            )
        )
    }

    /**
     * 0 关 / 1 轻 / 2 中 / 3 强。
     *
     * 档位切换要重建 effect：DynamicsProcessing 的 Config 是构造期固定的，
     * 运行中改不了（没有 setConfig）。重建代价很小（毫秒级），换档不是高频操作。
     */
    fun configureDynamics(level: Int) {
        val v = level.coerceIn(0, 3)
        if (v == dynamicsLevel && (dynamics != null || v == 0)) return
        dynamicsLevel = v
        val session = attachedSessionId
        if (session <= 0) return
        runCatching { dynamics?.release() }
        dynamics = null
        // 0 = 关：不建实例，池位直接还回去
        if (v > 0 && dynamicsAvailable) {
            dynamics = runCatching { buildDynamics(session) }
                .onFailure { noteFailure(dynamicsType, "动态范围", it) }
                .getOrNull()
        }
        sessionVersion++
    }

    fun configure(enabled: Boolean, presetIndex: Int) {
        this.enabled = enabled
        this.presetIndex = presetIndex
        // 选预设 = 放弃手动曲线
        if (presetIndex != this.presetIndex || presetIndex >= 0) {
            customBands = false
            manualLevels = emptyMap()
        }
        applyToDevice()
    }

    /** 一次设置四个增强参数。0 = 关闭该项 */
    fun configureEnhanced(bass: Int, virtual: Int, loudness: Int, reverb: Int) {
        this.bassStrength = bass.coerceIn(0, 1000)
        this.virtualStrength = virtual.coerceIn(0, 1000)
        this.loudnessGain = loudness.coerceIn(0, 1500)
        this.reverbPreset = reverb.coerceIn(-1, 6)
        applyToDevice()
    }

    private fun applyToDevice() {
        eq?.let { e ->
            runCatching {
                // ⚠️ DSD 播放时自动旁路均衡器。
                //
                // 原因：DSD 的码率极高（DSD64 就是 5.6 Mbps），
                // 自写的抽取链已经占满 CPU，再挂一条 EQ 会把音频线程挤爆（卡顿/爆音）；
                // 而且 DSD 频谱延伸到 100 kHz 以上，EQ 这 5 个频点对它的实际影响很有限。
                //
                // 用户配置**原样保留**（enabled / presetIndex / manualLevels 都不动），
                // 切回普通格式立刻恢复 —— 这里只是把设备上的开关临时按掉。
                val eqOn = enabled && !eqBypassedByDsd
                e.enabled = eqOn
                if (eqOn) {
                    if (customBands) {
                        manualLevels.forEach { (band, lv) ->
                            e.setBandLevel(band.toShort(), lv.toShort())
                        }
                    } else if (e.numberOfPresets > 0) {
                        val idx = presetIndex.coerceIn(0, e.numberOfPresets - 1)
                        e.usePreset(idx.toShort())
                    }
                }
            }.onFailure { Log.w(TAG, "应用预设失败: ${it.message}") }
        }

        // 强度为 0 → 直接把实例还给系统。
        // 「关」的项不该继续占着 effect 池：池位是全局稀缺资源，
        // 占着不放会让别的效果（乃至别的 App）建不出来。
        bass?.let { b ->
            if (bassStrength <= 0) {
                runCatching { b.release() }
                bass = null
            } else {
                runCatching {
                    b.enabled = true
                    b.setStrength(bassStrength.toShort())
                }.onFailure { Log.w(TAG, "低音增强应用失败: ${it.message}") }
            }
        }
        if (bassStrength > 0 && bass == null) {
            ensureBass()?.let { b ->
                runCatching {
                    b.enabled = true
                    b.setStrength(bassStrength.toShort())
                }.onFailure { Log.w(TAG, "低音增强应用失败: ${it.message}") }
            }
        }

        virt?.let { v ->
            if (virtualStrength <= 0) {
                runCatching { v.release() }
                virt = null
            } else {
                runCatching {
                    v.enabled = true
                    v.setStrength(virtualStrength.toShort())
                }.onFailure { Log.w(TAG, "环绕应用失败: ${it.message}") }
            }
        }
        if (virtualStrength > 0 && virt == null) {
            ensureVirtual()?.let { v ->
                runCatching {
                    v.enabled = true
                    v.setStrength(virtualStrength.toShort())
                }.onFailure { Log.w(TAG, "环绕应用失败: ${it.message}") }
            }
        }

        loud?.let { l ->
            if (loudnessGain <= 0) {
                runCatching { l.release() }
                loud = null
            } else {
                runCatching {
                    l.enabled = true
                    l.setTargetGain(loudnessGain)
                }.onFailure { Log.w(TAG, "响度增强应用失败: ${it.message}") }
            }
        }
        if (loudnessGain > 0 && loud == null) {
            ensureLoud()?.let { l ->
                runCatching {
                    l.enabled = true
                    l.setTargetGain(loudnessGain)
                }.onFailure { Log.w(TAG, "响度增强应用失败: ${it.message}") }
            }
        }

        reverb?.let { r ->
            if (reverbPreset < 0) {
                runCatching { r.release() }
                reverb = null
            } else {
                runCatching {
                    r.preset = reverbPreset.toShort()
                    r.enabled = true
                }.onFailure { Log.w(TAG, "混响应用失败: ${it.message}") }
            }
        }
        if (reverbPreset >= 0 && reverb == null) {
            ensureReverb()?.let { r ->
                runCatching {
                    r.preset = reverbPreset.toShort()
                    r.enabled = true
                }.onFailure { Log.w(TAG, "混响应用失败: ${it.message}") }
            }
        }

        // 动态范围由 configureDynamics 自己管实例（重建代价高，档位间切换不重建）
    }

    fun release() {
        attachedSessionId = 0
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        runCatching { virt?.release() }
        runCatching { loud?.release() }
        runCatching { reverb?.release() }
        runCatching { dynamics?.release() }
        eq = null
        bass = null
        virt = null
        loud = null
        reverb = null
        dynamics = null
        // failedTypes 不在这里清：那是「这台机器没有」的结论，跨 session 有效；
        // starvedTypes 要清，下次挂载重新判断这次是否还缺池位。
        starvedTypes = emptySet()
    }
}