package com.kmusic.apkhd.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志 —— 专门用来定位「为什么有的歌有歌词、有的没有」。
 *
 * 设计要点（都是为了能靠日志反推，而不是猜）：
 *
 * 1. **同时进 logcat 和内存环形缓冲**。logcat 用户抓不到（没授权 adb），
 *    所以必须落到 App 内文件，让用户从「我的 → 导出诊断日志」直接分享出来。
 * 2. **环形缓冲只留最近 N 条**，避免长时间播放把文件撑爆。
 * 3. **每行都带时间戳和阶段标签**，多首歌交替播放时能按时间轴还原顺序。
 * 4. 只记**决策依据**（用了哪个候选、校验为何失败、HTTP 码），
 *    不记歌词正文（那是用户内容，也没必要）。
 */
object DiagLog {

    private const val TAG = "MoyinDiag"
    private const val MAX_LINES = 4000
    private const val MAX_FILES = 3

    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 给同进程的其它工具拿 Application Context（这里只是借用，别在别处乱塞 Context） */
    fun appContextOrNull(): Context? = appContext

    /** 记一条诊断。会同时进 logcat（tag=MoyinDiag）和内存缓冲 */
    fun log(stage: String, msg: String) {
        val line = "${fmt.format(Date())} [$stage] $msg"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        Log.d(TAG, line)
    }

    /** 记一条带耗时的诊断 */
    fun log(stage: String, msg: String, ms: Long) {
        log(stage, "$msg (耗时 ${ms}ms)")
    }

    /** 取全部缓冲（导出用） */
    fun snapshot(): String = synchronized(lock) { buffer.joinToString("\n") }

    /**
     * 崩溃现场落盘。
     *
     * 真机上 adb 常年 unauthorized，没有这一步就只能靠猜。
     * 必须**同步**写：进程下一刻就没了，任何异步/延迟都会丢。
     */
    fun dumpCrash(t: Throwable) {
        val ctx = appContext ?: return
        runCatching {
            val f = File(ctx.filesDir, "last_crash.txt")
            f.writeText(
                "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
                    "线程: ${Thread.currentThread().name}\n" +
                    android.util.Log.getStackTraceString(t) +
                    "\n\n--- 崩溃前诊断缓冲 ---\n" + snapshot()
            )
        }
    }

    /** 读上一次崩溃记录（导出日志时附上） */
    fun lastCrash(): String? {
        val ctx = appContext ?: return null
        return runCatching {
            File(ctx.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
        }.getOrNull()
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        log("SYS", "日志已清空")
    }

    /**
     * 导出到 App 私有目录并返回文件。
     *
     * 放在 cache/logs 下而不是 files/ —— 缓存目录用户清缓存就没了，
     * 不会长期占地方；分享完也可以顺手删掉。
     */
    fun export(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        // 只留最近几个，避免堆积
        dir.listFiles()?.sortedBy { it.lastModified() }
            ?.dropLast(MAX_FILES - 1)?.forEach { runCatching { it.delete() } }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "moyin_diag_$stamp.log")
        val header = buildString {
            appendLine("# KMusic 诊断日志")
            // 不引 BuildConfig（项目没开 buildConfig，加了会动构建配置），
            // 直接从 PackageManager 读，反而连用户装的包是什么版本都能记下来
            val pm = context.packageManager
            val pi = runCatching {
                pm.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            appendLine(
                "# 版本: ${pi?.versionName ?: "?"} (versionCode=${pi?.longVersionCode ?: 0})"
            )
            appendLine("# 设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("# ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("# 导出时间: $stamp")
            appendLine("# 说明: 每行格式 = 时间 [阶段] 内容。歌词问题重点看 LYRIC_* 与 NET_* 阶段。")
            appendLine("# " + "-".repeat(60))
        }
        val crash = lastCrash()
        file.writeText(
            header + snapshot() + "\n" +
                if (crash != null) "\n\n======== 上次崩溃 ========\n" + crash + "\n" else ""
        )
        return file
    }
}
