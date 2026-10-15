package com.ddz.ui

/**
 * 虚拟坐标系：所有绘制都按 1280×720 的横屏画布设计，
 * 实际屏幕按等比缩放 + 居中letterbox 映射过去。
 *
 * 这样做的好处是布局只需要算一次，不同分辨率/刘海屏都不会错位。
 */
object V {
    const val W = 1280f
    const val H = 720f

    // 牌尺寸（5:7）
    const val CARD_W = 76f
    const val CARD_H = 106f
    const val CARD_RADIUS = 6f

    /** 手牌区。中心左移，给右侧竖排的操作按钮让位 */
    const val HAND_Y = 596f
    const val HAND_MAX_W = 900f
    const val HAND_CENTER_X = 560f

    /** 右侧竖排操作按钮的横坐标 */
    const val BTN_COLUMN_X = 1180f

    /** 对手座位 */
    const val LEFT_SEAT_X = 132f
    const val RIGHT_SEAT_X = W - 132f
    const val SEAT_Y = 156f
    const val AVATAR_R = 46f

    /** 对手打出的牌展示区 */
    const val LEFT_PLAY_X = 330f
    const val RIGHT_PLAY_X = 950f
    const val MY_PLAY_X = 560f
    const val PLAY_Y = 250f

    /** 我方打出的牌展示区 */
    const val MY_PLAY_Y = 500f

    /** 底牌（三张）显示在顶部中央 */
    const val BOTTOM_CARD_W = 44f
    const val BOTTOM_CARD_H = 62f
    const val BOTTOM_Y = 14f

    /** 按钮条 */
    const val BTN_H = 54f
    const val BTN_MIN_W = 120f
}

/** 界面枚举 */
enum class Screen { LOBBY, ROOM, TABLE }
