package com.ddz.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ddz.core.Card
import com.ddz.core.CardConst

/**
 * 界面绘制的公共零件：按钮、牌、面板、倒计时环。
 *
 * 每个零件在绘制的同时把可点击区域登记到 [ViewState.hits]，
 * 因此「画在哪」和「点得到哪」永远是同一份数据，不会出现视觉与热区错位。
 */
object UiDraw {

    // 主题色（贴图缺失时的兜底配色）
    const val GOLD = 0xFFE8B44A.toInt()
    const val DARK_PANEL = 0xCC0E1F17.toInt()
    const val TEXT_MAIN = 0xFFF2F2F2.toInt()
    const val TEXT_DIM = 0xFF9FB3A8.toInt()
    const val RED = 0xFFD32F2F.toInt()
    const val DARK_TEXT = 0xFF212121.toInt()
    const val FELT = 0xFF1B4332.toInt()

    /** 通用按钮 */
    fun button(
        c: Canvas,
        p: Painter,
        tex: Textures,
        st: ViewState,
        id: String,
        label: String,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        enabled: Boolean = true,
        primary: Boolean = true,
        textScale: Float = 0.42f
    ) {
        val l = cx - w / 2f
        val t = cy - h / 2f
        val r = cx + w / 2f
        val b = cy + h / 2f
        val name = if (primary) "btn_primary_normal" else "btn_secondary_normal"
        val bmp = tex.get(name)
        if (bmp != null) {
            p.bitmapAlpha(c, bmp, l, t, r, b, if (enabled) 255 else 120)
        } else {
            p.roundRect(c, l, t, r, b, h / 2f, if (primary) 0xFFC97B1E.toInt() else 0xFF2E5748.toInt())
            p.roundRectStroke(c, l, t, r, b, h / 2f, 0x55FFFFFF, 2f)
        }
        p.text(
            c, label, cx, cy + h * 0.17f, h * textScale,
            if (enabled) TEXT_MAIN else 0x88FFFFFF.toInt(),
            Paint.Align.CENTER, bold = true
        )
        if (enabled) st.hits.add(HitRegion(id, RectF(l, t, r, b)))
    }

    /** 半透明面板 */
    fun panel(c: Canvas, p: Painter, tex: Textures, l: Float, t: Float, r: Float, b: Float, radius: Float = 14f) {
        val bmp = tex.get("panel_bg")
        if (bmp != null) p.bitmap(c, bmp, l, t, r, b)
        else p.roundRect(c, l, t, r, b, radius, DARK_PANEL)
    }

    /** 一张牌（正面）。贴图缺失时画程序化占位牌 */
    fun card(
        c: Canvas,
        p: Painter,
        tex: Textures,
        card: Card,
        x: Float, y: Float, w: Float, h: Float,
        alpha: Int = 255
    ) {
        val bmp = tex.cardTexture(card, Textures.CARD_TEX_W)
        if (bmp != null) {
            p.bitmapAlpha(c, bmp, x, y, x + w, y + h, alpha)
            return
        }
        // ---- 占位牌 ----
        p.roundRect(c, x, y, x + w, y + h, V.CARD_RADIUS, 0xFFFAFAFA.toInt())
        p.roundRectStroke(c, x, y, x + w, y + h, V.CARD_RADIUS, 0xFF8D8D8D.toInt(), 2f)
        val isRed = card.suit == CardConst.HEART || card.suit == CardConst.DIAMOND ||
            card.rank == CardConst.JOKER_BIG
        val color = if (isRed) RED else DARK_TEXT
        p.text(c, card.label, x + w / 2f, y + h * 0.44f, w * 0.44f, color, Paint.Align.CENTER, bold = true)
        val suitChar = when {
            card.rank == CardConst.JOKER_SMALL -> "小"
            card.rank == CardConst.JOKER_BIG -> "大"
            card.suit == CardConst.SPADE -> "♠"
            card.suit == CardConst.HEART -> "♥"
            card.suit == CardConst.CLUB -> "♣"
            else -> "♦"
        }
        p.text(c, suitChar, x + w / 2f, y + h * 0.78f, w * 0.34f, color, Paint.Align.CENTER)
    }

    /** 牌背 */
    fun cardBack(
        c: Canvas,
        p: Painter,
        tex: Textures,
        x: Float, y: Float, w: Float, h: Float,
        alpha: Int = 255
    ) {
        val bmp = tex.get("card_back", Textures.CARD_TEX_W)
        if (bmp != null) {
            p.bitmapAlpha(c, bmp, x, y, x + w, y + h, alpha)
            return
        }
        p.roundRect(c, x, y, x + w, y + h, V.CARD_RADIUS, 0xFF2C4C7C.toInt())
        p.roundRectStroke(c, x, y, x + w, y + h, V.CARD_RADIUS, 0xFF9FB6D8.toInt(), 2f)
    }

