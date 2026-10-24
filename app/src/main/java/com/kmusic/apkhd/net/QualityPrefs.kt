package com.kmusic.apkhd.net

/**
 * 音质偏好 → 实际取链档位的换算。
 *
 * 抽成纯函数是为了能测：「无损降级」关掉时**只取首选那一档**，
 * 拿不到就失败 —— 绝不能偷偷给个别的音质顶上（用户选的就不作数了）。
 */
object QualityPrefs {

    /** 各档位的降级链（顺序即优先级，**只降不升**） */
    private val TIERS: Map<String, List<String>> = mapOf(
        "master" to listOf(KuwoClient.BR_MASTER, KuwoClient.BR_HIRES, KuwoClient.BR_HIGH),
        "hires" to listOf(KuwoClient.BR_HIRES, KuwoClient.BR_HIGH),
        "high" to listOf(KuwoClient.BR_HIGH),
        "standard" to listOf(KuwoClient.BR_STANDARD)
    )

    val ALL: List<String> = listOf("master", "hires", "high", "standard")

    /**
     * @param quality  用户选的档位（未知值按 master 处理）
     * @param fallback 是否允许自动降级
     * @return 依次尝试的 br 列表
     */
    fun tiers(quality: String, fallback: Boolean): List<String> {
        val chain = TIERS[quality] ?: TIERS.getValue("master")
        return if (fallback) chain else chain.take(1)
    }

    fun name(quality: String): String = when (quality) {
        "master" -> "臻品母带"
        "hires" -> "无损 FLAC"
        "high" -> "极高品"
        "standard" -> "标准"
        else -> "臻品母带"
    }
}
