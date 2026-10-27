package com.kmusic.apkhd.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kmusic.apkhd.data.DiagLog

/**
 * 系统音效链。全部挂在**播放器真实的 audio session** 上，而不是全局输出混音（0 号 session）——
 * 后者会作用到设备上所有声音，包括通知音和别的 App。
 *
 * ## 为什么只有一个实例（v2.3.2 起）
 *
 * 真机日志（Xiaomi 2203121C / Android 15）证明：某些 ROM 的 per-session effect 池
 * **只剩 1 个空位**。旧实现里均衡器、低音、响度各占 1 位，而均衡器在建链顺序里排第一 ——
 * 于是每次都是 EQ 抢走唯一空位，其余全部 `Error: -3` 饿死，
 * 表现为「一开均衡器，增强全失效」。
 *
 * 现在三项合并进一个 [DynamicsProcessing]，池位只算 1 个：
 * - **均衡器** → preEq 五段（60 / 230 / 910 / 3.6k / 14k Hz）
 * - **低音增强** → preEq 低频段叠加 60Hz +9dB / 230Hz +4dB 的固定曲线（满档）
 * - **响度增强** → 前级 inputGain 抬高 + limiter 兜底防削波
 *
 * 三项全关 → 实例释放，池位归还（0 项）；任一项开着 → 1 项。
 *
 * API 26/27 没有 [DynamicsProcessing]，或者 DP 在这台机器上确实建不出来（非资源类错误）时，
 * 自动回退到「均衡器 + 低音 + 响度」三条独立链，界面完全一致，只是占用数会显示 3。
 */
object AudioEffectHolder {

    private const val TAG = "MoyinEQ"

    /** 合并实例的五个频段（Hz），与旧设备均衡器的 5 段一致，界面不用改 */
    private val FREQS = intArrayOf(60, 230, 910, 3600, 14000)

    private const val BAND_COUNT = 5

    /** 低音增强满档时叠加到前两段的增益（mB） */
    private val BASS_CURVE = intArrayOf(900, 400, 0, 0, 0)

    /** 增益上下限（mB），和旧设备均衡器一致 */
    private const val LEVEL_MIN = -1500
    private const val LEVEL_MAX = 1500

    /**
     * 内置预设（5 段增益，mB）。
     *
     * 合并进 DP 之后没有芯片原厂的 usePreset() 了（DP 根本没有预设 API），
     * 预设改成自存的增益表 —— 音色接近，但不是原厂曲线。
     */
    private val PRESET_NAMES = listOf("原声", "流行", "摇滚", "古典", "人声", "低音", "高音")
    private val PRESETS = arrayOf(
        intArrayOf(0, 0, 0, 0, 0),
        intArrayOf(-100, 200, 400, 200, -100),
        intArrayOf(400, 150, -100, 200, 400),
        intArrayOf(300, 100, -100, 150, 300),
        intArrayOf(-200, 100, 500, 300, -100),
        intArrayOf(600, 300, 0, -100, -200),
        intArrayOf(-200, -100, 0, 300, 600)
    )

    /** 音效链的用户配置快照（后台释放 → 重建时恢复） */
    data class FxSettings(
        val enabled: Boolean,
        val presetIndex: Int,
        val customBands: Boolean,
        val manualLevels: Map<Int, Int>,
        val bassStrength: Int,
        val loudnessGain: Int
    )

    // ── 实例 ──
    /** 合并实例：均衡器 + 低音 + 响度共用一个池位 */
    private var dp: DynamicsProcessing? = null

    // 回退链（API 26/27，或 DP 在本机确实不可用）
    private var legacyEq: Equalizer? = null
    private var legacyBass: BassBoost? = null
    private var legacyLoud: LoudnessEnhancer? = null

    /** DP 在本机被判定为「确实建不出来」→ 本次进程改用回退链 */
    @Volatile
    private var dpUnsupported = false

    /** 是否走合并实例（API 28+ 且 DP 没被判死） */
    val unifiedMode: Boolean get() = Build.VERSION.SDK_INT >= 28 && !dpUnsupported

    /**
     * 音效链挂载版本号。播放器建出 audio session 才能创建这些效果，
     * 而那一刻发生在 Service 里、ViewModel 看不到 —— 用这个计数器让界面知道「可以刷新了」。
     */
    var sessionVersion by mutableIntStateOf(0)
        private set

    /**
     * 失败导致的界面刷新信号。
     *
     * 单独开一个而不是复用 sessionVersion：那个有订阅者会回灌
     * （refresh → configure → 又建实例），失败时自增它会变成无限递归。
     */
    var effectFailedTick by mutableIntStateOf(0)
        private set

    /** 当前已挂载的 audio session；0 表示还没挂 */
    private var attachedSessionId: Int = 0

    /** 最近一次挂载失败的原因，界面可以据此告诉用户「为什么没有调节」 */
    var lastError: String? = null
        private set

    /** 本次挂载里因为池满（ERROR_NO_INIT / NO_MEMORY）挂不上的类型 */
    var starvedTypes: Set<java.util.UUID> = emptySet()
        private set

    /** 已经重挂过几次 —— 池满时连续重挂没有意义 */
    var reattachCount: Int = 0
        private set

