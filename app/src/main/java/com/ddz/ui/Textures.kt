package com.ddz.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ddz.CrashLog
import com.ddz.core.Card
import java.util.concurrent.Executors

/**
 * 贴图仓库。
 *
 * **关键设计：所有贴图都是「可选」的。**
 * 如果 assets/textures 下缺某张图，[get] 返回 null，渲染层会自动退化成程序化画的占位
 * （圆角矩形 + 文字），所以这台机器上还没有任何美术资源时，游戏依然能完整跑起来、
 * 能联机、能打完一局。等你把贴图放进 [AssetsSpec.dirHint] 对应的目录，重启即生效，一行代码都不用改。
 *
 * 解码时会按目标尺寸做 inSampleSize 降采样，避免 55 张牌的原图直接把内存吃满。
 */
class Textures(private val context: Context) {

    private val cache = HashMap<String, Bitmap>()
    private var assetNames: Set<String>? = null
    private val decoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ddz-tex").also { it.isDaemon = true }
    }

    init {
        CrashLog.mark(context, "Textures: 构造完成")
    }

    /** 返回 null 表示这张图不存在（或解码失败），调用方应走占位绘制 */
    fun get(name: String, targetWidth: Int = 0): Bitmap? {
        val key = if (targetWidth > 0) "$name@$targetWidth" else name
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        // 依次尝试别名：美术包常按横屏/竖屏分别出图，或给 Logo 起了别的名字
        for (candidate in candidatesOf(name)) {
            val bmp = decode(candidate, targetWidth) ?: continue
            synchronized(cache) { cache[key] = bmp }
            return bmp
        }
        return null
    }

    fun has(name: String): Boolean =
        candidatesOf(name).any { assetSet().contains("$it.png") }

    /** 逻辑名字 → 若干可能的实际文件名（首项即原名） */
    private fun candidatesOf(name: String): List<String> {
        val extra = ALIASES[name] ?: return listOf(name)
        return listOf(name) + extra
    }

    /**
     * assets/textures 下的文件名集合，只读一次目录、**不做任何解码**。
     *
     * 早期实现里 has() 直接调 get(name, 0)，那会按原始尺寸解码整张图 ——
     * 检查 55 张牌就要吃掉约 27MB 堆内存，在低端机上足以触发 OOM。
     */
    private fun assetSet(): Set<String> {
        assetNames?.let { return it }
        val names = try {
            context.assets.list("textures")?.toHashSet() ?: HashSet()
        } catch (e: Exception) {
            HashSet()
        }
        assetNames = names
        return names
    }

    fun cardTexture(card: Card, targetWidth: Int = CARD_TEX_W): Bitmap? =
        get(card.textureName, targetWidth)

    private fun decode(name: String, targetWidth: Int): Bitmap? {
        val path = "textures/$name.png"
        // 第一遍只读尺寸，第二遍按需降采样解码
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = if (targetWidth > 0 && bounds.outWidth > targetWidth * 2) {
                var s = 1
                while (bounds.outWidth / (s * 2) >= targetWidth) s *= 2
                s
            } else 1
        }
        return try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    /** 后台预热常用贴图，避免第一次进牌桌时掉帧 */
    fun preloadAsync(names: List<String>, targetWidth: Int = 0) {
        decoder.execute {
            for (n in names) {
                try {
                    get(n, targetWidth)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 报告哪些「期望中的」贴图还没提供，用于设置页里的资源清单 */
    fun missingReport(): List<String> {
        val miss = ArrayList<String>()
        for (n in AssetsSpec.essential) if (!has(n)) miss.add(n)
        return miss
    }

    /**
     * 清空贴图缓存。
     *
     * **刻意不调用 `Bitmap.recycle()`。** 渲染线程可能正卡在 `holder.lockCanvas()` 里绘制上一帧，
     * 此时把它正在用的 Bitmap 回收掉，会在原生层直接崩
     * （`Canvas: trying to use a recycled bitmap`），而且 try/catch 抓不住。
     * Android 8+ 的 Bitmap 像素数据就在 Java 堆上，丢掉引用后 GC 自然会释放，不需要手动 recycle。
     */
    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
        assetNames = null
    }

    companion object {
        /**
         * 名字别名表：逻辑名字 → 额外尝试的实际文件名。
         *
         * 存在的意义是**容忍美术包的合理命名差异，而不是要求美术返工**：
         *  - 背景常按横竖屏分别出图（`table_bg_land` / `table_bg_port`），本项目锁定横屏，优先取 land；
         *  - Logo 常被命名为 `splash_logo`。
         * 这样无论美术给的是 `table_bg.png` 还是 `table_bg_land.png` 都能直接用上。
         */
        private val ALIASES: Map<String, List<String>> = mapOf(
            "table_bg" to listOf("table_bg_land", "table_bg_port"),
            "lobby_bg" to listOf("lobby_bg_land", "lobby_bg_port"),
            "logo" to listOf("splash_logo")
        )

        /** 手牌贴图解码宽度：够 2 倍屏清晰，又不至于吃内存 */
        const val CARD_TEX_W = 128
    }
}

/**
 * 贴图清单（与 README 里的「贴图需求」一一对应）。
 * 命名必须完全一致，代码按名字直接取。
 */
object AssetsSpec {

    /** assets 下的目录 */
    const val dirHint = "app/src/main/assets/textures/"

    val suits = listOf(
        "s" to "黑桃",
        "h" to "红桃",
        "c" to "梅花",
        "d" to "方块"
    )

    /**
     * 牌面点数标签，**必须小写**（`j`/`q`/`k`/`a`）。
     * Android 资源名不允许大写字母，小写才能让同一份文件既放 assets/ 也放 res/。
     * 与 `core.Card.textureName` 的取值保持一致。
     */
    val rankLabels = listOf(
        "3", "4", "5", "6", "7", "8", "9", "10", "j", "q", "k", "a", "2"
    )

    /** 54 张牌面 + 牌背 */
    val cards: List<String> = buildList {
        for ((s, _) in suits) for (r in rankLabels) add("card_${s}_$r")
        add("card_joker_small")
        add("card_joker_big")
    }

    /** 必需贴图：缺了会退化成占位图，但强烈建议全部提供 */
    val essential: List<String> = buildList {
        addAll(cards)
        add("card_back")
        add("table_bg")
        add("btn_primary_normal")
        add("btn_primary_pressed")
        add("btn_secondary_normal")
        add("btn_secondary_pressed")
        add("panel_bg")
        add("avatar_default")
        add("seat_frame_active")
        add("badge_landlord")
        add("card_selected_overlay")
        add("icon_clock_ring")
        add("logo")
    }

    /** 可选贴图：没有也能玩，只是不够好看 */
    val optional: List<String> = buildList {
        add("btn_primary_disabled")
        add("btn_small_normal")
        add("btn_small_pressed")
        add("input_bg")
        add("seat_frame_normal")
        add("avatar_frame_landlord")
        add("badge_farmer")
        add("card_count_bg")
        add("slot_hint_highlight")
        add("last_play_marker")
        add("icon_coin")
        add("fx_spring_banner")
        add("fx_anti_spring_banner")
        add("fx_multiplier_x2")
        add("fx_multiplier_x4")
        add("fx_win")
        add("fx_lose")
        for (i in 1..12) add("fx_bomb_%02d".format(i))
        for (i in 1..10) add("fx_rocket_%02d".format(i))
    }

    /** 生成牌面贴图名的唯一来源在 core.Card.textureName，这里不再重复实现，避免两处逻辑漂移 */
}
