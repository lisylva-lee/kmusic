package com.kmusic.apkhd.playback

import android.content.Context
import com.kmusic.apkhd.data.DiagLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置预设（v2.3.5 起十段）。
 *
 * 曲线数据仍然按**当年五段设备均衡器**的刻度写（60 / 230 / 910 / 3.6k / 14k），
 * 由 [EqBands.resample] 在初始化时按对数轴插值成十段。
 *
 * 为什么不直接手写十段：那五组数字是当年一条条听出来的，
 * 手写十段等于重新编一遍 —— 听着像，但已经不是同一条曲线。
 * 插值出来的是同一根曲线在新刻度上的采样，换刻度不该换音色。
 *
 * 旧 7 套（原声/流行/摇滚/古典/人声/低音/高音）**保留原有顺序**，新预设一律追加在
 * 它们后面 —— 老用户存的 `eq_preset` 是下标，插到中间会让人一升级就听到另一条曲线。
 * 这条约束比「分组好看」重要得多。
 *
 * 分组只影响界面展示（风格 / 场景 / 设备），下标仍是全局唯一。
 */
enum class EqPresetGroup(val label: String) {
    STYLE("风格"),
    SCENE("场景"),
    DEVICE("设备")
}

data class EqPresetDef(
    val name: String,
    val group: EqPresetGroup,
    /** 十段增益（mB），对应 [EqBands.FREQS] */
    val gains: IntArray
) {
    // IntArray 的 equals 是引用比较，data class 自动生成的会误导；显式覆写
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqPresetDef) return false
        return name == other.name && group == other.group && gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + group.hashCode()) + gains.contentHashCode()
}

/**
 * 预设表。**前 7 项顺序不可动**（见类注释）。
 */
object EqPresets {

    private val BUILT_IN: List<EqPresetDef> = listOf(
        // ── 旧 7 套，顺序锁死 ──
        legacy("原声", EqPresetGroup.STYLE, 0, 0, 0, 0, 0),
        legacy("流行", EqPresetGroup.STYLE, -100, 200, 400, 200, -100),
        legacy("摇滚", EqPresetGroup.STYLE, 400, 150, -100, 200, 400),
        legacy("古典", EqPresetGroup.STYLE, 300, 100, -100, 150, 300),
        legacy("人声", EqPresetGroup.STYLE, -200, 100, 500, 300, -100),
        legacy("低音", EqPresetGroup.STYLE, 600, 300, 0, -100, -200),
        legacy("高音", EqPresetGroup.STYLE, -200, -100, 0, 300, 600),
        // ── 新增：场景 ──
        // 车机：低频补一点、2k 抬一点（胎噪掩蔽主要在这两处），高频不猛加否则齿音刺
        legacy("车机", EqPresetGroup.SCENE, 300, 200, 0, 150, 200),
        // 夜间：整体压平并降一点，避免安静环境里低频轰头
        legacy("夜间", EqPresetGroup.SCENE, -200, -100, 0, -100, -200),
        // 小喇叭：手机外放没有低频，抬 230/910 做补偿，60Hz 反而要收（放不出来只会糊）
        legacy("小喇叭", EqPresetGroup.SCENE, -300, 300, 400, 200, 0),
        // ── 新增：设备 ──
        // 耳机：轻微的微笑曲线，抵消入耳式的低频泄漏
        legacy("耳机", EqPresetGroup.DEVICE, 200, 100, 0, 100, 200),
        // 音箱：等响补偿，小音量下补两端
        legacy("音箱", EqPresetGroup.DEVICE, 250, 0, -100, 0, 250)
    )

    /** 用旧五段刻度写一条曲线，插值成十段 */
    private fun legacy(
        name: String,
        group: EqPresetGroup,
        vararg gains: Int
    ) = EqPresetDef(name, group, EqBands.resample(gains))

    /** 内置预设数量。老用户下标必须 < 此值才安全 */
    val builtInCount: Int get() = BUILT_IN.size

    /** 内置预设（只读视图） */
    fun builtIn(): List<EqPresetDef> = BUILT_IN

    /** 名字表 —— ViewModel 与设置页直接用这个 */
    fun names(): List<String> = BUILT_IN.map { it.name }

    /** 按分组切分：给界面画分组小标题用 */
    fun grouped(): List<Pair<EqPresetGroup, List<IndexedValue<EqPresetDef>>>> =
        EqPresetGroup.entries.map { g ->
            g to BUILT_IN.withIndex().filter { it.value.group == g }
        }.filter { it.second.isNotEmpty() }

    fun gainsOf(index: Int): IntArray =
        BUILT_IN.getOrElse(index) { BUILT_IN[0] }.gains

    fun nameOf(index: Int): String = BUILT_IN.getOrElse(index) { BUILT_IN[0] }.name
}

/**
 * 用户自建预设。
 *
 * 只存「十段增益 + 名字」，不存完整音效参数：本项目的音效只有 EQ / 低音 / 响度
 * 三项，存整个 profile 反而会让「保存曲线」变成「顺手把低音也存进去」，
 * 语义不彻底 —— 用户点的是「保存这条曲线」。
 *
 * 存储用 SharedPreferences（单文件、条目少、读一次全量），不占系统音效池位，
 * 也不跟播放状态耦合。
 */
object UserEqPresets {

    const val MAX_PRESETS = 20
    const val MAX_NAME_LEN = 24
    private const val PREF = "eq_user_presets"
    private const val KEY = "presets_json"

    /** [gains] 恒为十段 —— 旧版本存的五段在 [load] 里已按对数轴插值迁移 */
    data class Preset(val name: String, val gains: List<Int>)

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context): List<Preset> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    if (name.isEmpty()) continue
                    val g = o.optJSONArray("gains") ?: continue
                    val stored = IntArray(g.length()) { g.optInt(it, 0) }
                    if (stored.isEmpty()) continue
                    // 五段旧曲线在这里升到十段。不做这一步，老用户存的曲线
                    // 会因为段数不符被整条丢掉 —— 看起来就是「我存的曲线没了」。
                    add(Preset(name, EqBands.normalize(stored).toList()))
                }
            }
        }.onFailure { DiagLog.log("EQ", "读取用户预设失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("gains", JSONArray(p.gains))
            })
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** 同名覆盖（用户再存一次同一条曲线不该出现两个同名项） */
    fun savePreset(context: Context, name: String, gains: List<Int>): List<Preset> {
        val clean = name.trim().take(MAX_NAME_LEN).ifEmpty { "自定义" }
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        val entry = Preset(clean, EqBands.normalize(gains.toIntArray()).toList())
        if (idx >= 0) list[idx] = entry
        else {
            list.add(entry)
            // 超限时踢掉最旧的（列表首项），不静默失败
            while (list.size > MAX_PRESETS) list.removeAt(0)
        }
        save(context, list)
        return list
    }

    fun delete(context: Context, name: String): List<Preset> {
        val list = load(context).filterNot { it.name == name }
        save(context, list)
        return list
    }
}