    /**
     * 这个 session 已经挂过了吗 —— 避免每次 READY 都把整条链拆了重建。
     *
     * ⚠️ 判据是「认领过哪个 session」，**不是**「实例还在不在」。
     */
    fun isAttached(sessionId: Int): Boolean = sessionId > 0 && attachedSessionId == sessionId

    /** 这个 session 认领过了吗（与「实例还在不在」无关） */
    val attached: Boolean get() = attachedSessionId > 0

    // ── 用户配置 ──
    var enabled: Boolean = false
        private set
    var presetIndex: Int = 0
        private set

    /** 用户手动拖过频段 → true。预设表不再覆盖它。 */
    var customBands: Boolean = false
        private set

    /** band 下标 → 增益（mB），仅 customBands 时生效 */
    private var manualLevels: Map<Int, Int> = emptyMap()

    /** 低音增强强度，0..1000，0 视为关闭 */
    var bassStrength: Int = 0
        private set

    /** 响度增强增益，单位 mB（1000 mB = 10 dB），0 视为关闭 */
    var loudnessGain: Int = 0
        private set

    /** 均衡器此刻是否**应该**开着（关掉就不建、DSD 播放中也不建） */
    fun eqWanted(): Boolean = enabled && !bypassedByDsd

    /** 三项里有没有任何一项需要占池位 */
    fun fxWanted(): Boolean = (enabled || bassStrength > 0 || loudnessGain > 0) && !bypassedByDsd

    /**
     * 空闲释放后抑制重建。
     *
     * 暂停 15 秒拆链之后，设置流回灌不该立刻又把池位抢回来 ——
     * 等真正开始播放时 attach() 统一重建。
     */
    @Volatile
    private var idleReleased: Boolean = false

    /** 本 App 此刻实际占用的系统音效池位数 */
    val heldCount: Int
        get() = if (dp != null) 1
        else listOfNotNull(legacyEq, legacyBass, legacyLoud).size

    /**
     * 用户配置里「有几项是开着的」。
     *
     * 和 [heldCount] 分开：暂停 15 秒后池位已归还，此时 heldCount=0
     * 但用户明明开着低音 —— 界面若把 0 解释成「全部关闭」就是撒谎。
     */
    val wantedCount: Int
        get() = listOf(eqWanted(), bassStrength > 0, loudnessGain > 0).count { it }

    /**
     * 池位占用变化信号 —— **只给界面读**，不参与任何逻辑。
     *
     * 不能复用 [sessionVersion]：那个被 ViewModel 收着，自增会回灌
     * refresh → configure → 又自增（实测能把计数刷到 3000+，CPU 烧满）。
     */
    var poolTick by mutableIntStateOf(0)
        private set

    /** 记录上一次报给界面的占用数，只在真的变了才发信号 */
    private var lastHeld = -1

    private fun notePoolChanged() {
        val n = heldCount
        if (n == lastHeld) return
        lastHeld = n
        poolTick++
    }

    // ── 后台自动释放 ──
    //
    // 为什么需要：进程被系统强杀时 onDestroy 不跑，AudioEffect 的 native handle
    // 永远不 release，AudioFlinger 里就留下一条 clients=0 的**孤儿链** ——
    // 它永久占着全局 effect 池，而且**不回收**。
    // 注意：**正在播歌时绝不拆**。

    /** 音效链是否已被后台策略拆除（下次起播需要重建） */
    @Volatile
    var releasedForBackground: Boolean = false
        private set

    /** 标记「已重建过」，避免回前台时反复走重建分支 */
    fun clearReleasedFlag() {
        releasedForBackground = false
    }

    /** 用户在界面上动了音效时回调服务，让服务重新评估「要不要释放」 */
    @Volatile
    var onUserConfigChanged: (() -> Unit)? = null

    /**
     * 用户**主动**动了音效时调这里（界面上的开关 / 滑块）。
     *
     * - 自动回灌（冷启读 DataStore）不该把刚释放的链抢回来；
     * - 用户动手就必须听得见变化 —— 立刻解除空闲抑制、按需重建。
     */
    fun onUserEdited() {
        idleReleased = false
        // 丢掉「后台释放时存的旧快照」，否则下一次 attach 会 restoreSettings(旧值)
        // 把用户刚调好的东西覆盖回去 —— 表现为「调完立刻弹回原样」。
        pendingRestore = null
        ensureUnified(force = true)
        applyToDevice()
        sessionVersion++
        runCatching { onUserConfigChanged?.invoke() }
    }

    /** 记录「用户配置」，供重建时恢复 */
    fun snapshotSettings(): FxSettings = FxSettings(
        enabled = enabled,
        presetIndex = presetIndex,
        customBands = customBands,
        manualLevels = manualLevels,
        bassStrength = bassStrength,
        loudnessGain = loudnessGain
    )

    /** 恢复用户配置（重建实例后调用） */
    fun restoreSettings(s: FxSettings) {
        enabled = s.enabled
        presetIndex = s.presetIndex
        customBands = s.customBands
        manualLevels = s.manualLevels
        bassStrength = s.bassStrength
        loudnessGain = s.loudnessGain
    }

