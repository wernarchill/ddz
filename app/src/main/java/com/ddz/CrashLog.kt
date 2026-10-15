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
 * 极简崩溃记录器。
 *
 * ## 为什么需要它
 * 这个项目在开发机上**无法编译、无法调试**（命令行起不来、没有 Android SDK），
 * 出问题只能靠「把日志发给我」。而抓 logcat 需要额外装 platform-tools。
 *
 * 这里换一条不需要任何工具的路：任何线程的未捕获异常/错误都会把堆栈写进应用私有目录，
 * **下次启动时直接把关键几行画在屏幕上**，截个图就能传出去。
 *
 * ## 覆盖范围
 * - 主线程崩溃（启动即闪退）
 * - 渲染线程抛出的 `Error`（这是最容易被忽略的一类：`catch (Exception)` 接不住，
 *   而 Android 上任何线程的未捕获 Throwable 都会杀掉整个进程）
 * - 网络线程、广播线程的崩溃
 *
 * 报告会同时写两份：应用私有目录（供界面读取显示）和外部私有目录
 * （`Android/data/com.ddz/files/`，方便用文件管理器直接取出完整堆栈）。
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val TAG = "DDZ"
    private const val MAX_APP_FRAMES = 14

    private var appContext: Context? = null

    /** 非致命异常只记第一次，避免渲染循环每帧刷盘 */
    @Volatile
    private var recordedNonFatal = false

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, buildReport(thread.name, error, "未捕获"))
                Log.e(TAG, "崩溃已记录", error)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志失败", t)
            }
            // 交回系统原来的处理器，保持标准行为（logcat + 结束进程）
            previous?.uncaughtException(thread, error)
        }
    }

    /** 记录一个**不杀进程**的异常（典型场景：渲染循环里接住了 Throwable） */
    fun record(error: Throwable, where: String) {
        if (recordedNonFatal) return
        recordedNonFatal = true
        val app = appContext ?: return
        try {
            write(app, buildReport(Thread.currentThread().name, error, where))
            Log.e(TAG, "已记录非致命异常（$where）", error)
        } catch (t: Throwable) {
            Log.e(TAG, "写崩溃日志失败", t)
        }
    }

    /** 取出上次的崩溃报告并清空（只在启动时调用一次）。没有则返回 null */
    fun consume(context: Context): String? {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return null
            val text = f.readText()
            f.delete()
            if (text.isBlank()) null else text
        } catch (t: Throwable) {
            null
        }
    }

    private fun write(context: Context, report: String) {
        try {
            File(context.filesDir, FILE_NAME).writeText(report)
        } catch (_: Throwable) {
            // 私有目录写不进去也不能再抛，否则就成了"崩溃处理器自己崩"
        }
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                File(dir, FILE_NAME).writeText(report)
            }
        } catch (_: Throwable) {
        }
    }

    private fun buildReport(threadName: String, error: Throwable, where: String): String {
        val sw = StringWriter()
        try {
            error.printStackTrace(PrintWriter(sw))
        } catch (_: Throwable) {
        }
        val full = sw.toString()
        val time = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        } catch (t: Throwable) {
            "?"
        }
        return buildString {
            append("时间：").append(time).append('\n')
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
