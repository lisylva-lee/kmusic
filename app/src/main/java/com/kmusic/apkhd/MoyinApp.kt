package com.kmusic.apkhd

import android.app.Application
import com.kmusic.apkhd.data.DiagLog

class MoyinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 诊断日志要在最早启动 —— 否则启动阶段的崩溃/异常抓不到
        DiagLog.init(this)
        DiagLog.log("SYS", "App 启动")

        // 前台状态追踪：音效链在后台空闲时要主动拆链（见 AudioEffectHolder）
        AppVisibility.install(this)

        // 未捕获异常落盘：崩溃后用户导一次诊断日志，就能拿到真实堆栈。
        // 真机 adb 常年 unauthorized，这是唯一能自查的通道。
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { DiagLog.dumpCrash(e) }
            prev?.uncaughtException(thread, e)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: MoyinApp
            private set
    }
}