    /**
     * 后台空闲释放：拆掉整条音效链，把池位还给系统。
     *
     * @return true 表示确实拆了
     */
    fun releaseForBackground(sessionId: Int): Boolean {
        if (attachedSessionId <= 0) return false
        // 记录配置 —— 重建时要用
        val snapshot = snapshotSettings()
        // ⚠️ 必须连同 session 认领一起清掉：否则下次 attach 会以为「已经挂过了」直接返回，
        // 链被拆了却再也建不回来（音效静默失效）。
        release()
        releasedForBackground = true
        idleReleased = true
        pendingRestore = snapshot
        DiagLog.log(
            "EQ",
            "后台空闲释放音效链（session=$sessionId），已保存配置 " +
                "eq=${snapshot.enabled} bass=${snapshot.bassStrength} loud=${snapshot.loudnessGain}"
        )
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

    // ── DSD 旁路 ──

    /**
     * 当前是否因为「正在播 DSD」而旁路了整条音效链。
     *
     * v2.3.2 起三项合并进同一个实例，没法只关其中一项，
     * 所以 DSD 播放时**三项一起停**（配置原样保留，切回普通格式立刻恢复）。
     */
    @Volatile
    var bypassedByDsd: Boolean = false
        private set

    /**
     * 设置 DSD 旁路状态。
     *
     * 直接释放实例（而不是只写 enabled=false）—— 池位还回去，
     * 否则「已旁路」这一项仍然白占着唯一空位。
     */
    fun setDsdBypass(bypass: Boolean) {
        if (bypassedByDsd == bypass) return
        bypassedByDsd = bypass
        if (bypass) {
            if (attachedSessionId > 0 && heldCount > 0) {
                releaseEffects()
                DiagLog.log("EQ", "DSD 播放 → 释放统一音效（均衡器/低音/响度一起暂停，池位归还）")
            }
        } else {
            if (fxWanted() && attachedSessionId > 0) {
                ensureUnified(force = true)
                applyToDevice()
            }
            DiagLog.log("EQ", "DSD 结束 → 恢复统一音效")
        }
        sessionVersion++
    }

    // ── 频段 ──

    /** 频段数量：合并模式固定 5 段；回退模式读设备 */
    val bandCount: Int
        get() = if (unifiedMode) BAND_COUNT
        else runCatching { legacyEq?.numberOfBands?.toInt() }.getOrNull()
            ?: cachedFreqs().size.takeIf { it > 0 } ?: BAND_COUNT

    /** 可调范围，元素为 [最小 mB, 最大 mB] */
    fun bandRange(): IntArray = if (unifiedMode) {
        intArrayOf(LEVEL_MIN, LEVEL_MAX)
    } else {
        runCatching {
            legacyEq?.bandLevelRange?.let { intArrayOf(it[0].toInt(), it[1].toInt()) }
        }.getOrNull() ?: intArrayOf(LEVEL_MIN, LEVEL_MAX)
    }

    /** 缓存里的频段中心频率（Hz）；没缓存过就是空表 */
    private fun cachedFreqs(): IntArray = eqCachePrefs()
        ?.getString("eq_freqs", null)
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.toIntArray()
        ?: IntArray(0)

    /**
     * 各频段中心频率（**Hz**），下标与 band 对应。
     *
     * 合并模式用固定 5 段；回退模式读设备 —— [Equalizer.getCenterFreq] 返回 **mHz**，要除以 1000。
     */
    fun bandFreqs(): IntArray = if (unifiedMode) {
        FREQS.copyOf()
    } else {
        runCatching {
            val e = legacyEq ?: return cachedFreqs()
            IntArray(e.numberOfBands.toInt()) { (e.getCenterFreq(it.toShort()).toInt() + 500) / 1000 }
        }.getOrDefault(cachedFreqs())
    }

    /**
     * 各频段当前增益（mB）—— 返回的是**均衡器曲线**，不含低音叠加。
     *
     * 没有手动曲线时返回预设表的曲线，这样关掉均衡器之后滑块也不会突然塌成 0 段。
     */
    fun bandLevels(): IntArray {
        val n = bandCount
        return if (customBands) {
            // 第一次从预设切到自定义时，manualLevels 只有刚拖的那一段。
            // 直接 `?: 0` 会把其余四段打成 0 dB —— 用户只想加一点 60Hz，
            // 结果整条曲线塌成一条斜线（实测过）。所以缺的段回落到当前预设值。
            val table = PRESETS.getOrElse(presetIndex) { PRESETS[0] }
            IntArray(n) { b -> manualLevels[b] ?: table.getOrElse(b) { 0 } }
        } else {
            val table = PRESETS.getOrElse(presetIndex) { PRESETS[0] }
            IntArray(n) { b -> table.getOrElse(b) { 0 } }
        }
    }

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
        // 用户拖了滑块就要**听得到**变化：实例不存在（关着 / 刚被空闲释放）就地建一个
        idleReleased = false
        ensureUnified(force = true)
        applyToDevice()
        DiagLog.log("EQ", "band[$band] = ${v}mB")
    }

    /** 回到预设模式，丢掉已自定义频段 */
    fun usePresetMode(index: Int) {
        presetIndex = index.coerceIn(0, PRESETS.size - 1)
        customBands = false
        manualLevels = emptyMap()
        // 选预设隐含「我要用均衡器」：不建实例的话选了没效果
        idleReleased = false
        enabled = true
        ensureUnified(force = true)
        applyToDevice()
    }

