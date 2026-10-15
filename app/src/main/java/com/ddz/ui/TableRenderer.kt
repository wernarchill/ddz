package com.ddz.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ddz.core.Card

/**
 * 牌桌界面：底牌、三家座位、桌面出牌、手牌、操作按钮、结算浮层。
 *
 * 座位映射：自己永远在下方，另外两家按顺时针映射成「左」「右」，
 * 这样无论服务端把谁排到 0/1/2 号位，本地视角都是稳定的。
 */
class TableRenderer {

    /** 桌面上展示的牌用更小的尺寸 */
    private val miniW = 52f
    private val miniH = 73f
    private val miniStep = 22f

    fun draw(c: Canvas, app: AppController, now: Long) {
        val st = app.state
        val p = app.painter
        val tex = app.textures

        background(c, app)
        drawTopBar(c, app)
        drawBottomCards(c, app)
        drawOpponent(c, app, st.leftSeat(), V.LEFT_PLAY_X, now)
        drawOpponent(c, app, st.rightSeat(), V.RIGHT_PLAY_X, now)
        drawMyArea(c, app, now)
        drawHand(c, app)
        drawButtons(c, app, now)
        drawResult(c, app)
        drawToast(c, app)
    }

    // ---------------------------------------------------------------- 背景与顶栏

    private fun background(c: Canvas, app: AppController) {
        val p = app.painter
        val bmp = app.textures.get("table_bg")
        if (bmp != null) {
            p.bitmap(c, bmp, 0f, 0f, V.W, V.H)
            // 只压很浅的一层：美术的桌面背景是专门画的，压暗太多等于白画。
            // 压这一层是为了让手牌与文字在花哨背景上仍然清晰；嫌暗/嫌亮改这一个数就行。
            p.rect(c, 0f, 0f, V.W, V.H, 0x26000000)
        } else {
            p.rect(c, 0f, 0f, V.W, V.H, UiDraw.FELT)
            p.rect(c, 0f, 0f, V.W, 100f, 0x33000000)
            p.rect(c, 0f, V.H - 60f, V.W, V.H, 0x33000000)
        }
    }

    private fun drawTopBar(c: Canvas, app: AppController) {
        val st = app.state
        val p = app.painter
        val mult = if (st.bombCount > 0) (1 shl minOf(st.bombCount, 10)) else 1

        p.text(c, "底分 ${if (st.baseScore > 0) st.baseScore else 1}", 40f, 46f, 22f, UiDraw.TEXT_MAIN, Paint.Align.LEFT, bold = true)
        p.text(c, "倍数 ×$mult", 40f, 78f, 22f, UiDraw.GOLD, Paint.Align.LEFT, bold = true)
        if (st.bombCount > 0) {
            p.text(c, "炸弹 ${st.bombCount} 个", 40f, 108f, 18f, UiDraw.RED, Paint.Align.LEFT, bold = true)
        }

        UiDraw.button(c, p, app.textures, st, "leave", "退出", V.W - 62f, 42f, 96f, 46f, primary = false, textScale = 0.44f)

        val landlordSeat = st.landlord
        if (landlordSeat >= 0) {
            p.text(
                c, "地主：${seatName(st, landlordSeat)}",
                V.W / 2f, 46f, 20f, UiDraw.TEXT_DIM, Paint.Align.CENTER
            )
        }
    }

    private fun drawBottomCards(c: Canvas, app: AppController) {
        val st = app.state
        val p = app.painter
        val tex = app.textures
        val total = UiDraw.rowWidth(3, V.BOTTOM_CARD_W, 28f)
        var x = V.W / 2f - total / 2f
        p.text(c, "底牌", x - 16f, V.BOTTOM_Y + V.BOTTOM_CARD_H * 0.72f, 20f, UiDraw.TEXT_DIM, Paint.Align.RIGHT)

        for (i in 0 until 3) {
            if (st.landlord >= 0 && i < st.bottom.size) {
                UiDraw.card(c, p, tex, st.bottom[i], x, V.BOTTOM_Y, V.BOTTOM_CARD_W, V.BOTTOM_CARD_H)
            } else {
                UiDraw.cardBack(c, p, tex, x, V.BOTTOM_Y, V.BOTTOM_CARD_W, V.BOTTOM_CARD_H)
            }
            x += 28f
        }
    }

