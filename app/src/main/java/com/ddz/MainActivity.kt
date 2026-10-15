package com.ddz

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ddz.ui.GameSurfaceView

/**
 * 唯一的 Activity：整屏沉浸式，全部绘制交给 [GameSurfaceView] 的 Canvas 渲染线程。
 */
class MainActivity : Activity() {

    private lateinit var surface: GameSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 最先安装崩溃记录器：这样即使后面崩了（包括渲染线程、网络线程），
        // 也能把堆栈写下来。
        CrashLog.install(this)

        // 上次启动崩过：**先用纯文本视图把报告显示出来，完全不创建游戏视图**。
        // 这一点很关键 —— 如果崩溃发生在 AppController / GameSurfaceView 的构造过程里，
        // 每次启动都会在同一处崩；若还去创建游戏视图，用户就永远看不到报告了。
        val report = CrashLog.consume(this)
        if (report != null) {
            showCrashReport(report)
            return
        }

        // 对局中不息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enterImmersive()

        surface = GameSurfaceView(this)
        setContentView(surface)

        requestNotificationPermissionIfNeeded()
    }

    /**
     * 崩溃报告屏。
     *
     * 只用框架自带的 ScrollView / TextView / Button，**不碰自绘视图、不碰 AppController**，
     * 因此即使游戏本体启动就崩，这一屏也一定显示得出来。
     * 文本可选中，用户既能截图，也能长按复制后直接粘贴给我。
     */
    private fun showCrashReport(report: String) {
        val text = TextView(this).apply {
            this.text = report
            textSize = 11f
            setPadding(28, 28, 28, 28)
            setTextIsSelectable(true) // 可长按选中复制
        }
        val scroll = ScrollView(this).apply {
            addView(text)
        }
        val button = Button(this).apply {
            this.text = "清除报告并重新启动"
            setOnClickListener {
                // 报告文件在 consume() 时已被删除，直接重建即可进入正常流程
                recreate()
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(root)
    }

    /** 全屏沉浸：状态栏与导航栏隐藏，向下滑才临时显示 */
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /** Android 13+ 才需要运行时申请；用户拒绝也不影响对战，只少了「轮到你」提醒 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    override fun onResume() {
        super.onResume()
        // 必须判 isInitialized：崩溃报告屏那条路径会在 onCreate 里提前 return，
        // 此时 surface 从未被赋值，直接访问会抛 UninitializedPropertyAccessException
        // —— 崩溃屏自己反而成了崩溃源。
        if (::surface.isInitialized) surface.onResume()
        enterImmersive()
    }

    override fun onPause() {
        if (::surface.isInitialized) surface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::surface.isInitialized) surface.onDestroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 先交给游戏处理（返回上一级界面 / 关闭弹窗），没人处理才退出
        val handled = if (::surface.isInitialized) surface.handleBackPressed() else false
        if (!handled) {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQ_NOTIFICATION = 1001
    }
}
