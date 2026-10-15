package com.ddz

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.ddz.ui.GameSurfaceView

/**
 * 唯一的 Activity：整屏沉浸式，全部绘制交给 [GameSurfaceView] 的 Canvas 渲染线程。
 */
class MainActivity : Activity() {

    private lateinit var surface: GameSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 对局中不息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enterImmersive()

        surface = GameSurfaceView(this)
        setContentView(surface)

        requestNotificationPermissionIfNeeded()
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
        surface.onResume()
        enterImmersive()
    }

    override fun onPause() {
        surface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        surface.onDestroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 先交给游戏处理（返回上一级界面 / 关闭弹窗），没人处理才退出
        if (!surface.handleBackPressed()) {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQ_NOTIFICATION = 1001
    }
}
