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
import org.json.JSONObject
import kotlin.math.max

/**
 * 红绿灯倒计时管理器
 * 负责监听高德地图车机版的红绿灯广播，包括导航模式和巡航模式
 */
class TrafficLightManager(
    private val context: Context,
    private val updateCallback: (TrafficLightInfo?) -> Unit
) {
    companion object {
        // 高德地图广播Action
        private const val AMAP_BROADCAST_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        private const val AMAP_BROADCAST_CRUISE_ACTION = "AUTONAVI_STANDARD_BROADCAST_RECV"
        private const val AMAP_BROADCAST_XM = "com.autonavi.xm.action.BROADCAST"
        private const val AMAP_BROADCAST_MINIMAP = "com.autonavi.minimap.action.BROADCAST"

        // 红绿灯信息类型
        private const val KEY_TYPE_TRAFFIC_LIGHT = 60073
        private const val KEY_TYPE_CRUISE_TRAFFIC_LIGHT = 10001 // 巡航模式可能的值

        // 字段名称
        private const val KEY_TRAFFIC_LIGHT_STATUS = "trafficLightStatus"
        private const val KEY_RED_LIGHT_COUNTDOWN = "redLightCountDownSeconds"
        private const val KEY_GREEN_LIGHT_COUNTDOWN = "greenLightCountDownSeconds"
        private const val KEY_DIRECTION = "dir"
        private const val KEY_WAIT_ROUND = "waitRound"
        private const val KEY_JSON_DATA = "json"
        private const val KEY_RAW_DATA = "data"

        // 巡航模式特定字段
        private const val KEY_CRUISE_TRAFFIC_LIGHT = "traffic_light"
        private const val KEY_CRUISE_COUNTDOWN = "countdown"
        private const val KEY_CRUISE_STATUS = "status"
        private const val KEY_CRUISE_COLOR = "color"
        private const val KEY_CRUISE_TIME = "time"

        // 状态映射
        const val STATUS_NONE = 0
        const val STATUS_GREEN = 1
        const val STATUS_RED = 2
        const val STATUS_YELLOW = 3
        const val STATUS_FLASHING_YELLOW = 4 // 黄灯闪烁

        // 方向映射
        const val DIRECTION_STRAIGHT = 0   // 直行
        const val DIRECTION_LEFT = 1       // 左转
        const val DIRECTION_RIGHT = 2      // 右转
        const val DIRECTION_STRAIGHT_LEFT = 3  // 直行+左转
        const val DIRECTION_STRAIGHT_RIGHT = 4 // 直行+右转
        const val DIRECTION_ALL = 5        // 所有方向

        // 颜色映射
        private const val COLOR_GREEN = 1
        private const val COLOR_RED = 2
        private const val COLOR_YELLOW = 3
    }

    /**
     * 红绿灯信息数据类
     */
    data class TrafficLightInfo(
        var status: Int = STATUS_NONE,           // 红绿灯状态
        var countdown: Int = 0,                  // 倒计时秒数
        var direction: Int = DIRECTION_STRAIGHT, // 方向
        var waitRound: Int = 0,                  // 等待轮次
        var source: String = "unknown",          // 数据来源: navigation/cruise/test
        var timestamp: Long = System.currentTimeMillis() // 接收时间戳
    ) {
        // 判断是否有效数据
        fun isValid(): Boolean {
            return status != STATUS_NONE && countdown >= 0
        }

        // 判断是否过期（默认10秒过期）
        fun isExpired(expireTime: Long = 10000): Boolean {
            return System.currentTimeMillis() - timestamp > expireTime
        }
    }

    private var broadcastReceiver: BroadcastReceiver? = null
    private var isRegistered = false
    private val handler = Handler(Looper.getMainLooper())

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
                    }
                }

                // 继续下一次心跳检查
                handler.postDelayed(this, HEARTBEAT_INTERVAL)
            } catch (e: Exception) {
                Log.e("TrafficLightManager", "心跳检查异常", e)
            }
        }
    }

    // 广播拦截日志（调试用）
    private val broadcastLog = mutableListOf<String>()
    private val MAX_LOG_SIZE = 50

    /**
     * 注册广播接收器并开始监听
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
                    val extras = intent.extras

                    // 记录广播日志（调试用）
                    addBroadcastLog("收到广播: $action")
                    if (extras != null) {
                        val keys = extras.keySet()
                        keys.forEach { key ->
                            val value = extras.get(key)
                            addBroadcastLog("  $key = $value")
                        }
                    }

                    // 根据Action处理不同类型的广播
                    when {
                        action == AMAP_BROADCAST_ACTION -> {
                            parseNavigationTrafficLightData(intent)
                        }
                        action == AMAP_BROADCAST_CRUISE_ACTION -> {
                            parseCruiseTrafficLightData(intent)
                        }
                        action.contains("autonavi", ignoreCase = true) -> {
                            parseGenericTrafficLightData(intent)
                        }
                        else -> {
                            // 其他广播，尝试解析红绿灯数据
                            parseGenericTrafficLightData(intent)
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                // 导航模式广播
                addAction(AMAP_BROADCAST_ACTION)

                // 巡航模式广播
                try {
                    addAction(AMAP_BROADCAST_CRUISE_ACTION)
                } catch (e: Exception) {
                    Log.w("TrafficLightManager", "无法注册巡航广播: ${e.message}")
                }

                // 其他可能的高德广播
                try {
                    addAction(AMAP_BROADCAST_XM)
                    addAction(AMAP_BROADCAST_MINIMAP)
                } catch (e: Exception) {
                    Log.w("TrafficLightManager", "无法注册其他高德广播: ${e.message}")
                }

                // 尝试捕获所有可能的广播
                if (BuildConfig.DEBUG) {
                    try {
                        addAction("android.intent.action.MEDIA_BUTTON")
                        addAction(Intent.ACTION_MEDIA_BUTTON)
                    } catch (e: Exception) {
                        // 忽略错误
                    }
                }
            }

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

            Log.d("TrafficLightManager", "广播接收器已注册，支持导航和巡航模式")

        } catch (e: Exception) {
            Log.e("TrafficLightManager", "注册广播接收器失败", e)
        }
    }

    /**
     * 解析导航模式红绿灯数据
     */
    private fun parseNavigationTrafficLightData(intent: Intent) {
        try {
            val extras = intent.extras ?: return

            // 检查KEY_TYPE
            val keyType = extras.getInt("KEY_TYPE", -1)
            if (keyType != KEY_TYPE_TRAFFIC_LIGHT && keyType != KEY_TYPE_CRUISE_TRAFFIC_LIGHT) {
                return
            }

            // 尝试从json字段获取数据
            var jsonString = extras.getString(KEY_JSON_DATA)
            if (jsonString.isNullOrEmpty()) {
                jsonString = extras.getString(KEY_RAW_DATA)
            }

            if (jsonString.isNullOrEmpty()) {
                // 尝试直接从bundle中读取字段
                parseNavigationBundleData(extras)
                return
            }

            // 解析JSON数据
            try {
                val jsonObject = JSONObject(jsonString)
                parseNavigationJsonData(jsonObject, "navigation")
            } catch (e: Exception) {
                Log.w("TrafficLightManager", "JSON解析失败，尝试直接解析Bundle", e)
                parseNavigationBundleData(extras)
            }

        } catch (e: Exception) {
            Log.e("TrafficLightManager", "解析导航红绿灯数据失败", e)
        }
    }

    /**
     * 解析导航模式JSON数据
     */
    private fun parseNavigationJsonData(json: JSONObject, source: String) {
        val trafficLightInfo = TrafficLightInfo().apply {
            // 解析状态
            val statusValue = json.optInt(KEY_TRAFFIC_LIGHT_STATUS, -1)
            status = when (statusValue) {
                COLOR_GREEN -> STATUS_GREEN
                COLOR_RED -> STATUS_RED
                COLOR_YELLOW -> STATUS_YELLOW
                else -> STATUS_NONE
            }

            // 解析倒计时（优先红绿灯倒计时，然后绿灯倒计时）
            countdown = json.optInt(KEY_RED_LIGHT_COUNTDOWN, 0)
            if (countdown == 0) {
                countdown = json.optInt(KEY_GREEN_LIGHT_COUNTDOWN, 0)
            }

            // 解析方向
            direction = json.optInt(KEY_DIRECTION, DIRECTION_STRAIGHT)

            // 解析等待轮次
            waitRound = json.optInt(KEY_WAIT_ROUND, 0)

            // 设置来源
            this.source = source
            timestamp = System.currentTimeMillis()
        }

        // 处理红绿灯信息
        if (trafficLightInfo.isValid()) {
            processTrafficLightInfo(trafficLightInfo)
        }
    }

    /**
     * 解析导航模式Bundle数据（无JSON格式）
     */
    private fun parseNavigationBundleData(extras: Bundle) {
        val trafficLightInfo = TrafficLightInfo().apply {
            // 尝试从不同字段获取状态
            status = when {
                extras.containsKey(KEY_TRAFFIC_LIGHT_STATUS) -> {
                    val statusValue = extras.getInt(KEY_TRAFFIC_LIGHT_STATUS, -1)
                    when (statusValue) {
                        COLOR_GREEN -> STATUS_GREEN
                        COLOR_RED -> STATUS_RED
                        COLOR_YELLOW -> STATUS_YELLOW
                        else -> STATUS_NONE
                    }
                }
                extras.containsKey("lightStatus") -> {
                    val statusValue = extras.getInt("lightStatus", -1)
                    when (statusValue) {
                        COLOR_GREEN -> STATUS_GREEN
                        COLOR_RED -> STATUS_RED
                        COLOR_YELLOW -> STATUS_YELLOW
                        else -> STATUS_NONE
                    }
                }
                else -> STATUS_NONE
            }

            // 尝试从不同字段获取倒计时
            countdown = when {
                extras.containsKey(KEY_RED_LIGHT_COUNTDOWN) ->
                    extras.getInt(KEY_RED_LIGHT_COUNTDOWN, 0)
                extras.containsKey(KEY_GREEN_LIGHT_COUNTDOWN) ->
                    extras.getInt(KEY_GREEN_LIGHT_COUNTDOWN, 0)
                extras.containsKey("lightCountdown") ->
                    extras.getInt("lightCountdown", 0)
                else -> 0
            }

            // 解析方向
            direction = extras.getInt(KEY_DIRECTION, DIRECTION_STRAIGHT)

            // 设置来源
            source = "navigation"
            timestamp = System.currentTimeMillis()
        }

        // 处理红绿灯信息
        if (trafficLightInfo.isValid()) {
            processTrafficLightInfo(trafficLightInfo)
        }
    }

    /**
     * 解析巡航模式红绿灯数据
     */
    private fun parseCruiseTrafficLightData(intent: Intent) {
        try {
            val extras = intent.extras ?: return

            // 方法1：直接字段解析
            parseCruiseDirectFields(extras)

            // 方法2：JSON格式解析
            val jsonString = extras.getString(KEY_JSON_DATA)
            if (!jsonString.isNullOrEmpty()) {
                try {
                    val json = JSONObject(jsonString)
                    parseCruiseJsonData(json)
                } catch (e: Exception) {
                    Log.w("TrafficLightManager", "巡航JSON解析失败", e)
                }
            }

            // 方法3：字符串格式解析
            val trafficLightStr = extras.getString(KEY_CRUISE_TRAFFIC_LIGHT)
            if (!trafficLightStr.isNullOrEmpty()) {
                parseTrafficLightString(trafficLightStr, "cruise")
            }

        } catch (e: Exception) {
            Log.e("TrafficLightManager", "解析巡航红绿灯数据失败", e)
        }
    }

    /**
     * 解析巡航模式直接字段数据
     */
    private fun parseCruiseDirectFields(extras: Bundle) {
        val trafficLightInfo = TrafficLightInfo().apply {
            // 解析状态
            status = when {
                extras.containsKey(KEY_CRUISE_STATUS) -> {
                    val statusValue = extras.getInt(KEY_CRUISE_STATUS, -1)
                    when (statusValue) {
                        COLOR_GREEN -> STATUS_GREEN
                        COLOR_RED -> STATUS_RED
                        COLOR_YELLOW -> STATUS_YELLOW
                        else -> STATUS_NONE
                    }
                }
                extras.containsKey(KEY_CRUISE_COLOR) -> {
                    val colorStr = extras.getString(KEY_CRUISE_COLOR) ?: ""
                    when {
                        colorStr.contains("green", ignoreCase = true) -> STATUS_GREEN
                        colorStr.contains("red", ignoreCase = true) -> STATUS_RED
                        colorStr.contains("yellow", ignoreCase = true) -> STATUS_YELLOW
                        else -> STATUS_NONE
                    }
                }
                else -> STATUS_NONE
            }

            // 解析倒计时
            countdown = when {
                extras.containsKey(KEY_CRUISE_COUNTDOWN) ->
                    extras.getInt(KEY_CRUISE_COUNTDOWN, 0)
                extras.containsKey(KEY_CRUISE_TIME) ->
                    extras.getInt(KEY_CRUISE_TIME, 0)
                extras.containsKey("seconds") ->
                    extras.getInt("seconds", 0)
                else -> 0
            }

            // 设置来源
            source = "cruise"
            timestamp = System.currentTimeMillis()
        }

        // 处理红绿灯信息
        if (trafficLightInfo.isValid()) {
            processTrafficLightInfo(trafficLightInfo)
        }
    }

    /**
     * 解析巡航模式JSON数据
     */
    private fun parseCruiseJsonData(json: JSONObject) {
        val trafficLightInfo = TrafficLightInfo().apply {
            // 解析状态
            val statusValue = json.optInt("status", -1)
            status = when (statusValue) {
                COLOR_GREEN -> STATUS_GREEN
                COLOR_RED -> STATUS_RED
                COLOR_YELLOW -> STATUS_YELLOW
                else -> {
                    val colorStr = json.optString("color", "").toLowerCase()
                    when {
                        colorStr.contains("green") -> STATUS_GREEN
                        colorStr.contains("red") -> STATUS_RED
                        colorStr.contains("yellow") -> STATUS_YELLOW
                        else -> STATUS_NONE
                    }
                }
            }

            // 解析倒计时
            countdown = json.optInt("countdown", 0)
            if (countdown == 0) {
                countdown = json.optInt("time", 0)
            }

            // 解析方向
            direction = json.optInt("direction", DIRECTION_STRAIGHT)

            // 设置来源
            source = "cruise_json"
            timestamp = System.currentTimeMillis()
        }

        // 处理红绿灯信息
        if (trafficLightInfo.isValid()) {
            processTrafficLightInfo(trafficLightInfo)
        }
    }

    /**
     * 解析通用红绿灯字符串格式
     */
    private fun parseTrafficLightString(trafficLightStr: String, source: String) {
        try {
            // 格式1: "green:15", "red:30:1" (颜色:倒计时:方向)
            val parts = trafficLightStr.split(":")
            if (parts.isNotEmpty()) {
                val colorStr = parts[0].toLowerCase()
                val countdown = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
                val direction = if (parts.size > 2) parts[2].toIntOrNull() ?: DIRECTION_STRAIGHT else DIRECTION_STRAIGHT

                val status = when {
                    colorStr.contains("green") -> STATUS_GREEN
                    colorStr.contains("red") -> STATUS_RED
                    colorStr.contains("yellow") -> STATUS_YELLOW
                    else -> STATUS_NONE
                }

                if (status != STATUS_NONE) {
                    val trafficLightInfo = TrafficLightInfo().apply {
                        this.status = status
                        this.countdown = countdown
                        this.direction = direction
                        this.source = source
                        timestamp = System.currentTimeMillis()
                    }

                    processTrafficLightInfo(trafficLightInfo)
                }
            }
        } catch (e: Exception) {
            Log.w("TrafficLightManager", "解析红绿灯字符串失败: $trafficLightStr", e)
        }
    }

    /**
     * 解析通用红绿灯数据
     */
    private fun parseGenericTrafficLightData(intent: Intent) {
        try {
            val extras = intent.extras ?: return
            val allKeys = extras.keySet()

            // 检查是否包含红绿灯相关字段
            val hasTrafficLightKey = allKeys.any { key ->
                key.contains("traffic", ignoreCase = true) &&
                        key.contains("light", ignoreCase = true)
            }

            val hasLightKey = allKeys.any { key ->
                key.contains("light", ignoreCase = true) &&
                        !key.contains("flash", ignoreCase = true)
            }

            if (!hasTrafficLightKey && !hasLightKey) {
                return
            }

            // 尝试解析各种字段组合
            var status = STATUS_NONE
            var countdown = 0
            var direction = DIRECTION_STRAIGHT

            // 查找状态字段
            for (key in allKeys) {
                when {
                    key.contains("status", ignoreCase = true) ||
                            key.contains("color", ignoreCase = true) -> {

                        val value = extras.get(key)
                        status = when {
                            value.toString().contains("green", ignoreCase = true) -> STATUS_GREEN
                            value.toString().contains("red", ignoreCase = true) -> STATUS_RED
                            value.toString().contains("yellow", ignoreCase = true) -> STATUS_YELLOW
                            value is Int -> when (value) {
                                COLOR_GREEN -> STATUS_GREEN
                                COLOR_RED -> STATUS_RED
                                COLOR_YELLOW -> STATUS_YELLOW
                                else -> STATUS_NONE
                            }
                            else -> STATUS_NONE
                        }

                        if (status != STATUS_NONE) break
                    }
                }
            }

            // 查找倒计时字段
            for (key in allKeys) {
                if (key.contains("count", ignoreCase = true) ||
                    key.contains("time", ignoreCase = true) ||
                    key.contains("second", ignoreCase = true)) {

                    val value = extras.get(key)
                    when (value) {
                        is Int -> countdown = value
                        is String -> countdown = value.toIntOrNull() ?: 0
                        else -> {}
                    }

                    if (countdown > 0) break
                }
            }

            // 查找方向字段
            for (key in allKeys) {
                if (key.contains("dir", ignoreCase = true) ||
                    key.contains("turn", ignoreCase = true)) {

                    val value = extras.get(key)
                    direction = when (value) {
                        is Int -> value
                        is String -> when {
                            value.contains("left", ignoreCase = true) -> DIRECTION_LEFT
                            value.contains("right", ignoreCase = true) -> DIRECTION_RIGHT
                            value.contains("straight", ignoreCase = true) -> DIRECTION_STRAIGHT
                            else -> DIRECTION_STRAIGHT
                        }
                        else -> DIRECTION_STRAIGHT
                    }
                    break
                }
            }

            // 如果找到有效数据，创建信息对象
            if (status != STATUS_NONE && countdown >= 0) {
                val trafficLightInfo = TrafficLightInfo().apply {
                    this.status = status
                    this.countdown = countdown
                    this.direction = direction
                    this.source = "generic"
                    timestamp = System.currentTimeMillis()
                }

                processTrafficLightInfo(trafficLightInfo)
            }

        } catch (e: Exception) {
            Log.w("TrafficLightManager", "解析通用红绿灯数据失败", e)
        }
    }

    /**
     * 处理红绿灯信息
     */
    private fun processTrafficLightInfo(info: TrafficLightInfo) {
        // 记录接收时间
        lastValidDataTime = System.currentTimeMillis()

        // 验证数据有效性
        if (!info.isValid()) {
            Log.d("TrafficLightManager", "收到无效红绿灯数据，忽略")
            return
        }

        // 确保倒计时非负
        info.countdown = max(0, info.countdown)

        Log.d("TrafficLightManager", "处理红绿灯数据[${info.source}]: " +
                "状态=${getStatusString(info.status)}, " +
                "倒计时=${info.countdown}s, " +
                "方向=${getDirectionString(info.direction)}")

        // 在主线程更新UI
        handler.post {
            try {
                // 取消之前的自动隐藏
                handler.removeCallbacks(autoHideRunnable)

                // 发送更新
                updateCallback(info)

                // 设置自动隐藏（倒计时结束后8秒，或15秒无新数据）
                val hideDelay = if (info.countdown > 0) {
                    (info.countdown + 8) * 1000L
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
        broadcastLog.clear()
    }

    /**
     * 手动测试：模拟接收红绿灯数据（用于调试）
     */
    fun simulateTrafficLightData(
        status: Int,
        countdown: Int,
        direction: Int = DIRECTION_STRAIGHT,
        source: String = "test"
    ) {
        val testInfo = TrafficLightInfo().apply {
            this.status = status
            this.countdown = countdown
            this.direction = direction
            this.source = source
            timestamp = System.currentTimeMillis()
        }

        Log.d("TrafficLightManager", "模拟红绿灯数据: " +
                "状态=${getStatusString(status)}, " +
                "倒计时=${countdown}s, " +
                "方向=${getDirectionString(direction)}")

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
            STATUS_FLASHING_YELLOW -> "黄闪"
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
            DIRECTION_STRAIGHT_LEFT -> "直行+左转"
            DIRECTION_STRAIGHT_RIGHT -> "直行+右转"
            DIRECTION_ALL -> "所有方向"
            else -> "直行"
        }
    }

    /**
     * 添加广播日志（调试用）
     */
    private fun addBroadcastLog(message: String) {
        synchronized(broadcastLog) {
            broadcastLog.add("${System.currentTimeMillis()}: $message")
            if (broadcastLog.size > MAX_LOG_SIZE) {
                broadcastLog.removeAt(0)
            }
        }

        // 在调试模式下输出到Logcat
        if (BuildConfig.DEBUG) {
            Log.d("TrafficLightManager", message)
        }
    }

    /**
     * 获取广播日志（调试用）
     */
    fun getBroadcastLog(): List<String> {
        return synchronized(broadcastLog) {
            broadcastLog.toList()
        }
    }

    /**
     * 清除广播日志
     */
    fun clearBroadcastLog() {
        synchronized(broadcastLog) {
            broadcastLog.clear()
        }
    }

    /**
     * 检查是否已注册
     */
    fun isRunning(): Boolean {
        return isRegistered
    }
}