    /** 预设名（内置 7 套，与系统预设无关） */
    fun presetNames(): List<String> = PRESET_NAMES

    /**
     * 老版本存的 eqPreset 是**设备预设下标**（0..9），现在换成了内置表。
     *
     * 只迁移一次：拿 fx_history 里缓存的旧预设名做名字匹配（摇滚 → 摇滚），
     * 认不出来就落到「原声」。迁移完写标记，之后不再动用户的选择。
     */
    fun migratePresetIndex(oldIndex: Int): Int {
        val prefs = eqCachePrefs() ?: return oldIndex.coerceIn(0, PRESETS.size - 1)
        if (prefs.getBoolean("preset_migrated_232", false)) {
            return oldIndex.coerceIn(0, PRESETS.size - 1)
        }
        val oldNames = prefs.getString("eq_presets", null)
            ?.split('\u0001')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val rawName = oldNames.getOrNull(oldIndex)?.trim().orEmpty()
        val mapped = PRESET_NAMES.indexOfFirst { it == rawName }
            .takeIf { it >= 0 }
            ?: when {
                rawName.contains("流行") || rawName.contains("pop", true) -> 1
                rawName.contains("摇滚") || rawName.contains("rock", true) -> 2
                rawName.contains("古典") || rawName.contains("classical", true) -> 3
                rawName.contains("人声") || rawName.contains("vocal", true) -> 4
                rawName.contains("低音") || rawName.contains("bass", true) -> 5
                rawName.contains("高音") || rawName.contains("treble", true) -> 6
                else -> 0
            }
        prefs.edit().putBoolean("preset_migrated_232", true).apply()
        DiagLog.log("EQ", "预设迁移：设备预设 #$oldIndex（${rawName.ifBlank { "未知" }}）→ 内置 #$mapped")
        return mapped
    }

    // ── 设备能力 ──

