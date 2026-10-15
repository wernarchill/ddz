package com.ddz.ui

import android.graphics.Canvas
import android.graphics.Paint

/**
 * 大厅与房间界面。
 *
 * 布局清单：
 *  - 左栏：昵称、创建房间、本机 IP 提示
 *  - 右栏：局域网自动发现的房间列表 + 手动输入 IP 兜底
 *  - 房间页：三个座位 + 准备 / 电脑补齐 / 返回
 */
class LobbyRenderer {

    /** 资源清单只在第一次打开时扫描一次 assets，之后走缓存（否则每帧都要开 55 个文件） */
    private var cachedMissing: List<String>? = null

    fun drawLobby(c: Canvas, app: AppController, now: Long) {
        val st = app.state
        val p = app.painter
        val tex = app.textures

        background(c, app)
        title(c, app)

        // ---------------- 左栏：身份与创建 ----------------
        UiDraw.panel(c, p, tex, 60f, 156f, 560f, 596f)

        p.text(c, "你的昵称", 92f, 204f, 20f, UiDraw.TEXT_DIM)
        UiDraw.panel(c, p, tex, 92f, 216f, 420f, 268f, 10f)
        p.text(c, st.nickname, 108f, 252f, 24f, UiDraw.TEXT_MAIN, Paint.Align.LEFT, bold = true)
        UiDraw.button(c, p, tex, st, "edit_name", "修改", 490f, 242f, 110f, 46f, primary = false)

        p.text(c, "本机 IP（朋友需要输这个）", 92f, 322f, 20f, UiDraw.TEXT_DIM)
        p.text(c, "${st.myIp} : ${com.ddz.net.HostServer.DEFAULT_PORT}", 92f, 358f, 26f, UiDraw.GOLD, Paint.Align.LEFT, bold = true)

        UiDraw.button(c, p, tex, st, "create", "创建房间（我当房主）", 310f, 452f, 400f, 64f, primary = true, textScale = 0.36f)

        p.text(c, "房主负责发牌与判定，其他人加入房主的房间。", 92f, 512f, 16f, UiDraw.TEXT_DIM)
        p.text(c, "同一 WiFi 下可直接加入；跨网络需要房主做端口映射。", 92f, 536f, 16f, UiDraw.TEXT_DIM)
        p.text(c, "人数不足时可用「电脑补齐」一个人也能玩。", 92f, 560f, 16f, UiDraw.TEXT_DIM)

        // ---------------- 右栏：加入房间 ----------------
        UiDraw.panel(c, p, tex, 600f, 156f, 1220f, 596f)
        p.text(c, "附近的房间", 632f, 200f, 22f, UiDraw.TEXT_MAIN, Paint.Align.LEFT, bold = true)
        if (st.rooms.isEmpty()) {
            p.text(
                c, if (st.scanning) "正在搜索…（若一直搜不到，请用下方手动输入）" else "未开启搜索",
                632f, 240f, 18f, UiDraw.TEXT_DIM
            )
        }

        var y = 216f
        for ((index, room) in st.rooms.take(4).withIndex()) {
            UiDraw.panel(c, p, tex, 632f, y, 1188f, y + 66f, 10f)
            p.text(c, room.name, 650f, y + 28f, 20f, UiDraw.TEXT_MAIN, Paint.Align.LEFT, bold = true)
            p.text(
                c, "${room.ip}:${room.port}   ${room.players}/${room.maxPlayers} 人",
                650f, y + 52f, 16f, UiDraw.TEXT_DIM
            )
            p.text(c, "点击加入 →", 1170f, y + 42f, 18f, UiDraw.GOLD, Paint.Align.RIGHT, bold = true)
            st.hits.add(HitRegion("join_room", android.graphics.RectF(632f, y, 1188f, y + 66f), index))
            y += 76f
        }

        // 手动输入 IP
        val manualY = maxOf(y + 12f, 480f)
        p.text(c, "手动输入房主 IP", 632f, manualY, 20f, UiDraw.TEXT_DIM)
        UiDraw.panel(c, p, tex, 632f, manualY + 12f, 1040f, manualY + 64f, 10f)
        p.text(
            c, st.manualIp.ifBlank { "点这里输入，例如 192.168.1.5" },
            648f, manualY + 47f, 20f,
            if (st.manualIp.isBlank()) UiDraw.TEXT_DIM else UiDraw.TEXT_MAIN
        )
        st.hits.add(
            HitRegion(
                "edit_ip",
                android.graphics.RectF(632f, manualY + 12f, 1040f, manualY + 64f)
            )
        )
        UiDraw.button(c, p, tex, st, "connect", "连接", 1120f, manualY + 38f, 130f, 52f, primary = true)

        // ---------------- 底栏 ----------------
        UiDraw.button(c, p, tex, st, "toggle_sound", if (app.sfx.enabled) "音效：开" else "音效：关", 120f, 664f, 160f, 48f, primary = false, textScale = 0.38f)
        UiDraw.button(c, p, tex, st, "checklist", "贴图清单", 300f, 664f, 160f, 48f, primary = false, textScale = 0.38f)
        p.text(
            c, "提示：先由一人「创建房间」，把上面显示的 IP 告诉其他人，其他人用「手动输入 IP」连接。",
            V.W / 2f + 120f, 672f, 17f, UiDraw.TEXT_DIM, Paint.Align.CENTER
        )

        drawToast(c, app, now)
    }