    /**
     * 一排牌。返回实际占用的宽度，便于调用方居中。
     * [step] 是相邻两张牌的间距，小于牌宽就是叠压显示。
     */
    fun cardRow(
        c: Canvas,
        p: Painter,
        tex: Textures,
        cards: List<Card>,
        startX: Float,
        y: Float,
        w: Float,
        h: Float,
        step: Float,
        alpha: Int = 255
    ) {
        var x = startX
        for (card in cards) {
            card(c, p, tex, card, x, y, w, h, alpha)
            x += step
        }
    }

    /** 计算一排牌的总宽度 */
    fun rowWidth(count: Int, w: Float, step: Float): Float {
        if (count <= 0) return 0f
        return w + step * (count - 1)
    }

    /** 倒计时环：剩余比例 [ratio]（1 -> 满圈） */
    fun timerRing(
        c: Canvas,
        p: Painter,
        tex: Textures,
        cx: Float, cy: Float, radius: Float,
        ratio: Float,
        seconds: Int
    ) {
        val bmp = tex.get("icon_clock_ring")
        if (bmp != null) {
            p.bitmapAlpha(c, bmp, cx - radius, cy - radius, cx + radius, cy + radius, 140)
        } else {
            p.circleStroke(c, cx, cy, radius, 0x44000000, 6f)
        }
        val sweep = 360f * ratio.coerceIn(0f, 1f)
        val color = if (ratio > 0.35f) GOLD else RED
        p.arc(c, cx, cy, radius, -90f, sweep, color, 6f)
        p.text(c, seconds.toString(), cx, cy + radius * 0.35f, radius * 0.95f, TEXT_MAIN, Paint.Align.CENTER, bold = true)
    }

    /** 头像（圆形），贴图缺失时用首字母占位 */
    fun avatar(c: Canvas, p: Painter, tex: Textures, cx: Float, cy: Float, radius: Float, name: String, highlight: Boolean) {
        val bmp = tex.get("avatar_default")
        if (bmp != null) {
            p.bitmap(c, bmp, cx - radius, cy - radius, cx + radius, cy + radius)
        } else {
            p.circle(c, cx, cy, radius, 0xFF274B3C.toInt())
            p.text(
                c, name.take(1), cx, cy + radius * 0.4f, radius * 0.95f,
                TEXT_MAIN, Paint.Align.CENTER, bold = true
            )
        }
        if (highlight) {
            p.circleStroke(c, cx, cy, radius + 3f, GOLD, 4f)
        } else {
            p.circleStroke(c, cx, cy, radius + 2f, 0x66FFFFFF, 2f)
        }
    }

    /** 地主标记 */
    fun landlordBadge(c: Canvas, p: Painter, tex: Textures, cx: Float, cy: Float) {
        val bmp = tex.get("badge_landlord")
        if (bmp != null) {
            p.bitmap(c, bmp, cx - 24f, cy - 14f, cx + 24f, cy + 14f)
        } else {
            p.roundRect(c, cx - 26f, cy - 13f, cx + 26f, cy + 13f, 6f, 0xFFB3261E.toInt())
            p.text(c, "地主", cx, cy + 6f, 17f, GOLD, Paint.Align.CENTER, bold = true)
        }
    }

    /** 剩几张牌的小角标 */
    fun cardCountBadge(c: Canvas, p: Painter, tex: Textures, cx: Float, cy: Float, count: Int) {
        val bmp = tex.get("card_count_bg")
        if (bmp != null) {
            p.bitmap(c, bmp, cx - 30f, cy - 16f, cx + 30f, cy + 16f)
        } else {
            p.roundRect(c, cx - 30f, cy - 16f, cx + 30f, cy + 16f, 16f, 0xB3000000.toInt())
        }
        p.text(c, "剩 $count 张", cx, cy + 6f, 18f, TEXT_MAIN, Paint.Align.CENTER, bold = true)
    }

    /** 出牌提示文字（如「不出」/牌型名） */
    fun playTag(c: Canvas, p: Painter, cx: Float, cy: Float, text: String, color: Int = 0xCC000000.toInt()) {
        val w = p.textWidth(text, 20f, true) + 28f
        p.roundRect(c, cx - w / 2f, cy - 15f, cx + w / 2f, cy + 15f, 15f, color)
        p.text(c, text, cx, cy + 7f, 20f, TEXT_MAIN, Paint.Align.CENTER, bold = true)
    }

    /** 牌型中文名 */
    fun comboName(type: String): String = when (type) {
        "SINGLE" -> "单张"
        "PAIR" -> "对子"
        "TRIPLE" -> "三张"
        "TRIPLE_ONE" -> "三带一"
        "TRIPLE_TWO" -> "三带二"
        "STRAIGHT" -> "顺子"
        "STRAIGHT_PAIR" -> "连对"
        "PLANE" -> "飞机"
        "PLANE_ONE" -> "飞机带单"
        "PLANE_TWO" -> "飞机带对"
        "FOUR_TWO_SINGLE" -> "四带二"
        "FOUR_TWO_PAIR" -> "四带两对"
        "BOMB" -> "炸弹"
        "ROCKET" -> "王炸"
        else -> ""
    }
}