    // ---------------------------------------------------------------- 对手

    private fun drawOpponent(c: Canvas, app: AppController, seat: Int, playCenterX: Float, now: Long) {
        if (seat !in 0..2) return
        val st = app.state
        val p = app.painter
        val tex = app.textures
        val isLeft = playCenterX < V.W / 2f
        val avatarX = if (isLeft) V.LEFT_SEAT_X else V.RIGHT_SEAT_X

        val name = seatName(st, seat)
        val active = st.currentSeat == seat && st.phase != "SETTLED"

        UiDraw.avatar(c, p, tex, avatarX, V.SEAT_Y, V.AVATAR_R, name, highlight = active)
        p.text(c, name, avatarX, V.SEAT_Y + V.AVATAR_R + 28f, 20f, UiDraw.TEXT_MAIN, Paint.Align.CENTER, bold = true)

        val badgeY = V.SEAT_Y + V.AVATAR_R + 60f
        UiDraw.cardCountBadge(c, p, tex, avatarX, badgeY, st.seatCount(seat))

        if (seat == st.landlord) {
            UiDraw.landlordBadge(c, p, tex, avatarX, V.SEAT_Y - V.AVATAR_R - 20f)
        }

        val sv = st.seats.getOrNull(seat)
        var lineY = badgeY + 30f
        if (sv != null && !sv.connected) {
            p.text(c, if (sv.ai) "电脑托管中" else "断线中", avatarX, lineY, 17f, UiDraw.RED, Paint.Align.CENTER, bold = true)
            lineY += 22f
        } else if (sv != null && sv.ai) {
            p.text(c, "电脑", avatarX, lineY, 17f, UiDraw.TEXT_DIM, Paint.Align.CENTER)
            lineY += 22f
        }
        if (sv != null && sv.score != 0) {
            p.text(c, "累计 ${sv.score} 分", avatarX, lineY, 17f, UiDraw.GOLD, Paint.Align.CENTER)
        }

        if (active && st.deadline > 0) {
            drawTimer(c, app, avatarX, V.SEAT_Y, V.AVATAR_R + 16f)
        }

        // 桌面上的牌 / 不出标签
        val cards = st.tablePlays[seat]
        if (cards != null && cards.isNotEmpty()) {
            drawMiniRow(c, app, cards, playCenterX)
        } else if (st.passed[seat] && now - st.lastPlacedAt[seat] < 1600L) {
            UiDraw.playTag(c, p, playCenterX, V.PLAY_Y + miniH / 2f, "不出")
        }
    }

    // ---------------------------------------------------------------- 我方

    private fun drawMyArea(c: Canvas, app: AppController, now: Long) {
        val st = app.state
        val p = app.painter
        val tex = app.textures

        val myName = "我：${st.nickname}"
        p.text(c, myName, 40f, V.H - 84f, 20f, UiDraw.TEXT_MAIN, Paint.Align.LEFT, bold = true)
        p.text(c, "剩 ${st.hand.size} 张", 40f, V.H - 58f, 18f, UiDraw.TEXT_DIM, Paint.Align.LEFT)

        if (st.mySeat == st.landlord) {
            UiDraw.landlordBadge(c, p, tex, 40f + p.textWidth(myName, 20f, true) + 60f, V.H - 92f)
        }

        val cards = st.tablePlays[st.mySeat]
        if (cards != null && cards.isNotEmpty()) {
            drawMiniRow(c, app, cards, V.MY_PLAY_X)
        } else if (st.passed[st.mySeat] && now - st.lastPlacedAt[st.mySeat] < 1600L) {
            UiDraw.playTag(c, p, V.MY_PLAY_X, V.PLAY_Y + miniH / 2f, "不出")
        }
    }