    fun drawRoom(c: Canvas, app: AppController, now: Long) {
        val st = app.state
        val p = app.painter
        val tex = app.textures

        background(c, app)

        p.text(c, "房间号 ${st.roomId}", V.W / 2f, 76f, 46f, UiDraw.GOLD, Paint.Align.CENTER, bold = true)
        if (st.isHost) {
            if (st.hostServerRunning) {
                p.text(
                    c, "把下面这个地址告诉朋友：${st.myIp} : ${com.ddz.net.HostServer.DEFAULT_PORT}",
                    V.W / 2f, 112f, 22f, UiDraw.TEXT_MAIN, Paint.Align.CENTER
                )
            } else {
                p.text(
                    c, "端口 9527 被占用，本房间只能和电脑打（别人连不进来）",
                    V.W / 2f, 112f, 22f, UiDraw.RED, Paint.Align.CENTER, bold = true
                )
            }
        } else {
            p.text(c, "已连接房主 ${st.hostIp}", V.W / 2f, 112f, 22f, UiDraw.TEXT_MAIN, Paint.Align.CENTER)
        }

        // 三个座位
        for (i in 0..2) {
            val x = 240f + i * 400f
            val sv = st.seats[i]
            UiDraw.panel(c, p, tex, x - 170f, 176f, x + 170f, 420f)

            val label = when {
                !sv.occupied -> "空位"
                sv.ai -> "电脑"
                sv.connected -> "已连接"
                else -> "断线中"
            }
            UiDraw.avatar(c, p, tex, x, 250f, 48f, sv.name.ifBlank { "?" }, highlight = sv.ready)

            p.text(
                c, (if (i == st.mySeat) "我：" else "") + sv.name.ifBlank { "空位" },
                x, 330f, 22f, UiDraw.TEXT_MAIN, Paint.Align.CENTER, bold = true
            )
            p.text(c, label, x, 358f, 18f, UiDraw.TEXT_DIM, Paint.Align.CENTER)
            if (sv.score != 0) {
                p.text(c, "累计 ${sv.score} 分", x, 384f, 18f, UiDraw.GOLD, Paint.Align.CENTER)
            }
            if (sv.occupied) {
                val readyText = if (sv.ready) "已准备" else "未准备"
                val color = if (sv.ready) 0xFF4CAF50.toInt() else 0xFFBDBDBD.toInt()
                p.text(c, readyText, x, 406f, 18f, color, Paint.Align.CENTER, bold = true)
            }
        }

        // 按钮
        val myReady = st.seats.getOrNull(st.mySeat)?.ready == true
        UiDraw.button(
            c, p, tex, st, "ready",
            if (myReady) "取消准备" else "准备",
            V.W / 2f - 240f, 500f, 220f, 62f, primary = true
        )
        if (st.isHost) {
            val canFill = st.seats.any { !it.occupied }
            UiDraw.button(
                c, p, tex, st, "start_ai",
                if (canFill) "开始游戏（电脑补齐）" else "开始游戏",
                V.W / 2f + 40f, 500f, 320f, 62f, primary = true, textScale = 0.34f
            )
        }
        UiDraw.button(c, p, tex, st, "leave", "返回大厅", V.W / 2f + 370f, 500f, 200f, 62f, primary = false, textScale = 0.36f)

        p.text(
            c, "三人全部「准备」后会自动开局；人数不足点「电脑补齐」即可单人试玩。",
            V.W / 2f, 580f, 18f, UiDraw.TEXT_DIM, Paint.Align.CENTER
        )

        drawToast(c, app, now)
    }

