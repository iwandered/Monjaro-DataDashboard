package ru.monjaro.mconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.max

/**
 * 红绿灯倒计时管理器
 * 专门用于处理高德地图车机版的红绿灯广播数据
 * 基于之前分析的高德实现方案
 */
class TrafficLightManager(
    private val context: Context,
    private val updateCallback: (TrafficLightInfo?) -> Unit
) {
    companion object {
        // 高德地图广播Action
        private const val AMAP_BROADCAST_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"

        // 红绿灯信息类型
        private const val KEY_TYPE_TRAFFIC_LIGHT = 60073

        // 字段名称（根据高德文档）
        private const val KEY_TYPE = "KEY_TYPE"
        private const val KEY_TRAFFIC_LIGHT_STATUS = "trafficLightStatus"
        private const val KEY_RED_LIGHT_COUNTDOWN = "redLightCountDownSeconds"
        private const val KEY_GREEN_LIGHT_COUNTDOWN = "greenLightLastSecond"
        private const val KEY_DIRECTION = "dir"
        private const val KEY_WAIT_ROUND = "waitRound"

        // 状态映射（高德状态 -> 我们自定义状态）
        const val STATUS_NONE = 0
        const val STATUS_GREEN = 1
        const val STATUS_RED = 2
        const val STATUS_YELLOW = 3

        // 方向映射（保持与Java版本一致，直接使用高德原始值）
        const val DIRECTION_STRAIGHT = 4   // 直行（对应高德4，直接使用4）
        const val DIRECTION_LEFT = 1       // 左转（对应高德1）
        const val DIRECTION_RIGHT = 2      // 右转（对应高德2）

        // 高德原始状态值
        private const val AMAP_STATUS_RED = 1        // 红灯
        private const val AMAP_STATUS_GREEN = 2      // 绿灯
        private const val AMAP_STATUS_YELLOW = 3     // 黄灯
        private const val AMAP_STATUS_GREEN_COUNTDOWN = 4  // 绿灯倒计时
        private const val AMAP_STATUS_TRANSITION = -1      // 过渡状态（显示黄灯）

        // 高德原始方向值（保持不变）
        private const val AMAP_DIRECTION_LEFT = 1    // 左转
        private const val AMAP_DIRECTION_RIGHT = 2   // 右转
        private const val AMAP_DIRECTION_STRAIGHT = 4 // 直行
    }

    /**
     * 红绿灯信息数据类（完全按照之前分析的结构）
     */
    data class TrafficLightInfo(
        var status: Int = STATUS_NONE,           // 红绿灯状态（映射后的状态）
        var countdown: Int = 0,                  // 倒计时秒数（主要使用redLightCountDownSeconds）
        var direction: Int = DIRECTION_STRAIGHT, // 方向（直接使用高德原始值：1=左转，2=右转，4=直行）
        var waitRound: Int = 0,                  // 等待轮次
        var source: String = "amap",             // 数据来源
        var timestamp: Long = System.currentTimeMillis(), // 接收时间戳

        // 原始高德数据（用于调试）
        var amapStatus: Int = 0,                 // 高德原始状态
        var amapDirection: Int = 0,              // 高德原始方向
        var greenLast: Int = 0                   // 绿灯最后秒数
    ) {
        // 判断是否有效数据
        fun isValid(): Boolean {
            return status != STATUS_NONE && countdown >= 0
        }

        // 判断是否过期（默认10秒过期）
        fun isExpired(expireTime: Long = 10000): Boolean {
            return System.currentTimeMillis() - timestamp > expireTime
        }

        // 判断是否应该显示
        fun shouldDisplay(): Boolean {
            // 黄灯且倒计时为0时不显示
            if (status == STATUS_YELLOW && countdown == 0) {
                return false
            }
            return isValid() && !isExpired()
        }
    }

    private var broadcastReceiver: BroadcastReceiver? = null
    private var isRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    // 历史方向缓存（应对dir=0的情况）
    private var lastValidDirection = DIRECTION_STRAIGHT  // 默认为直行

    // 最后接收到的有效数据时间戳
    private var lastValidDataTime: Long = 0
    private val DATA_EXPIRE_TIME = 10000L // 数据过期时间10秒
    private val HEARTBEAT_INTERVAL = 1000L // 心跳检查间隔1秒

    // 自动隐藏计时器
    private val autoHideRunnable = Runnable {
        updateCallback(null)
    }

    // 心跳检查，清理过期数据
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                val currentTime = System.currentTimeMillis()

                // 如果数据过期，清除显示
                if (currentTime - lastValidDataTime > DATA_EXPIRE_TIME) {
                    handler.post {
                        updateCallback(null)
                        // 重置历史方向
                        lastValidDirection = DIRECTION_STRAIGHT
                    }
                }

                // 继续下一次心跳检查
                handler.postDelayed(this, HEARTBEAT_INTERVAL)
            } catch (e: Exception) {
                Log.e("TrafficLightManager", "心跳检查异常", e)
            }
        }
    }

    /**
     * 注册广播接收器并开始监听（只监听高德导航模式广播）
     */
    fun start() {
        try {
            if (isRegistered) {
                Log.w("TrafficLightManager", "广播接收器已注册，跳过重复注册")
                return
            }

            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return

                    val action = intent.action ?: ""

                    // 只处理高德地图广播
                    if (action != AMAP_BROADCAST_ACTION) {
                        return
                    }

                    parseAmapBroadcast(intent)
                }
            }

            val filter = IntentFilter(AMAP_BROADCAST_ACTION)

            // 注册广播接收器
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.registerReceiver(
                    broadcastReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(broadcastReceiver, filter)
            }

            isRegistered = true

            // 启动心跳检查
            handler.post(heartbeatRunnable)

            Log.d("TrafficLightManager", "高德红绿灯广播接收器已注册")

        } catch (e: Exception) {
            Log.e("TrafficLightManager", "注册广播接收器失败", e)
        }
    }

    /**
     * 解析高德地图广播（严格按照之前分析的格式）
     */
    private fun parseAmapBroadcast(intent: Intent) {
        try {
            val extras = intent.extras ?: return

            // 检查KEY_TYPE
            val keyType = getIntFromBundle(extras, KEY_TYPE, -1)
            if (keyType != KEY_TYPE_TRAFFIC_LIGHT) {
                // 不是红绿灯数据，忽略
                return
            }

            // 解析原始数据
            val amapStatus = getIntFromBundle(extras, KEY_TRAFFIC_LIGHT_STATUS, 0)
            val redCountdown = getIntFromBundle(extras, KEY_RED_LIGHT_COUNTDOWN, 0)
            val greenLast = getIntFromBundle(extras, KEY_GREEN_LIGHT_COUNTDOWN, 0)
            val amapDirection = getIntFromBundle(extras, KEY_DIRECTION, 0)
            val waitRound = getIntFromBundle(extras, KEY_WAIT_ROUND, 0)

            // 记录日志
            if (BuildConfig.DEBUG) {
                Log.d("TrafficLightManager", "收到高德红绿灯: " +
                        "status=$amapStatus, " +
                        "red=$redCountdown, " +
                        "greenLast=$greenLast, " +
                        "dir=$amapDirection, " +
                        "wait=$waitRound")
            }

            // 处理方向逻辑
            val direction = handleDirection(amapDirection)

            // 映射状态
            val status = mapAmapStatus(amapStatus)

            // 确定倒计时值
            val countdown = determineCountdown(amapStatus, redCountdown, greenLast)

            // 检查是否需要显示
            val shouldDisplay = when {
                amapStatus == AMAP_STATUS_YELLOW && redCountdown == 0 -> {
                    Log.d("TrafficLightManager", "黄灯常亮0秒，不显示")
                    false
                }
                amapStatus == AMAP_STATUS_TRANSITION && redCountdown == 0 -> {
                    Log.d("TrafficLightManager", "过渡状态0秒，不显示")
                    false
                }
                else -> true
            }

            if (!shouldDisplay) {
                // 发送null以隐藏显示
                handler.post {
                    updateCallback(null)
                }
                return
            }

            // 创建红绿灯信息
            val trafficLightInfo = TrafficLightInfo(
                status = status,
                countdown = countdown,
                direction = direction,
                waitRound = waitRound,
                source = "amap_navigation",
                timestamp = System.currentTimeMillis(),
                amapStatus = amapStatus,
                amapDirection = amapDirection,
                greenLast = greenLast
            )

            // 处理红绿灯信息
            processTrafficLightInfo(trafficLightInfo)

        } catch (e: Exception) {
            Log.e("TrafficLightManager", "解析高德广播失败", e)
        }
    }

    /**
     * 处理方向逻辑（按照Java版本保持一致）
     * 高德方向：1=左转, 2=右转, 4=直行
     * 特殊处理：dir=0时使用历史方向
     */
    private fun handleDirection(amapDirection: Int): Int {
        var direction = DIRECTION_STRAIGHT  // 默认直行

        // 如果收到有效方向，更新历史方向
        when (amapDirection) {
            AMAP_DIRECTION_LEFT -> {
                direction = DIRECTION_LEFT
                lastValidDirection = direction  // 只在有效时更新历史
            }
            AMAP_DIRECTION_RIGHT -> {
                direction = DIRECTION_RIGHT
                lastValidDirection = direction  // 只在有效时更新历史
            }
            AMAP_DIRECTION_STRAIGHT -> {
                direction = DIRECTION_STRAIGHT
                lastValidDirection = direction  // 只在有效时更新历史
            }
            0 -> {
                // dir=0时，使用历史方向（如果有）
                if (lastValidDirection != 0) {
                    direction = lastValidDirection
                }
                // 注意：dir=0时不更新历史方向
            }
        }

        return direction
    }

    /**
     * 映射高德状态到我们自定义状态（按照之前分析的状态机）
     */
    private fun mapAmapStatus(amapStatus: Int): Int {
        return when (amapStatus) {
            AMAP_STATUS_RED -> STATUS_RED            // 红灯
            AMAP_STATUS_GREEN -> STATUS_GREEN        // 绿灯
            AMAP_STATUS_YELLOW -> STATUS_YELLOW      // 黄灯
            AMAP_STATUS_GREEN_COUNTDOWN -> STATUS_GREEN // 绿灯倒计时也显示为绿灯
            AMAP_STATUS_TRANSITION -> STATUS_YELLOW  // 过渡状态显示黄灯
            else -> STATUS_NONE
        }
    }

    /**
     * 确定倒计时值（按照之前分析的逻辑）
     */
    private fun determineCountdown(amapStatus: Int, redCountdown: Int, greenLast: Int): Int {
        return when (amapStatus) {
            AMAP_STATUS_RED -> redCountdown          // 红灯阶段：使用红灯倒计时
            AMAP_STATUS_GREEN -> {
                if (greenLast > 0) greenLast else 0  // 绿灯：如果有greenLast则显示，否则0
            }
            AMAP_STATUS_YELLOW -> redCountdown       // 黄灯常亮：使用红灯倒计时字段
            AMAP_STATUS_GREEN_COUNTDOWN -> redCountdown // 绿灯倒计时：使用红灯倒计时字段
            AMAP_STATUS_TRANSITION -> redCountdown   // 过渡状态：短暂倒计时
            else -> 0
        }
    }

    /**
     * 从Bundle安全获取整数值（支持String转Int）
     */
    private fun getIntFromBundle(bundle: Bundle, key: String, defaultValue: Int): Int {
        try {
            if (!bundle.containsKey(key)) {
                return defaultValue
            }

            val value = bundle.get(key)
            return when (value) {
                is Int -> value
                is String -> value.toIntOrNull() ?: defaultValue
                is Double -> value.toInt()
                is Float -> value.toInt()
                is Long -> value.toInt()
                else -> defaultValue
            }
        } catch (e: Exception) {
            Log.w("TrafficLightManager", "获取字段 $key 失败: ${e.message}")
            return defaultValue
        }
    }

    /**
     * 处理红绿灯信息
     */
    private fun processTrafficLightInfo(info: TrafficLightInfo) {
        // 记录接收时间
        lastValidDataTime = System.currentTimeMillis()

        // 检查是否应该显示
        if (!info.shouldDisplay()) {
            Log.d("TrafficLightManager", "收到不需要显示的数据，忽略")
            handler.post {
                updateCallback(null)
            }
            return
        }

        // 确保倒计时非负
        info.countdown = max(0, info.countdown)

        if (BuildConfig.DEBUG) {
            Log.d("TrafficLightManager", "处理红绿灯: " +
                    "状态=${getStatusString(info.status)}, " +
                    "倒计时=${info.countdown}s, " +
                    "方向=${getDirectionString(info.direction)}, " +
                    "原始状态=${info.amapStatus}")
        }

        // 在主线程更新UI
        handler.post {
            try {
                // 取消之前的自动隐藏
                handler.removeCallbacks(autoHideRunnable)

                // 发送更新
                updateCallback(info)

                // 设置自动隐藏（倒计时结束后5秒，或15秒无新数据）
                val hideDelay = if (info.countdown > 0) {
                    (info.countdown + 5) * 1000L
                } else {
                    15000L // 没有倒计时时显示15秒
                }

                handler.postDelayed(autoHideRunnable, hideDelay)
            } catch (e: Exception) {
                Log.e("TrafficLightManager", "处理红绿灯信息异常", e)
            }
        }
    }

    /**
     * 停止监听
     */
    fun stop() {
        try {
            if (isRegistered && broadcastReceiver != null) {
                context.unregisterReceiver(broadcastReceiver)
                isRegistered = false
                broadcastReceiver = null
            }

            handler.removeCallbacks(autoHideRunnable)
            handler.removeCallbacks(heartbeatRunnable)

            // 重置历史方向
            lastValidDirection = DIRECTION_STRAIGHT

            Log.d("TrafficLightManager", "广播接收器已注销")

        } catch (e: Exception) {
            Log.e("TrafficLightManager", "注销广播接收器失败", e)
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 手动测试：模拟高德地图红绿灯数据（用于调试）
     */
    fun simulateAmapTrafficLightData(
        amapStatus: Int,  // 高德原始状态
        redCountdown: Int,
        amapDirection: Int, // 高德原始方向
        waitRound: Int = 0,
        greenLast: Int = 0
    ) {
        // 检查是否需要显示
        val shouldDisplay = when {
            amapStatus == AMAP_STATUS_YELLOW && redCountdown == 0 -> {
                Log.d("TrafficLightManager", "模拟：黄灯常亮0秒，不显示")
                false
            }
            amapStatus == AMAP_STATUS_TRANSITION && redCountdown == 0 -> {
                Log.d("TrafficLightManager", "模拟：过渡状态0秒，不显示")
                false
            }
            else -> true
        }

        if (!shouldDisplay) {
            handler.post { updateCallback(null) }
            return
        }

        val testInfo = TrafficLightInfo().apply {
            this.amapStatus = amapStatus
            this.amapDirection = amapDirection

            // 处理方向
            this.direction = handleDirection(amapDirection)

            // 映射状态
            this.status = mapAmapStatus(amapStatus)

            // 确定倒计时
            this.countdown = determineCountdown(amapStatus, redCountdown, greenLast)

            this.waitRound = waitRound
            this.greenLast = greenLast
            this.source = "test"
            timestamp = System.currentTimeMillis()
        }

        Log.d("TrafficLightManager", "模拟高德红绿灯: " +
                "原始状态=$amapStatus, " +
                "红灯=$redCountdown, " +
                "方向=$amapDirection, " +
                "映射状态=${getStatusString(testInfo.status)}")

        processTrafficLightInfo(testInfo)
    }

    /**
     * 获取状态字符串
     */
    private fun getStatusString(status: Int): String {
        return when (status) {
            STATUS_GREEN -> "绿灯"
            STATUS_RED -> "红灯"
            STATUS_YELLOW -> "黄灯"
            else -> "未知"
        }
    }

    /**
     * 获取方向字符串
     */
    private fun getDirectionString(direction: Int): String {
        return when (direction) {
            DIRECTION_STRAIGHT -> "直行"
            DIRECTION_LEFT -> "左转"
            DIRECTION_RIGHT -> "右转"
            else -> "未知($direction)"
        }
    }

    /**
     * 检查是否已注册
     */
    fun isRunning(): Boolean {
        return isRegistered
    }
}