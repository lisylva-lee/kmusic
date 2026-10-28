package com.kmusic.apkhd.playback

/**
 * 音效链状态判定（A4）。
 *
 * 以前界面上只有一句「当前占用：N 项系统音效」——它回答的是**池位**，
 * 但用户真正想知道的是「我现在听到的效果生效了吗？没生效是为什么？」
 * 于是出现两种经典困惑：
 *  - 开了均衡器但正在播 DSD / 开了省电播放 → 显示「开」，其实整条链没挂上；
 *  - 拉满曲线又叠响度 → 前级被 headroom 抵消，听起来「不够响」，但没有任何说明。
 *
 * 把判定从 Composable 里拆成纯函数：分支能被单测钉死，界面只负责翻译成文案。
 */
sealed interface FxStatus {
    /**
     * 音效正在处理。
     * @param sampleRateHz 输出采样率（未知为 null）
     * @param headroomDb 让给 EQ 正增益峰值的前级余量（0 = 没让）
     * @param loudnessAppliedDb 实际生效的前级增益（用户设定值可能被 headroom 抵消）
     * @param loudnessRequestedDb 用户设定的响度
     */
    data class Active(
        val sampleRateHz: Int?,
        val headroomDb: Float,
        val loudnessAppliedDb: Float,
        val loudnessRequestedDb: Float
    ) : FxStatus

    /** 有配置但链路被旁路 */
    data class Bypassed(val reason: Reason) : FxStatus {
        enum class Reason {
            /** 省电播放：PCM 直通 DSP */
            OFFLOAD,
            /** DSD 解码占满 CPU，自动旁路 */
            DSD,
            /** 三项全关 */
            ALL_OFF
        }
    }

    /** 池位被别的 App 占满 */
    data object PoolStarved : FxStatus

    /**
     * 用户开着音效，但当前是暂停/停止，App 主动把池位归还了。
     *
     * 这不是「没生效」，也不是「全关」：暂停时没有任何音频需要处理，
     * 继续占着系统音效池位只会挡住别人的 App。恢复播放会重新申请。
     * 早先这种情况被归到 [Bypassed.ALL_OFF]，界面显示「三项全关」——
     * 用户明明看到开关是「开」，文案却说全关，是纯粹的自相矛盾。
     */
    data object Suspended : FxStatus

    /** 用户什么都没开 */
    data object Idle : FxStatus

    companion object {
        /**
         * @param offload 省电播放是否开启
         * @param dsdPlaying 正在播 DSD
         * @param dsdBypassEq 用户允许 DSD 时旁路
         * @param wanted 用户配置里有几项开着（0..3）
         * @param held 实际占用的池位数
         * @param starved 是否因池满抢不到
         * @param paused 当前处于暂停/停止（此时 App 会主动归还池位）
         */
        fun resolve(
            offload: Boolean,
            dsdPlaying: Boolean,
            dsdBypassEq: Boolean,
            wanted: Int,
            held: Int,
            starved: Boolean,
            paused: Boolean = false,
            sampleRateHz: Int? = null,
            headroomDb: Float = 0f,
            loudnessAppliedDb: Float = 0f,
            loudnessRequestedDb: Float = 0f
        ): FxStatus = when {
            // 省电播放优先：这时连链都不该挂，说「已旁路」比说「关闭」准确
            offload -> Bypassed(Bypassed.Reason.OFFLOAD)
            dsdPlaying && dsdBypassEq -> Bypassed(Bypassed.Reason.DSD)
            wanted == 0 -> Idle
            held == 0 && starved -> PoolStarved
            // 池位归零但用户确实开着 → 先看是不是暂停导致的主动归还
            held == 0 && paused -> Suspended
            held == 0 -> Bypassed(Bypassed.Reason.ALL_OFF)
            else -> Active(
                sampleRateHz = sampleRateHz,
                headroomDb = headroomDb,
                loudnessAppliedDb = loudnessAppliedDb,
                loudnessRequestedDb = loudnessRequestedDb
            )
        }
    }
}
