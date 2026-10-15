package com.ddz

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简崩溃记录器 + 启动阶段打点。
 *
 * ## 为什么需要它
 * 这个项目在开发机上**无法编译、无法调试**（命令行起不来、没有 Android SDK），
 * 出了问题只能靠「把日志发给我」，而抓 logcat 需要额外装 platform-tools。
 *
 * ## 两层机制
 * 1. **未捕获异常处理器**：任何线程的未捕获 Throwable 都会把堆栈写进文件，
 *    下次启动时用纯文本界面显示出来。
 * 2. **启动阶段打点（breadcrumb）**：每走一步就把「走到哪儿了」落盘。
 *    这一层专门用来对付**第 1 层抓不到的死法**：
 *      - 崩溃发生在 Activity 实例化 / 主题解析阶段（比 install() 更早）；
 *      - 原生层崩溃（SIGSEGV），根本不走 Java 异常处理器；
 *      - 进程被系统直接杀掉。
 *    这些情况下第 1 层什么都留不下，但「最后停在哪一步」一定留得下。
 */
object CrashLog {

    private const val CRASH_FILE = "last_crash.txt"
    private const val BOOT_FILE = "last_boot.txt"
    private const val BOOT_DONE = "启动完成"
    private const val TAG = "DDZ"
    private const val MAX_APP_FRAMES = 14

    private var appContext: Context? = null

    @Volatile
    private var recordedNonFatal = false

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, CRASH_FILE, buildReport(thread.name, error, "未捕获"))
                Log.e(TAG, "崩溃已记录", error)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志失败", t)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 启动阶段打点：**立刻落盘**。
     *
     * 刻意用同步写 + 追加，因为它的使用场景就是「下一行可能就把进程干掉了」，
     * 任何缓存/异步都会丢掉最关键的那一条。
     */
    fun mark(context: Context, stage: String) {
        try {
            val line = "${stamp()}  $stage\n"
            File(context.filesDir, BOOT_FILE).appendText(line)
        } catch (_: Throwable) {
        }
    }

    /** 第一帧成功绘制后调用：标记「启动完成」，此后不再报告 */
    fun bootCompleted(context: Context) {
        mark(context, BOOT_DONE)
    }

    /** 记录一个**不杀进程**的异常（典型场景：渲染循环里接住了 Throwable） */
    fun record(error: Throwable, where: String) {
        if (recordedNonFatal) return
        recordedNonFatal = true
        val app = appContext ?: return
        try {
            write(app, CRASH_FILE, buildReport(Thread.currentThread().name, error, where))
            Log.e(TAG, "已记录非致命异常（$where）", error)
        } catch (t: Throwable) {
            Log.e(TAG, "写崩溃日志失败", t)
        }
    }

    /**
     * 取出「上次启动的异常信息」并清空，供界面显示。没有则返回 null。
     *
     * 两种情形都会产出报告：
     *  - 有崩溃堆栈（正常崩溃）；
     *  - **没有崩溃堆栈，但启动打点没走到「启动完成」** ——
     *    这说明进程是在 Java 异常处理器之外死掉的（原生崩溃 / 被系统杀 / 崩在更早的阶段），
     *    此时「最后停在哪一步」就是最有价值的线索。
     */
    fun consume(context: Context): String? {
        val crash = readAndDelete(context, CRASH_FILE)
        val boot = readAndDelete(context, BOOT_FILE)
        val bootIncomplete = boot != null && !boot.contains(BOOT_DONE)
        if (crash == null && !bootIncomplete) return null

        return buildString {
            append("=== 上次启动没有正常完成 ===\n")
            append("时间：").append(stamp()).append('\n')
            append("包名：").append(context.packageName).append('\n')
            append("设备：").append(deviceInfo()).append('\n')
            append('\n')
            if (crash != null) {
                append("--- 崩溃堆栈 ---\n")
                append(crash)
                append('\n')
            } else {
                append("--- 没有捕获到 Java 异常 ---\n")
                append("这通常意味着：原生层崩溃（SIGSEGV）、进程被系统杀死，\n")
                append("或者崩溃发生在 Activity 实例化 / 主题解析阶段（比我们的处理器更早）。\n")
                append("请重点看下面的「最后停在哪一步」。\n\n")
            }
            append("--- 启动阶段打点（最后一行就是死亡位置）---\n")
            append(boot ?: "(没有打点记录：说明连 MainActivity.onCreate 都没进来，\n" +
                "问题在 Activity 实例化、主题解析或安装包本身)\n")
        }
    }

    private fun readAndDelete(context: Context, name: String): String? {
        return try {
            val f = File(context.filesDir, name)
            if (!f.exists()) return null
            val text = f.readText()
            f.delete()
            if (text.isBlank()) null else text
        } catch (t: Throwable) {
            null
        }
    }

    private fun write(context: Context, name: String, report: String) {
        try {
            File(context.filesDir, name).writeText(report)
        } catch (_: Throwable) {
        }
        // 同时写一份到外部私有目录，方便用文件管理器取出
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                File(dir, name).writeText(report)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stamp(): String = try {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    } catch (t: Throwable) {
        "?"
    }

    private fun deviceInfo(): String = try {
        "Android ${android.os.Build.VERSION.RELEASE}(API ${android.os.Build.VERSION.SDK_INT}) " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
            "ABI=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}"
    } catch (t: Throwable) {
        "?"
    }

    private fun buildReport(threadName: String, error: Throwable, where: String): String {
        val sw = StringWriter()
        try {
            error.printStackTrace(PrintWriter(sw))
        } catch (_: Throwable) {
        }
        val full = sw.toString()
        return buildString {
            append("时间：").append(stamp()).append('\n')
            append("来源：").append(where).append('\n')
            append("线程：").append(threadName).append('\n')
            append("异常：").append(error.javaClass.name).append('\n')
            append("信息：").append(error.message ?: "(无)").append('\n')
            append('\n').append("=== 与 com.ddz 相关的栈帧（看这里就够）===\n")
            append(extractAppFrames(full))
            append('\n').append("=== 完整堆栈 ===\n")
            append(full)
        }
    }

    /** 只挑出 com.ddz 的栈帧：通常十几行里就能看出崩在哪个函数哪一行 */
    private fun extractAppFrames(full: String): String {
        val picked = ArrayList<String>()
        for (raw in full.split('\n')) {
            val t = raw.trim()
            if (t.startsWith("at ") && t.contains("com.ddz")) {
                picked.add(t)
                if (picked.size >= MAX_APP_FRAMES) break
            }
        }
        if (picked.isEmpty()) {
            return "(没有 com.ddz 的栈帧，说明崩在系统内部或资源加载阶段)\n"
        }
        return picked.joinToString("\n") + "\n"
    }
}
