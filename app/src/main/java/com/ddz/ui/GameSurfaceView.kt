package com.ddz.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.ddz.CrashLog

/**
 * 唯一的自绘视图：一个渲染线程 + 一块 Surface。
 *
 * 为什么用 SurfaceView 而不是普通 View：
 *  - 渲染在独立线程，不受 UI 线程消息队列影响，出牌动画不会因为主线程卡顿而掉帧；
 *  - 每帧 lockCanvas 直接写绘制缓冲，没有 View 树的 measure/layout 开销。
 *
 * 触摸坐标会从屏幕坐标反算回 1280×720 的虚拟坐标系，所以布局代码只写一次。
 */
class GameSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, TextInputHost {

    private val controller = AppController(context, this)
    private val lobbyRenderer = LobbyRenderer()
    private val tableRenderer = TableRenderer()

    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    /**
     * 虚拟坐标 → 屏幕坐标的映射。
     *
     * 由主线程在 surfaceChanged 里写、渲染线程在每帧读，所以必须 @Volatile，
     * 否则渲染线程可能长期读到旧的缩放值（表现为画面比例错乱且不再恢复）。
     */
    @Volatile
    private var scale = 1f

    @Volatile
    private var offsetX = 0f

    @Volatile
    private var offsetY = 0f

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    // ---------------------------------------------------------------- 生命周期

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val s = minOf(width / V.W, height / V.H)
        scale = if (s <= 0f) 1f else s
        offsetX = (width - V.W * scale) / 2f
        offsetY = (height - V.H * scale) / 2f
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRender()
    }

    fun onResume() {
        if (holder.surface?.isValid == true) startRender()
    }

    fun onPause() {
        stopRender()
    }

    fun onDestroy() {
        stopRender()
        // stopRender 的 join 有超时；即使渲染线程理论上还活着，release() 也会先把自己标记为
        // 已停止，让 tick() 立刻变成空操作，缩小与渲染线程并发的窗口。
        controller.release()
    }

    fun handleBackPressed(): Boolean = controller.handleBack()

    /**
     * 启动渲染线程。
     *
     * **绝不能出现两个渲染线程**：它们会同时驱动同一份 `HostSession`/`ViewState`，
     * 等价于把「单线程状态机」这个前提彻底破坏掉。
     * 所以如果上一个线程还没退干净（`stopRender` 的 join 有超时），
     * 这里再等一次；仍然活着就放弃启动 —— 宁可这一帧画面不恢复，也不能双线程驱动。
     */
    private fun startRender() {
        if (running) return
        val old = renderThread
        if (old != null && old.isAlive) {
            try {
                old.join(1500)
            } catch (_: InterruptedException) {
            }
            if (old.isAlive) return
        }
        running = true
        renderThread = Thread({ renderLoop() }, "ddz-render").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopRender() {
        running = false
        try {
            renderThread?.join(800)
        } catch (_: InterruptedException) {
        }
        renderThread = null
    }

    private fun renderLoop() {
        while (running) {
            val frameStart = System.currentTimeMillis()
            try {
                controller.tick(frameStart)

                val canvas = try {
                    holder.lockCanvas()
                } catch (e: Exception) {
                    null
                }
                if (canvas == null) {
                    Thread.sleep(16)
                    continue
                }
                try {
                    drawFrame(canvas, frameStart)
                } finally {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            } catch (t: Throwable) {
                // 注意这里是 Throwable 而不是 Exception。
                // 渲染线程里抛出的 Error（OutOfMemoryError、StackOverflowError、
                // NoClassDefFoundError 等）同样会杀掉整个进程，而 Exception 接不住它们 ——
                // 表现就是"一打开就闪退"且任何界面都看不到。这里接住并记录下来，
                // 既能让应用活下去，又能把堆栈留给下次启动显示。
                CrashLog.record(t, "渲染线程")
                try {
                    Thread.sleep(32)
                } catch (_: InterruptedException) {
                    return
                }
            }

            val cost = System.currentTimeMillis() - frameStart
            val sleep = 16 - cost
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun drawFrame(canvas: Canvas, now: Long) {
        // release() 可能在渲染线程还没退干净时就被主线程调用（stopRender 的 join 有超时）。
        // 这里再挡一道，避免已经释放的纹理被继续绘制。
        if (controller.isReleased()) return

        canvas.drawColor(Color.BLACK)
        controller.state.hits.clear()

        val save = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, V.W, V.H)

        when (controller.state.screen) {
            Screen.LOBBY -> lobbyRenderer.drawLobby(canvas, controller, now)
            Screen.ROOM -> lobbyRenderer.drawRoom(canvas, controller, now)
            Screen.TABLE -> tableRenderer.draw(canvas, controller, now)
        }
        if (controller.state.showChecklist) lobbyRenderer.drawChecklist(canvas, controller)

        canvas.restoreToCount(save)
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x - offsetX) / scale
        val y = (event.y - offsetY) / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                controller.onTouchDown(x, y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                controller.onTouchUp(x, y)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                controller.onTouchDown(-1000f, -1000f)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 让 Activity 的 onBackPressed 统一处理，这里不消费
            return false
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- 文本输入

    /**
     * 用系统 AlertDialog + EditText 拉起重输入法。
     *
     * 为什么不自己在 Canvas 上画键盘：中文昵称没法用自绘键盘解决，
     * 而 IP 输入用系统键盘也更省事、更符合用户习惯。SurfaceView 无法直接接收 IME 输入，
     * 所以这里必须借助一个真实的可编辑 View —— 系统对话框是最省心的做法。
     */
    override fun requestText(
        title: String,
        initial: String,
        maxLength: Int,
        numeric: Boolean,
        onDone: (String) -> Unit
    ) {
        post {
            try {
                val edit = EditText(context)
                edit.setText(initial)
                edit.setSelection(edit.text.length)
                edit.filters = arrayOf(InputFilter.LengthFilter(maxLength))
                edit.inputType = if (numeric) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                edit.imeOptions = EditorInfo.IME_ACTION_DONE
                edit.setSingleLine(true)

                val dialog = AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(edit)
                    .setPositiveButton("确定") { _, _ -> onDone(edit.text.toString()) }
                    .setNegativeButton("取消", null)
                    .create()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                dialog.show()

                edit.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {
                // 输入框拉不起来也不能让游戏崩。
                // 注意这里在**主线程**上，不能直接改状态，必须投递给渲染线程。
                controller.toastFromAnyThread("无法打开输入框：${e.message}")
            }
        }
    }
}