    /**
     * 设备固件里装了哪些 effect。
     *
     * queryEffects() 只读系统已注册的效果库清单，**不占 effect 池**。
     */
    private val deviceTypes: Set<java.util.UUID> by lazy {
        runCatching {
            AudioEffect.queryEffects()?.filterNotNull()?.map { it.type }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private fun deviceSupports(type: java.util.UUID): Boolean =
        deviceTypes.isEmpty() || deviceTypes.contains(type)

    /** 试过、确实建不出来的类型 —— 之后再问就是「本机不支持」，不重复试 */
    private var failedTypes: Set<java.util.UUID> = emptySet()

    /** 本次进程里**真的建成过**的类型（区分「池满抢不到」和「本机不支持」的唯一依据） */
    private var everBuilt: Set<java.util.UUID> = emptySet()

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

    /** 各类型连续失败次数 */
    private val failStreak = HashMap<java.util.UUID, Int>()

    /** everBuilt 是否已从磁盘载入（每个进程只读一次） */
    private var everBuiltLoaded = false

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

    private val dpType = AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
    private val bassType = AudioEffect.EFFECT_TYPE_BASS_BOOST
    private val loudType = AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER

    /**
     * 低音 / 响度是否可用。
     *
     * 合并模式下一律 true（它们不是独立 effect 了，只要 DP 建得出来就行）；
     * 回退模式才逐项看设备清单。
     */
    val bassAvailable: Boolean
        get() = if (unifiedMode) true else deviceSupports(bassType) && bassType !in failedTypes
    val loudnessAvailable: Boolean
        get() = if (unifiedMode) true else deviceSupports(loudType) && loudType !in failedTypes

    /**
     * 「想用但一个实例都没挂上」—— 界面显示「资源占用」而不是「本机不支持」。
     *
     * 三项共用同一个实例，所以任何一项想用而实例为空，就是同一个占用问题。
     */
    private val starvedNow: Boolean
        get() = heldCount == 0 && attachedSessionId > 0 && !idleReleased && !bypassedByDsd

    val eqStarved: Boolean get() = eqWanted() && starvedNow
    val bassStarved: Boolean
        get() = bassStrength > 0 && if (unifiedMode) starvedNow else bassType in starvedTypes
    val loudnessStarved: Boolean
        get() = loudnessGain > 0 && if (unifiedMode) starvedNow else loudType in starvedTypes

    /** 实例是否已经建出来（调试与诊断用） */
    val unifiedReady: Boolean get() = dp != null

    // ── 挂载 ──

    fun attach(sessionId: Int) {
        if (sessionId <= 0) return
        if (isAttached(sessionId)) return
        release()
        attachedSessionId = sessionId
        // 真正起播了：解除「空闲抑制」，否则暂停 15 秒拆过一次就再也建不回来
        releasedForBackground = false
        idleReleased = false
        failedTypes = emptySet()
        starvedTypes = emptySet()
        // 新 session = 新机会：HAL 上一轮抽风（或上一台 UID 池位没退干净）不该判死整台机器。
        // 真不支持 DP 的机型也只是每个 session 多试一次，不会变成重试风暴。
        if (!inRetryPass) {
            dpBuildFails = 0
            dpUnsupported = false
        }
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
        // ⚠️ 只在这里建一次。以前 attach 建一次 + applyToDevice 又建一次，
        // 失败时再叠加 reattach 三次和 sessionVersion 回灌，5 秒能打出 164 条创建失败。
        ensureUnified(force = false)
        if (!unifiedMode && eqWanted()) primeLegacyEqCapabilities(sessionId)
        applyToDevice()
        sessionVersion++
        DiagLog.log(
            "EQ",
            "attach session=$sessionId mode=${if (unifiedMode) "unified" else "legacy"} " +
                "dp=${dp != null} eq=$enabled bass=$bassStrength loud=$loudnessGain " +
                "held=$heldCount wanted=$wantedCount deviceTypes=${deviceTypes.size}"
        )
        // 想用却一个都没挂上 → 排**一次**退避重试（4.5 秒后）。
        // 重试内部 inRetryPass=true，不会在这里再排一次；也不会像旧实现那样连试三次。
        if (!inRetryPass && heldCount == 0 && fxWanted()) scheduleAutoRetry(sessionId)
    }

    /**
     * 建实例（合并模式一个 DP；回退模式按需三条链）。
     *
     * @param force 用户主动操作时绕过冷却 —— 否则刚失败过一次，用户再点开关会「点了没反应」
     */
    fun ensureUnified(force: Boolean = false): Boolean {
        if (attachedSessionId <= 0) return false
        if (idleReleased) return false
        if (bypassedByDsd) return false
        if (!fxWanted()) {
            // 三项都关了 → 立刻释放实例，池位归零（这是这一版的核心承诺）
            if (heldCount > 0) {
                releaseEffects()
                DiagLog.log("EQ", "三项全关 → 释放实例（池位归还）")
            }
            return false
        }
        if (unifiedMode) return ensureDp(force) != null
        return ensureLegacy(force)
    }

    private fun ensureLegacy(force: Boolean): Boolean {
        if (!force && System.currentTimeMillis() < cooldownUntil) return false
        // 合并实例要是还挂着（比如刚被判定不可用），先还回去 —— 不然同一个 session 上
        // 同时挂 DP + 三条独立链 = 4 个池位，池位一满又会把后面的创建打回 -3。
        if (dp != null) {
            runCatching { dp?.release() }
            dp = null
            notePoolChanged()
        }
        var ok = false
        if (eqWanted()) ok = ensureLegacyEq() != null || ok
        if (bassStrength > 0) ok = ensureLegacyBass() != null || ok
        if (loudnessGain > 0) ok = ensureLegacyLoud() != null || ok
        return ok
    }

    // ── 合并实例 ──

    /** 失败后的冷却截止时间（毫秒）。自动路径在冷却期内不再尝试，避免重试风暴。 */
    @Volatile
    private var cooldownUntil = 0L

    private const val FAIL_COOLDOWN_MS = 4_000L

    /** DP 连续建失败多少次才判「本机不支持」—— HAL 抽风一次不算 */
    private const val DP_FAILS_BEFORE_GIVE_UP = 3

    private fun ensureDp(force: Boolean): DynamicsProcessing? {
        dp?.let { return it }
        if (dpUnsupported) return null
        if (!force && System.currentTimeMillis() < cooldownUntil) return null
        synchronized(this) {
            dp?.let { return it }
            if (!force && System.currentTimeMillis() < cooldownUntil) return null
            val built = buildDp(attachedSessionId) ?: return null
            dp = built
            markBuilt(dpType)
            notePoolChanged()
            return built
        }
    }

    private fun limiter() = DynamicsProcessing.Limiter(
        /* inUse = */ true,
        /* enabled = */ true,
        /* linkGroup = */ 0,
        /* attackTime = */ 1f,
        /* releaseTime = */ 60f,
        /* ratio = */ 10f,
        /* threshold = */ -1f,
        /* postGain = */ 0f
    )

    /**
     * 建合并实例：preEq（均衡器 + 低音）+ limiter（防削波）+ inputGain（响度）。
     *
     * 变体不是随便挑的：HAL 只实现其中一个时，另一个会抛
     * "variant N not available for creation"（MuMu 就是只有 0）。两个都试。
     */
    private fun buildDp(sessionId: Int): DynamicsProcessing? {
        if (Build.VERSION.SDK_INT < 28) return null
        // ⚠️ 两个 variant 里只有一个可能成功（多半是 0，1 会回 "not available for creation"），
        // 所以失败要**合并成一次**上报：否则 variant 1 的「正常不支持」会被当成整台机器的失败，
        // 连续几轮就把 DP 判死、永久退回三条独立链 —— 那正好把本版要修的问题又装回去。
        var lastErr: Throwable? = null
        for (variant in intArrayOf(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION
        )) {
            val built = runCatching {
                val builder = DynamicsProcessing.Config.Builder(
                    variant,
                    2,
                    /* preEqInUse = */ true, BAND_COUNT,
                    /* mbcInUse = */ false, 0,
                    /* postEqInUse = */ false, 0,
                    /* limiterInUse = */ true
                )
                // Builder 上没有 setPreEqBandAllChannelsTo（只有 Config 运行时有），
                // 所以先把 Eq 对象整体拼好再一次性塞进去。
                val preEq = DynamicsProcessing.Eq(true, true, BAND_COUNT)
                for (b in 0 until BAND_COUNT) {
                    preEq.setBand(b, DynamicsProcessing.EqBand(true, FREQS[b].toFloat(), 0f))
                }
                builder.setPreEqAllChannelsTo(preEq)
                builder.setInputGainAllChannelsTo(0f)
                builder.setLimiterAllChannelsTo(limiter())
                val fx = DynamicsProcessing(0, sessionId, builder.build())
                fx.enabled = true
                fx
            }.onFailure {
                Log.w(TAG, "统一音效 variant=$variant 创建失败: ${it.message}")
                lastErr = it
            }.getOrNull()
            if (built != null) {
                dpBuildFails = 0
                DiagLog.log("EQ", "统一音效已创建 variant=$variant bands=$BAND_COUNT（均衡器+低音+响度共 1 个池位）")
                return built
            }
        }
        dpBuildFails++
        lastErr?.let { noteFailure(dpType, "统一音效", it) }
        return null
    }

    /** DP 连续建失败次数 —— 只有「明确不支持」或反复失败才降级到独立链 */
    @Volatile
    private var dpBuildFails = 0

    // ── 回退链（API 26/27 或 DP 不可用） ──

    private fun ensureLegacyEq(): Equalizer? {
        legacyEq?.let { return it }
        if (idleReleased || attachedSessionId <= 0 || !eqWanted()) return null
        if (System.currentTimeMillis() < cooldownUntil) return null
        legacyEq = runCatching { Equalizer(0, attachedSessionId) }
            .recoverCatching { Equalizer(1, attachedSessionId) }
            .onFailure { noteFailure(AudioEffect.EFFECT_TYPE_EQUALIZER, "均衡器", it) }
            .getOrNull()
        if (legacyEq != null) {
            markBuilt(AudioEffect.EFFECT_TYPE_EQUALIZER)
            legacyEq?.let { cacheLegacyEqCapabilities(it) }
            notePoolChanged()
        }
        return legacyEq
    }

    private fun ensureLegacyBass(): BassBoost? {
        legacyBass?.let { return it }
        if (idleReleased || attachedSessionId <= 0 || bassStrength <= 0 || !bassAvailable) return null
        if (System.currentTimeMillis() < cooldownUntil) return null
        legacyBass = runCatching { BassBoost(0, attachedSessionId) }
            .onFailure { noteFailure(bassType, "低音增强", it) }
            .getOrNull()
        if (legacyBass != null) { markBuilt(bassType); notePoolChanged() }
        return legacyBass
    }

    private fun ensureLegacyLoud(): LoudnessEnhancer? {
        legacyLoud?.let { return it }
        if (idleReleased || attachedSessionId <= 0 || loudnessGain <= 0 || !loudnessAvailable) return null
        if (System.currentTimeMillis() < cooldownUntil) return null
        legacyLoud = runCatching { LoudnessEnhancer(attachedSessionId) }
            .onFailure { noteFailure(loudType, "响度增强", it) }
            .getOrNull()
        if (legacyLoud != null) { markBuilt(loudType); notePoolChanged() }
        return legacyLoud
    }

    private fun eqCachePrefs() = DiagLog.appContextOrNull()
        ?.getSharedPreferences("fx_history", android.content.Context.MODE_PRIVATE)

    /**
     * 一次性探测设备均衡器能力（回退模式）：建一个 → 读频段 → 立刻释放。
     *
     * 只在**首次挂载且无缓存**时跑一次。释放是显式调的，不会留下孤儿链。
     */
    private fun primeLegacyEqCapabilities(sessionId: Int) {
        if (sessionId <= 0) return
        val prefs = eqCachePrefs() ?: return
        if (prefs.contains("eq_freqs")) return
        if (prefs.getBoolean("eq_prime_failed", false)) return
        Thread {
            val probe = runCatching { Equalizer(0, sessionId) }
                .recoverCatching { Equalizer(1, sessionId) }
                .getOrNull()
            if (probe == null) {
                prefs.edit().putBoolean("eq_prime_failed", true).apply()
                DiagLog.log("EQ", "均衡器能力探测失败 → 判定本机不支持（不再试）")
                return@Thread
            }
            try {
                cacheLegacyEqCapabilities(probe)
                sessionVersion++
            } finally {
                runCatching { probe.release() }
            }
        }.apply { isDaemon = true; name = "eq-prime" }.start()
    }

    private fun cacheLegacyEqCapabilities(e: Equalizer) {
        val prefs = eqCachePrefs() ?: return
        runCatching {
            val n = e.numberOfBands.toInt()
            val freqs = IntArray(n) { (e.getCenterFreq(it.toShort()).toInt() + 500) / 1000 }
            val range = e.bandLevelRange
            prefs.edit()
                .putString("eq_freqs", freqs.joinToString(","))
                .putInt("eq_range_lo", range[0].toInt())
                .putInt("eq_range_hi", range[1].toInt())
                .apply()
            DiagLog.log("EQ", "均衡器能力已缓存：bands=$n（实例已释放）")
        }
    }

    // ── 失败处理 ──

    /** 重试路径里置 true：失败只记日志，不累计（防自我放大） */
    @Volatile
    private var inRetryPass = false

    /** 同一类型失败处理的递归保护 */
    private val failingGuard = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>()

    private fun noteFailure(type: java.util.UUID, label: String, e: Throwable) {
        if (!failingGuard.add(type)) return
        try {
            noteFailureInner(type, label, e)
        } finally {
            failingGuard.remove(type)
        }
    }

    /** 日志节流：失败可能成片出现，但环形缓冲只有 4000 行，不能让它把排查工具刷废 */
    @Volatile
    private var lastFailLogAt = 0L
    private var suppressedFails = 0

    private fun noteFailureInner(type: java.util.UUID, label: String, e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        // 错误码本身分不出「池满」和「本机没实现」—— 两者都是 -3 (ERROR_NO_INIT)。
        val looksLikeResource = msg.contains("-3") || msg.contains("-6")
        val builtBefore = type in everBuilt
        val streak = if (inRetryPass) (failStreak[type] ?: 0) else (failStreak[type] ?: 0) + 1
        failStreak[type] = streak
        // ⚠️ 以前每次失败都写盘；失败风暴时 5 秒 164 次 IO，既卡又把环形缓冲刷满。
        if (!inRetryPass && (streak == 1 || streak % 10 == 0)) saveFailStreak(type, streak)

        val hardUnsupported = !looksLikeResource
        if (hardUnsupported) {
            failedTypes = failedTypes + type
            starvedTypes = starvedTypes - type
            // DP 报「明确不支持」→ 才降级。但 HAL 偶尔会抽风（MuMu 上见过 AudioEffect:
            // set/get parameter error，重启后就好），所以必须**连续几次**失败才判死，
            // 否则一次抽风就把整个进程永久退回三条独立链，把本版要修的池位问题又装回去。
            if (type == dpType && dpBuildFails >= DP_FAILS_BEFORE_GIVE_UP) {
                dpUnsupported = true
                DiagLog.log("EQ", "统一音效连续 $dpBuildFails 次建不出来（$msg）→ 本次进程回退到独立音效链")
            } else if (type == dpType) {
                // 还没到判死的次数：按可重挂处理，退避后再试，别当场连打
                starvedTypes = starvedTypes + type
                failedTypes = failedTypes - type
                cooldownUntil = System.currentTimeMillis() + FAIL_COOLDOWN_MS
            }
        } else {
            starvedTypes = starvedTypes + type
            failedTypes = failedTypes - type
            cooldownUntil = System.currentTimeMillis() + FAIL_COOLDOWN_MS
        }
        lastError = msg
        Log.w(
            TAG,
            "$label 不可用（${if (hardUnsupported) "本机不支持" else "effect 池已满，可重挂"}）: $msg " +
                "builtBefore=$builtBefore streak=$streak"
        )

        val now = System.currentTimeMillis()
        if (now - lastFailLogAt >= 30_000L) {
            val folded = if (suppressedFails > 0) "，此前已折叠 $suppressedFails 次同类失败" else ""
            DiagLog.log(
                "EQ",
                "$label 创建失败：$msg（${if (hardUnsupported) "判定本机不支持" else "可重挂"}，" +
                    "曾建成=$builtBefore，连续失败=$streak$folded）"
            )
            lastFailLogAt = now
            suppressedFails = 0
        } else {
            suppressedFails++
        }

        // ⚠️ 这里**绝对不能**碰 sessionVersion（会被 ViewModel 回灌成无限递归）。
        effectFailedTick++
    }

    /** 记一次「真的建成了」—— 这是区分池满/不支持的依据 */
    private fun markBuilt(type: java.util.UUID) {
        starvedTypes = starvedTypes - type
        failedTypes = failedTypes - type
        failStreak.remove(type)
        clearFailStreak(type)
        cooldownUntil = 0L
        if (type in everBuilt) return
        everBuilt = everBuilt + type
        saveEverBuilt()
    }

    // ── 挂载失败退避重试 ──

    /**
     * 挂载失败后的**单次**退避重试（后台线程，不阻塞调用方）。
     *
     * v2.3.2 起从「三次重挂」收敛成「一次重试」：连续失败说明池位真的没还，
     * 多试几次只会把失败计数和日志刷爆。
     */
    fun scheduleAutoRetry(sessionId: Int) {
        if (sessionId <= 0 || heldCount > 0 || !fxWanted()) return
        if (autoRetryPending) return
        autoRetryPending = true
        Thread {
            try {
                Thread.sleep(FAIL_COOLDOWN_MS + 500)
                val s = attachedSessionId
                if (s <= 0 || heldCount > 0) return@Thread
                inRetryPass = true
                attach(s)
                DiagLog.log("EQ", "自动重试一次 → held=$heldCount")
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
     * release 只是把 handle 交还给框架，AudioFlinger 真正回收池位是**异步**的，
     * 所以这里等 250ms 再重建，只试一次。
     */
    fun reattach(): Boolean {
        val s = attachedSessionId
        if (s <= 0) return false
        reattachCount++
        release()
        DiagLog.log("EQ", "reattach #$reattachCount session=$s 释放旧链，等待池位回收")
        runCatching { Thread.sleep(250) }
        attach(s)
        DiagLog.log(
            "EQ",
            "reattach #$reattachCount session=$s 结果 held=$heldCount " +
                "dp=${dp != null} starved=${starvedTypes.size}"
        )
        return heldCount > 0
    }

    // ── 应用参数 ──

    /**
     * 写参数到设备。**这里不创建实例** —— 创建只在 [ensureUnified] 里做一次，
     * 以前 applyToDevice 也会 ensure，同一次 attach 建两遍（失败时翻倍）。
     */
    private fun applyToDevice() {
        if (unifiedMode) applyUnified() else applyLegacy()
        notePoolChanged()
    }

    private fun applyUnified() {
        val d = dp ?: return
        runCatching {
            if (!fxWanted()) {
                d.enabled = false
                return@runCatching
            }
            d.enabled = true
            val gains = effectiveGains()
            // preEq 里既有均衡器曲线也有低音曲线：任一项要用就把这一段打开
            val preEqOn = enabled || bassStrength > 0
            d.setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, preEqOn, BAND_COUNT))
            for (b in 0 until BAND_COUNT) {
                d.setPreEqBandAllChannelsTo(
                    b,
                    DynamicsProcessing.EqBand(true, FREQS[b].toFloat(), gains[b] / 100f)
                )
            }
            // 响度：前级抬高 + limiter 兜底。⚠️ 不能用 limiter 的 postGain ——
            // 它在限幅之后，加多少就削多少。
            d.setInputGainAllChannelsTo(loudnessGain / 100f)
            d.setLimiterAllChannelsTo(limiter())
        }.onFailure { Log.w(TAG, "应用统一音效失败: ${it.message}") }
    }

    private fun applyLegacy() {
        legacyEq?.let { e ->
            runCatching {
                e.enabled = eqWanted()
                if (eqWanted()) {
                    val range = bandRange()
                    val levels = bandLevels()
                    for (b in levels.indices) {
                        if (b < e.numberOfBands.toInt()) {
                            e.setBandLevel(b.toShort(), levels[b].coerceIn(range[0], range[1]).toShort())
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "应用均衡器失败: ${it.message}") }
        }
        legacyBass?.let { b ->
            runCatching {
                b.enabled = bassStrength > 0
                if (bassStrength > 0) b.setStrength(bassStrength.toShort())
            }.onFailure { Log.w(TAG, "低音增强应用失败: ${it.message}") }
        }
        legacyLoud?.let { l ->
            runCatching {
                l.enabled = loudnessGain > 0
                if (loudnessGain > 0) l.setTargetGain(loudnessGain)
            }.onFailure { Log.w(TAG, "响度增强应用失败: ${it.message}") }
        }
    }

    /** 均衡器曲线 + 低音曲线的合成增益（mB） */
    private fun effectiveGains(): IntArray {
        val eqCurve = if (enabled) bandLevels() else IntArray(BAND_COUNT)
        return IntArray(BAND_COUNT) { b ->
            val bass = BASS_CURVE.getOrElse(b) { 0 } * bassStrength / 1000
            (eqCurve.getOrElse(b) { 0 } + bass).coerceIn(LEVEL_MIN, LEVEL_MAX)
        }
    }

    fun configure(enabled: Boolean, presetIndex: Int) {
        this.enabled = enabled
        val idx = presetIndex.coerceIn(0, PRESETS.size - 1)
        // ⚠️ 只有真的换了预设才丢掉自定义曲线。
        // 旧实现先赋值再比较，条件恒为 true —— 设置流每次重发都会把用户拖好的曲线清空。
        if (idx != this.presetIndex) {
            this.presetIndex = idx
            customBands = false
            manualLevels = emptyMap()
        }
        // 设置流回灌（冷启读 DataStore）也可能带来「本来就该开着」的状态，
        // 但**不绕过冷却、不绕过空闲抑制** —— 否则暂停拆链后又把池位抢回来。
        if (fxWanted()) {
            ensureUnified(force = false)
        } else if (heldCount > 0) {
            releaseEffects()
            DiagLog.log("EQ", "均衡器关闭且无增强 → 释放实例（池位归还）")
        }
        applyToDevice()
    }

    /** 一次设置两个增强参数。0 = 关闭该项 */
    fun configureEnhanced(bass: Int, loudness: Int) {
        this.bassStrength = bass.coerceIn(0, 1000)
        this.loudnessGain = loudness.coerceIn(0, 1500)
        if (fxWanted()) {
            ensureUnified(force = false)
        } else if (heldCount > 0) {
            releaseEffects()
            DiagLog.log("EQ", "增强项全关 → 释放实例（池位归还）")
        }
        applyToDevice()
    }

    // ── 释放 ──

    /** 只释放实例，保留 session 认领（DSD 旁路 / 三项全关用） */
    private fun releaseEffects() {
        runCatching { dp?.release() }
        runCatching { legacyEq?.release() }
        runCatching { legacyBass?.release() }
        runCatching { legacyLoud?.release() }
        dp = null
        legacyEq = null
        legacyBass = null
        legacyLoud = null
        starvedTypes = emptySet()
        notePoolChanged()
    }

    fun release() {
        attachedSessionId = 0
        releaseEffects()
        lastError = null
    }
}