    fun drawChecklist(c: Canvas, app: AppController) {
        val p = app.painter
        if (cachedMissing == null) cachedMissing = app.textures.missingReport()
        val missing = cachedMissing ?: emptyList()

        p.rect(c, 0f, 0f, V.W, V.H, 0xE6000000.toInt())
        UiDraw.panel(c, p, app.textures, 100f, 60f, V.W - 100f, V.H - 60f)

        // 铺一层全屏「拦截」热区，挡住底层界面的按钮。
        // 少了它，点浮层空白处会穿透到下面大厅的按钮上（例如误触「创建房间」）。
        // 它先于下面的「关闭」按钮登记，所以关闭按钮在更上层、仍可点击。
        app.state.hits.add(HitRegion("noop", android.graphics.RectF(0f, 0f, V.W, V.H)))

        p.text(c, "贴图资源清单", V.W / 2f, 112f, 34f, UiDraw.GOLD, Paint.Align.CENTER, bold = true)
        p.text(
            c, "把 PNG 放到 app/src/main/assets/textures/ 下即可自动生效（文件名必须一致）",
            V.W / 2f, 146f, 18f, UiDraw.TEXT_DIM, Paint.Align.CENTER
        )

        if (missing.isEmpty()) {
            p.text(c, "必需贴图已全部就位 ✓", V.W / 2f, 260f, 26f, 0xFF4CAF50.toInt(), Paint.Align.CENTER, bold = true)
        } else {
            p.text(
                c, "当前缺失 ${missing.size} 张（程序已自动用占位图代替，不影响游玩）",
                V.W / 2f, 186f, 20f, UiDraw.TEXT_MAIN, Paint.Align.CENTER
            )
            val columns = 4
            val perColumn = (missing.size + columns - 1) / columns
            for ((index, name) in missing.withIndex()) {
                val col = index / perColumn
                val row = index % perColumn
                val x = 140f + col * 262f
                val y = 226f + row * 24f
                p.text(c, "· $name", x, y, 17f, UiDraw.TEXT_DIM)
            }
        }

        p.text(
            c, "共需：54 张牌面 + 1 张牌背 + 桌面背景 + 按钮/面板/头像/标记 + 可选特效帧",
            V.W / 2f, V.H - 118f, 18f, UiDraw.TEXT_DIM, Paint.Align.CENTER
        )
        // 注意参数顺序：UiDraw.button(canvas, painter, textures, state, id, label, cx, cy, w, h, ...)
        // 这里曾经漏传 textures，导致后面所有实参整体错位一格。
        UiDraw.button(
            c, p, app.textures, app.state,
            "close_checklist", "关闭",
            V.W / 2f, V.H - 92f, 180f, 50f,
            primary = true
        )
    }

    /**
     * 大厅背景用 `lobby_bg`（别名表会兼容 `lobby_bg_land` / `lobby_bg_port`），
     * 取不到再退回牌桌背景 `table_bg`，最后才是纯色兜底。
     */
    private fun background(c: Canvas, app: AppController) {
        val p = app.painter
        val bmp = app.textures.get("lobby_bg") ?: app.textures.get("table_bg")
        if (bmp != null) {
            p.bitmap(c, bmp, 0f, 0f, V.W, V.H)
            // 同牌桌：只压很浅一层，保留美术细节
            p.rect(c, 0f, 0f, V.W, V.H, 0x33000000)
        } else {
            p.rect(c, 0f, 0f, V.W, V.H, UiDraw.FELT)
            p.rect(c, 0f, 0f, V.W, 120f, 0x33000000)
        }
    }

    /**
     * 标题：有 Logo 贴图就用图（保持宽高比、限高），否则退回文字。
     * 美术包里的名字可能是 `logo` 或 `splash_logo`，别名表都认。
     */
    private fun title(c: Canvas, app: AppController) {
        val p = app.painter
        val logo = app.textures.get("logo")
        if (logo != null && logo.height > 0) {
            val maxH = 88f
            val ratio = logo.width.toFloat() / logo.height.toFloat()
            val h = maxH
            val w = (h * ratio).coerceAtMost(V.W * 0.5f)
            p.bitmap(c, logo, V.W / 2f - w / 2f, 22f, V.W / 2f + w / 2f, 22f + h)
            p.text(c, "局域网联机 · 三人标准局", V.W / 2f, 134f, 20f, UiDraw.TEXT_DIM, Paint.Align.CENTER)
        } else {
            p.text(c, "斗地主", V.W / 2f, 92f, 58f, UiDraw.GOLD, Paint.Align.CENTER, bold = true)
            p.text(c, "局域网联机 · 三人标准局", V.W / 2f, 124f, 20f, UiDraw.TEXT_DIM, Paint.Align.CENTER)
        }
    }

    private fun drawToast(c: Canvas, app: AppController, now: Long) {
        val text = app.state.toastText ?: return
        val p = app.painter
        val w = p.textWidth(text, 22f, true) + 60f
        p.roundRect(c, V.W / 2f - w / 2f, V.H - 74f, V.W / 2f + w / 2f, V.H - 22f, 18f, 0xCC000000.toInt())
        p.text(c, text, V.W / 2f, V.H - 38f, 22f, UiDraw.TEXT_MAIN, Paint.Align.CENTER, bold = true)
    }
}
