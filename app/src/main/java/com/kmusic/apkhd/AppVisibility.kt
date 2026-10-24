package com.kmusic.apkhd

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * App 是否在前台。
 *
 * 只靠「有没有可见 Activity」判断：Activity 的 onStart/onStop 覆盖了
 * 「界面可见」这个真实需求，而 onPause 会在弹系统对话框时也触发 ——
 * 那时候 App 还在用户眼前，不该算后台。
 *
 * 为什么需要它：音效链在后台空闲时要主动拆掉（见
 * [com.kmusic.apkhd.playback.AudioEffectHolder.releaseForBackground]），
 * 判「是否前台」是第一步。
 */
object AppVisibility {

    /** 当前可见 Activity 的数量。>0 即为前台。 */
    @Volatile
    private var startedActivities = 0

    /** 是否在前台（没有任何可见界面时为 false） */
    @Volatile
    var isForeground: Boolean = false
        private set

    /**
     * 前后台状态变化的回调。
     *
     * 音效链的「后台空闲释放」需要知道 App 退到后台了这件事 ——
     * 播放器的 isPlaying 变化不一定和这个同步（暂停了但界面还在前台，
     * 或者界面退后台了但歌还在放）。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** 注册前后台变化回调，返回一个反注册句柄 */
    fun addListener(l: (Boolean) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    private fun notify(foreground: Boolean) {
        listeners.forEach { runCatching { it(foreground) } }
    }

    /**
     * 注册到 Application 上。
     *
     * 用 ActivityLifecycleCallbacks 而不是 ProcessLifecycleOwner：
     * 后者要引 androidx.lifecycle:lifecycle-process，而项目用的
     * lifecycle 2.8.6 在本地 Gradle 缓存里没有对应版本，离线构建会失败。
     */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (!isForeground) {
                    isForeground = true
                    notify(true)
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    if (isForeground) {
                        isForeground = false
                        notify(false)
                    }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