    private fun drawMiniRow(c: Canvas, app: AppController, cards: List<Card>, centerX: Float) {
        val p = app.painter
        val tex = app.textures
        // 牌太多时压缩间距，保证不会画出屏幕
        val step = if (cards.size <= 6) miniStep else minOf(miniStep, (V.W * 0.42f) / (cards.size - 1))
        val total = UiDraw.rowWidth(cards.size, miniW, step)
        var x = centerX - total / 2f
        for (card in cards) {
            UiDraw.card(c, p, tex, card, x, V.PLAY_Y, miniW, miniH)
            x += step
        }
    }

    // ---------------------------------------------------------------- 手牌

    private fun drawHand(c: Canvas, app: AppController) {
        val st = app.state
        val p = app.painter
        val tex = app.textures
        val n = st.hand.size
        if (n == 0) return

        val step = if (n <= 1) 0f else {
            minOf(V.CARD_W * 0.55f, (V.HAND_MAX_W - V.CARD_W) / (n - 1))
        }
        val total = UiDraw.rowWidth(n, V.CARD_W, step)
        var x = V.HAND_CENTER_X - total / 2f

        for (i in st.hand.indices) {
            val card = st.hand[i]
            val selected = st.selected.contains(i)
            val y = if (selected) V.HAND_Y - 26f else V.HAND_Y
            UiDraw.card(c, p, tex, card, x, y, V.CARD_W, V.CARD_H)
            if (selected) {
                p.roundRectStroke(c, x, y, x + V.CARD_W, y + V.CARD_H, V.CARD_RADIUS, UiDraw.GOLD, 3f)
            }
            st.hits.add(HitRegion("card", RectF(x, y, x + V.CARD_W, y + V.CARD_H), i))
            x += step
        }
    }

    // ---------------------------------------------------------------- 操作按钮

    private fun drawButtons(c: Canvas, app: AppController, now: Long) {
        val st = app.state
        val p = app.painter
        val tex = app.textures
        val myTurn = st.currentSeat == st.mySeat

        if (st.phase == "BIDDING" && myTurn) {
            val items = listOf("bid0" to "不叫", "bid1" to "1 分", "bid2" to "2 分", "bid3" to "3 分")
            for ((i, item) in items.withIndex()) {
                UiDraw.button(
                    c, p, tex, st, item.first, item.second,
                    V.BTN_COLUMN_X, 470f + i * 62f, 132f, 52f,
                    primary = i > 0
                )
            }
            drawTimer(c, app, V.BTN_COLUMN_X, 420f, 32f)
            p.text(c, "请叫分", V.BTN_COLUMN_X, 690f, 20f, UiDraw.GOLD, Paint.Align.CENTER, bold = true)
            return
        }

        if (st.phase == "PLAYING" && myTurn) {
            UiDraw.button(c, p, tex, st, "hint", "提示", V.BTN_COLUMN_X, 540f, 132f, 52f, primary = false)
            UiDraw.button(c, p, tex, st, "pass", "不出", V.BTN_COLUMN_X, 605f, 132f, 52f, primary = false)
            UiDraw.button(c, p, tex, st, "play", "出牌", V.BTN_COLUMN_X, 670f, 132f, 52f, primary = true)
            drawTimer(c, app, V.BTN_COLUMN_X, 420f, 32f)
            if (st.mustPlay) {
                p.text(c, "你是首家，必须出牌", V.BTN_COLUMN_X, 490f, 17f, UiDraw.GOLD, Paint.Align.CENTER, bold = true)
            }
            return
        }

        // 不是自己回合：给一句状态提示
        val who = if (st.currentSeat == st.mySeat) "你" else seatName(st, st.currentSeat)
        val text = when {
            st.phase == "BIDDING" -> "等待 $who 叫分…"
            st.phase == "PLAYING" -> "等待 $who 出牌…"
            st.phase == "SETTLED" -> "本局结束"
            else -> ""
        }
        if (text.isNotEmpty()) {
            p.text(c, text, V.BTN_COLUMN_X, 640f, 20f, UiDraw.TEXT_DIM, Paint.Align.CENTER)
            if (st.currentSeat == st.mySeat) drawTimer(c, app, V.BTN_COLUMN_X, 420f, 32f)
        }
    }

