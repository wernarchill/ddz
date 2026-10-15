package com.ddz.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * 音效。**全部资源可选**：assets/sounds 下没有对应 ogg 就静音跳过，绝不因为缺音效而崩溃。
 * 用 SoundPool 而不是 MediaPlayer，因为出牌/点击这类短音效需要低延迟、可重叠播放。
 */
class Sfx(context: Context) {

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()

    var enabled = true
    var volume = 1.0f

    init {
        try {
            val p = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            pool = p
            for (name in KNOWN) {
                try {
                    context.assets.openFd("sounds/$name.ogg").use { fd ->
                        val id = p.load(fd, 1)
                        if (id != 0) ids[name] = id
                    }
                } catch (e: Exception) {
                    // 缺这个音效，忽略
                }
            }
        } catch (e: Exception) {
            Log.w("DDZ", "SoundPool 初始化失败，游戏将静音运行", e)
            pool = null
        }
    }

    fun play(name: String, rate: Float = 1.0f) {
        if (!enabled) return
        val p = pool ?: return
        val id = ids[name] ?: return
        try {
            p.play(id, volume, volume, 1, 0, rate)
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            pool?.release()
        } catch (_: Exception) {
        }
        pool = null
        ids.clear()
    }

    companion object {
        val KNOWN = listOf(
            "deal", "select", "play", "pass", "bomb", "rocket",
            "click", "win", "lose", "your_turn", "landlord"
        )
    }
}
