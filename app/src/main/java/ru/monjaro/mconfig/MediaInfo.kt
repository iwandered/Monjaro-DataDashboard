package ru.monjaro.mconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView

class MediaInfo(
    private val context: Context,
    private val containerView: View, // 新增：容器视图，用于控制显隐
    private val artistTextView: TextView,
    private val songTitleTextView: TextView
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isRegistered = false

    // 广播接收器，模仿 Mhud 的 MediaInfoReceiver 实现
    private val mediaBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val action = intent.action
            Log.d("MediaInfo", "Received broadcast: $action")

            when (action) {
                "plus.monjaro.MEDIA_INFO_UPDATE",
                "debug.monjaro.MEDIA_INFO_UPDATE" -> {
                    // 提取信息
                    val title = intent.getStringExtra("title") ?: ""
                    val artist = intent.getStringExtra("artist") ?: ""

                    // Mhud中还会判断 is_playing，如果需要严格同步Mhud逻辑：
                    // val isPlaying = intent.getBooleanExtra("is_playing", false)

                    // 更新UI
                    updateUI(artist, title)
                }
                "plus.monjaro.MEDIA_INFO_CLEAR",
                "debug.monjaro.MEDIA_INFO_CLEAR" -> {
                    // 清除信息
                    clearMediaInfo()
                }
            }
        }
    }

    fun start() {
        if (!isRegistered) {
            try {
                Log.d("MediaInfo", "=== 启动媒体广播监听 ===")
                val filter = IntentFilter().apply {
                    addAction("plus.monjaro.MEDIA_INFO_UPDATE")
                    addAction("plus.monjaro.MEDIA_INFO_CLEAR")
                    addAction("debug.monjaro.MEDIA_INFO_UPDATE")
                    addAction("debug.monjaro.MEDIA_INFO_CLEAR")
                }
                // 根据Android版本适配注册方式
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(mediaBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(mediaBroadcastReceiver, filter)
                }
                isRegistered = true

                // 启动时先清空/隐藏，等待广播更新
                clearMediaInfo()

            } catch (e: Exception) {
                Log.e("MediaInfo", "注册广播接收器失败", e)
            }
        }
    }

    fun stop() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(mediaBroadcastReceiver)
                isRegistered = false
                Log.d("MediaInfo", "媒体广播监听已停止")
            } catch (e: Exception) {
                Log.e("MediaInfo", "注销广播接收器失败", e)
            }
        }
        clearMediaInfo()
    }

    private fun updateUI(artist: String, songTitle: String) {
        handler.post {
            // 如果歌名和歌手都为空，或者是"Unknown"，则隐藏
            if ((artist.isEmpty() && songTitle.isEmpty()) ||
                (artist == "Unknown" && songTitle == "Unknown")) {
                containerView.visibility = View.INVISIBLE
            } else {
                containerView.visibility = View.VISIBLE

                // 处理默认值
                val displayArtist = if (artist.isEmpty() || artist == "Unknown") "未知歌手" else artist
                val displayTitle = if (songTitle.isEmpty() || songTitle == "Unknown") "未知歌曲" else songTitle

                artistTextView.text = displayArtist
                songTitleTextView.text = displayTitle

                // 让文字滚动（跑马灯效果需要选中状态）
                artistTextView.isSelected = true
                songTitleTextView.isSelected = true
            }
        }
    }

    private fun clearMediaInfo() {
        handler.post {
            containerView.visibility = View.INVISIBLE
            artistTextView.text = ""
            songTitleTextView.text = ""
        }
    }

    // 兼容旧接口，虽然广播是被动接收，不需要主动轮询
    fun triggerImmediateRefresh() {
        // 广播模式下无法主动拉取数据，只能等待下一次广播
        // 但可以确保UI状态是正确的
        Log.d("MediaInfo", "广播模式：等待下一次媒体更新")
    }
}