    /** 倒计时环 */
    private fun drawTimer(c: Canvas, app: AppController, cx: Float, cy: Float, radius: Float) {
        val st = app.state
        val now = System.currentTimeMillis()
        val left = st.deadline - now
        if (left <= 0) return
        val ratio = (left.toFloat() / st.deadlineTotalMs.toFloat()).coerceIn(0f, 1f)
        UiDraw.timerRing(c, app.painter, app.textures, cx, cy, radius, ratio, (left / 1000).toInt())
    }

    // ---------------------------------------------------------------- 结算

    private fun drawResult(c: Canvas, app: AppController) {
        val r = app.state.result ?: return
        val st = app.state
        val p = app.painter
        val tex = app.textures

        p.rect(c, 0f, 0f, V.W, V.H, 0xB3000000.toInt())
        UiDraw.panel(c, p, tex, 340f, 104f, 940f, 616f)

        val iAmLandlord = st.mySeat == r.landlord
        val iWon = if (iAmLandlord) r.landlordWon else !r.landlordWon

        p.text(
            c, if (r.landlordWon) "地主胜利" else "农民胜利",
            V.W / 2f, 168f, 40f, UiDraw.GOLD, Paint.Align.CENTER, bold = true
        )
        p.text(
            c, if (iWon) "你赢了" else "你输了",
            V.W / 2f, 212f, 26f,
            if (iWon) 0xFF4CAF50.toInt() else UiDraw.RED,
            Paint.Align.CENTER, bold = true
        )

        val tags = ArrayList<String>(4)
        if (r.spring) tags.add("春天 ×2")
        if (r.antiSpring) tags.add("反春 ×2")
        if (r.bombCount > 0) tags.add("炸弹 ${r.bombCount} 个")
        tags.add("底分 ${r.baseScore}")
        tags.add("总倍数 ×${r.multiplier}")
        p.text(c, tags.joinToString("   "), V.W / 2f, 250f, 19f, UiDraw.TEXT_MAIN, Paint.Align.CENTER)

        for (i in 0..2) {
            val y = 288f + i * 68f
            val isMe = i == st.mySeat
            UiDraw.panel(c, p, tex, 380f, y, 900f, y + 58f, 10f)
            val role = if (i == r.landlord) "地主" else "农民"
            p.text(
                c, "${seatName(st, i)}（$role）${if (isMe) " ← 你" else ""}",
                404f, y + 36f, 20f,
                if (isMe) UiDraw.GOLD else UiDraw.TEXT_MAIN,
                Paint.Align.LEFT, bold = isMe
            )
            val delta = r.deltas.getOrElse(i) { 0 }
            p.text(
                c, if (delta >= 0) "+$delta" else "$delta",
                800f, y + 36f, 22f,
                if (delta >= 0) 0xFF66BB6A.toInt() else UiDraw.RED,
                Paint.Align.RIGHT, bold = true
            )
            p.text(
                c, "总 ${r.totals.getOrElse(i) { 0 }}",
                876f, y + 36f, 18f, UiDraw.TEXT_DIM, Paint.Align.RIGHT
            )
        }

        UiDraw.button(c, p, tex, st, "restart", "再来一局", 510f, 560f, 200f, 56f, primary = true)
        UiDraw.button(c, p, tex, st, "leave", "返回大厅", 770f, 560f, 200f, 56f, primary = false, textScale = 0.38f)
    }

    // ---------------------------------------------------------------- 杂项

    private fun seatName(st: ViewState, seat: Int): String {
        if (seat !in 0..2) return "—"
        if (seat == st.mySeat) return st.nickname.ifBlank { "我" }
        val n = st.seats.getOrNull(seat)?.name
        return if (n.isNullOrBlank()) "玩家${seat + 1}" else n
    }

    private fun drawToast(c: Canvas, app: AppController) {
        val text = app.state.toastText ?: return
        val p = app.painter
        val w = p.textWidth(text, 22f, true) + 60f
        p.roundRect(c, V.W / 2f - w / 2f, V.H - 52f, V.W / 2f + w / 2f, V.H - 8f, 18f, 0xD9000000.toInt())
        p.text(c, text, V.W / 2f, V.H - 22f, 22f, UiDraw.TEXT_MAIN, Paint.Align.CENTER, bold = true)
    }
}
