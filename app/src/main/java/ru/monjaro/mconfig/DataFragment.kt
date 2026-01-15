package ru.monjaro.mconfig

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import ru.monjaro.mconfig.databinding.DataFragmentBinding

/**
 * 主数据显示Fragment
 * 负责显示车辆各项数据，包括红绿灯倒计时
 */
class DataFragment : Fragment() {

    private var _binding: DataFragmentBinding? = null
    private val binding get() = _binding!!

    // 各个模块的处理器
    private lateinit var basicDataHandler: BasicDataHandler
    private lateinit var climateSeatHandler: ClimateSeatHandler
    private var mediaInfo: MediaInfo? = null
    private lateinit var trafficLightManager: TrafficLightManager

    // MConfigManager 实例
    private var mConfigManager: MConfigManager? = null

    // 用于延迟检查的Handler
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    // 红绿灯测试相关
    private val isTestMode = BuildConfig.DEBUG
    private val testHandler = Handler(Looper.getMainLooper())
    private var testRunnable: Runnable? = null

    // ==================== 生命周期方法 ====================
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DataFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("DataFragment", "视图已创建")

        // 初始化各个处理器
        initializeHandlers()

        // 启动基本数据处理器（包含时间更新和转向灯）
        basicDataHandler.start()

        // 初始化媒体信息处理器（立即开始）
        initializeMediaInfo()

        // 初始化 MConfigManager
        initializeMConfigManager()

        // 初始化红绿灯管理器
        initializeTrafficLightManager()

        // 设置延迟刷新，确保UI完全加载
        setupDelayedRefresh()

        // 启动红绿灯测试模式（仅调试）
        if (isTestMode) {
            startTrafficLightTest()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("DataFragment", "Fragment恢复")

        // 确保显示是最新的
        binding.root.invalidate()
        binding.root.requestLayout()

        // 重新启动红绿灯监听
        trafficLightManager.start()

        // 重新启动测试模式（仅调试）
        if (isTestMode) {
            startTrafficLightTest()
        }

        // 媒体信息通过广播自动更新，无需手动触发
    }

    override fun onPause() {
        super.onPause()
        Log.d("DataFragment", "Fragment暂停")

        // 停止延迟刷新
        stopDelayedRefresh()

        // 暂停红绿灯监听
        trafficLightManager.stop()

        // 停止测试模式
        stopTrafficLightTest()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanupResources()
    }

    // ==================== 初始化方法 ====================
    private fun initializeHandlers() {
        // 初始化各个处理器
        basicDataHandler = BasicDataHandler(binding, this)
        climateSeatHandler = ClimateSeatHandler(binding)

        // 初始化显示
        climateSeatHandler.initializeDisplay()

        Log.d("DataFragment", "处理器初始化完成")
    }

    private fun initializeMediaInfo() {
        try {
            // 传入 mediaInfoContainer 以便控制显隐
            mediaInfo = MediaInfo(
                requireContext(),
                binding.mediaInfoContainer,
                binding.tvMediaArtist,
                binding.tvMediaTitle
            )
            mediaInfo?.start()
            Log.d("DataFragment", "媒体信息处理器初始化完成 (Broadcast模式)")
        } catch (e: Exception) {
            Log.e("DataFragment", "初始化媒体信息处理器失败", e)
        }
    }

    private fun initializeMConfigManager() {
        try {
            // 创建或获取 MConfigManager 实例并传入 Handler
            mConfigManager = MConfigManager(requireContext(), dataHandler)
            Log.d("DataFragment", "MConfigManager 初始化完成")
        } catch (e: Exception) {
            Log.e("DataFragment", "初始化 MConfigManager 失败", e)
        }
    }

    /**
     * 初始化红绿灯管理器（严格按照之前分析的方式）
     */
    private fun initializeTrafficLightManager() {
        try {
            Log.d("DataFragment", "开始初始化红绿灯管理器")

            // 确保视图已加载
            if (_binding == null) {
                Log.e("DataFragment", "Binding未初始化，无法设置红绿灯管理器")
                return
            }



            // 设置预览缩放（如果需要）
            // binding.trafficLightView.setPreviewScale(1.0f)

            // 创建红绿灯管理器，严格按照之前分析的高德实现
            trafficLightManager = TrafficLightManager(requireContext()) { trafficLightInfo ->
                // 在主线程更新UI
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (trafficLightInfo != null && trafficLightInfo.isValid() && !trafficLightInfo.isExpired()) {
                            // 显示红绿灯容器
                            binding.trafficLightContainer.visibility = View.VISIBLE

                            // 更新红绿灯视图状态
                            binding.trafficLightView.updateState(
                                trafficLightInfo.status,
                                trafficLightInfo.countdown,
                                trafficLightInfo.direction,
                                trafficLightInfo.source
                            )

                            // 记录日志（调试）
                            if (BuildConfig.DEBUG) {
                                Log.d("DataFragment", "高德红绿灯更新: " +
                                        "状态=${getStatusString(trafficLightInfo.status)}, " +
                                        "倒计时=${trafficLightInfo.countdown}s, " +
                                        "方向=${getDirectionString(trafficLightInfo.direction)}, " +
                                        "原始状态=${trafficLightInfo.amapStatus}, " +
                                        "原始方向=${trafficLightInfo.amapDirection}")
                            }
                        } else {
                            // 隐藏红绿灯容器
                            binding.trafficLightContainer.visibility = View.GONE

                            // 重置红绿灯视图状态
                            binding.trafficLightView.updateState(
                                TrafficLightManager.STATUS_NONE,
                                0,
                                TrafficLightManager.DIRECTION_STRAIGHT,
                                ""
                            )

                            if (BuildConfig.DEBUG) {
                                Log.d("DataFragment", "隐藏红绿灯显示")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("DataFragment", "更新红绿灯UI异常", e)
                        binding.trafficLightContainer.visibility = View.GONE
                    }
                }
            }

            // 启动红绿灯监听
            trafficLightManager.start()
            Log.d("DataFragment", "红绿灯管理器初始化完成（高德模式）")

        } catch (e: Exception) {
            Log.e("DataFragment", "初始化红绿灯管理器失败", e)
            // 确保红绿灯容器隐藏
            try {
                binding.trafficLightContainer.visibility = View.GONE
            } catch (e: Exception) {
                Log.e("DataFragment", "隐藏红绿灯容器失败", e)
            }
        }
    }

    /**
     * 启动红绿灯测试模式（模拟高德地图状态序列）
     */
    private fun startTrafficLightTest() {
        if (!BuildConfig.DEBUG) return

        stopTrafficLightTest()

        testRunnable = object : Runnable {
            private var testStep = 0
            private val testSequence = listOf(
                // 模拟绿灯倒计时阶段（状态=4，方向左转）
                mapOf(
                    "amapStatus" to 4,           // 绿灯倒计时
                    "redCountdown" to 5,         // 从5开始倒数
                    "amapDirection" to 1,        // 左转
                    "greenLast" to 3,            // 最后3秒
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 4,
                    "redCountdown" to 4,
                    "amapDirection" to 1,
                    "greenLast" to 3,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 4,
                    "redCountdown" to 3,
                    "amapDirection" to 1,
                    "greenLast" to 3,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 4,
                    "redCountdown" to 2,
                    "amapDirection" to 1,
                    "greenLast" to 3,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 4,
                    "redCountdown" to 1,
                    "amapDirection" to 1,
                    "greenLast" to 3,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 4,
                    "redCountdown" to 0,
                    "amapDirection" to 1,
                    "greenLast" to 3,
                    "waitRound" to 0
                ),
                // 模拟过渡状态（状态=-1，dir=0）
                mapOf(
                    "amapStatus" to -1,          // 过渡黄灯
                    "redCountdown" to 2,         // 短暂倒计时
                    "amapDirection" to 0,        // 方向为0，应使用历史方向
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to -1,
                    "redCountdown" to 1,
                    "amapDirection" to 0,
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to -1,
                    "redCountdown" to 0,
                    "amapDirection" to 0,
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                // 模拟红灯阶段（状态=1，保持左转方向）
                mapOf(
                    "amapStatus" to 1,           // 红灯
                    "redCountdown" to 94,        // 长红灯
                    "amapDirection" to 1,        // 左转
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 1,
                    "redCountdown" to 93,
                    "amapDirection" to 1,
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 1,
                    "redCountdown" to 92,
                    "amapDirection" to 1,
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 1,
                    "redCountdown" to 91,
                    "amapDirection" to 1,
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                mapOf(
                    "amapStatus" to 1,
                    "redCountdown" to 90,
                    "amapDirection" to 1,
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                // 测试方向右转
                mapOf(
                    "amapStatus" to 1,
                    "redCountdown" to 45,
                    "amapDirection" to 2,        // 右转
                    "greenLast" to 0,
                    "waitRound" to 0
                ),
                // 测试方向直行
                mapOf(
                    "amapStatus" to 4,
                    "redCountdown" to 20,
                    "amapDirection" to 4,        // 直行
                    "greenLast" to 3,
                    "waitRound" to 0
                ),
                // 测试黄灯
                mapOf(
                    "amapStatus" to 3,           // 黄灯
                    "redCountdown" to 0,
                    "amapDirection" to 4,
                    "greenLast" to 0,
                    "waitRound" to 0
                )
            )

            override fun run() {
                if (testStep >= testSequence.size) {
                    testStep = 0
                }

                val data = testSequence[testStep]

                // 模拟高德红绿灯数据
                trafficLightManager.simulateAmapTrafficLightData(
                    amapStatus = data["amapStatus"] as Int,
                    redCountdown = data["redCountdown"] as Int,
                    amapDirection = data["amapDirection"] as Int,
                    waitRound = data["waitRound"] as Int,
                    greenLast = data["greenLast"] as Int
                )

                testStep++

                // 每秒更新一次，模拟高德广播频率
                testHandler.postDelayed(this, 1000)
            }
        }

        // 延迟1秒开始测试
        testHandler.postDelayed(testRunnable!!, 1000)

        Log.d("DataFragment", "高德红绿灯测试模式已启动")
    }

    /**
     * 停止红绿灯测试模式
     */
    private fun stopTrafficLightTest() {
        testRunnable?.let {
            testHandler.removeCallbacks(it)
            testRunnable = null
        }

        if (BuildConfig.DEBUG) {
            Log.d("DataFragment", "红绿灯测试模式已停止")
        }
    }

    private fun setupDelayedRefresh() {
        // 取消之前的刷新任务
        stopDelayedRefresh()

        refreshRunnable = object : Runnable {
            override fun run() {
                Log.d("DataFragment", "执行延迟刷新 (UI布局)")

                // 可以在这里执行一些布局相关的重绘，如果需要的话
                try {
                    binding.root.requestLayout()
                    binding.root.invalidate()
                } catch (e: Exception) {
                    Log.e("DataFragment", "延迟刷新异常", e)
                }
            }
        }

        // 延迟200ms后执行
        refreshHandler.postDelayed(refreshRunnable!!, 200)
    }

    private fun stopDelayedRefresh() {
        refreshRunnable?.let {
            refreshHandler.removeCallbacks(it)
            refreshRunnable = null
        }
    }

    // ==================== 消息处理器 ====================
    private val dataHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            try {
                // 根据消息类型分发给不同的处理器
                when (msg.what) {
                    // 基本数据（包括时间、里程、车速、转速、温度、油量、机油、档位等）
                    in arrayOf(
                        IdNames.ODOMETER,
                        IdNames.IGNITION_STATE,
                        IdNames.CAR_SPEED,
                        IdNames.SENSOR_RPM,
                        IdNames.AMBIENT_TEMPERATURE,
                        IdNames.INT_TEMPERATURE,
                        IdNames.FUEL_LEVEL,
                        IdNames.SENSOR_OIL_LEVEL,
                        IdNames.SENSOR_TYPE_GEAR,
                        IdNames.SEAT_OCCUPATION_STATUS_PASSENGER,
                    ) -> basicDataHandler.handleMessage(msg)

                    // 转向灯数据
                    in arrayOf(
                        IdNames.LIGHT_LEFT_TURN,
                        IdNames.LIGHT_RIGHT_TURN,
                        IdNames.LIGHT_HAZARD_FLASHERS
                    ) -> basicDataHandler.handleMessage(msg)

                    // 胎压胎温数据
                    in arrayOf(
                        IdNames.TIRE_PRESSURE_FL,
                        IdNames.TIRE_PRESSURE_FR,
                        IdNames.TIRE_PRESSURE_RL,
                        IdNames.TIRE_PRESSURE_RR,
                        IdNames.TIRE_TEMP_FL,
                        IdNames.TIRE_TEMP_FR,
                        IdNames.TIRE_TEMP_RL,
                        IdNames.TIRE_TEMP_RR
                    ) -> basicDataHandler.handleMessage(msg)

                    // 油耗数据 - 处理瞬时油耗和单次点火平均油耗
                    IdNames.INSTANT_FUEL_CONSUMPTION -> basicDataHandler.handleMessage(msg)
                    IdNames.SENSOR_AVG_FUEL_CONSUMPTION -> basicDataHandler.handleMessage(msg)

                    // 空调和座椅系统
                    in arrayOf(
                        IdNames.HVAC_FUNC_FAN_SPEED,
                        IdNames.HVAC_FUNC_AUTO_FAN_SETTING,
                        IdNames.HVAC_FUNC_AUTO,
                        IdNames.HVAC_FUNC_BLOWING_MODE,
                        IdNames.HVAC_FUNC_CIRCULATION,
                        IdNames.HVAC_FUNC_AC,
                        IdNames.SEAT_HEATING_DRIVER,
                        IdNames.SEAT_HEATING_PASSENGER,
                        IdNames.SEAT_VENTILATION_DRIVER,
                        IdNames.SEAT_VENTILATION_PASSENGER
                    ) -> climateSeatHandler.handleMessage(msg)

                    else -> {
                        // 减少日志：只记录未处理的消息类型
                        if (BuildConfig.DEBUG && msg.what > 0) {
                            Log.d("DataFragment", "未处理的消息类型: ${msg.what}, 数据: ${msg.obj}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DataFragment", "处理消息异常: ${msg.what}", e)
            }
        }
    }

    // ==================== 资源清理 ====================
    private fun cleanupResources() {
        Log.d("DataFragment", "开始清理资源")

        // 停止延迟刷新
        stopDelayedRefresh()

        // 停止媒体信息处理器
        mediaInfo?.stop()
        mediaInfo = null

        // 停止基本数据处理器（包含时间更新和转向灯）
        basicDataHandler.stop()

        // 清理ClimateSeatHandler
        climateSeatHandler.cleanup()

        // 清理红绿灯管理器
        trafficLightManager.cleanup()

        // 清理红绿灯视图
        binding.trafficLightView.cleanup()

        // 停止测试模式
        stopTrafficLightTest()

        // 清理 MConfigManager
        mConfigManager?.destroy()
        mConfigManager = null

        // 清理Handler
        dataHandler.removeCallbacksAndMessages(null)
        refreshHandler.removeCallbacksAndMessages(null)
        testHandler.removeCallbacksAndMessages(null)

        _binding = null
        Log.d("DataFragment", "资源清理完成")
    }

    // ==================== 辅助方法 ====================
    /**
     * 获取状态字符串
     */
    private fun getStatusString(status: Int): String {
        return when (status) {
            TrafficLightManager.STATUS_GREEN -> "绿灯"
            TrafficLightManager.STATUS_RED -> "红灯"
            TrafficLightManager.STATUS_YELLOW -> "黄灯"
            else -> "未知"
        }
    }

    /**
     * 获取方向字符串
     */
    private fun getDirectionString(direction: Int): String {
        return when (direction) {
            TrafficLightManager.DIRECTION_STRAIGHT -> "直行"
            TrafficLightManager.DIRECTION_LEFT -> "左转"
            TrafficLightManager.DIRECTION_RIGHT -> "右转"
            else -> "直行"
        }
    }

    // ==================== 公共方法 ====================
    fun getHandler(): Handler {
        return dataHandler
    }

    fun refreshMediaInfo() {
        // 兼容保留
        mediaInfo?.triggerImmediateRefresh()
    }

    /**
     * 获取红绿灯管理器（用于调试）
     */
    fun getTrafficLightManager(): TrafficLightManager {
        return trafficLightManager
    }

    /**
     * 手动触发红绿灯测试（用于调试）
     */
    fun triggerTrafficLightTest() {
        if (!BuildConfig.DEBUG) return

        Log.d("DataFragment", "手动触发高德红绿灯测试")

        // 模拟高德绿灯倒计时（左转）
        trafficLightManager.simulateAmapTrafficLightData(
            amapStatus = 4,          // 绿灯倒计时
            redCountdown = 25,       // 25秒倒计时
            amapDirection = 1,       // 左转
            waitRound = 0,
            greenLast = 3
        )
    }

    /**
     * 获取红绿灯显示状态
     */
    fun getTrafficLightState(): Map<String, Any> {
        return mapOf(
            "visible" to (binding.trafficLightContainer.visibility == View.VISIBLE),
            "status" to binding.trafficLightView.getCurrentStatus(),
            "countdown" to binding.trafficLightView.getCurrentCountdown(),
            "direction" to binding.trafficLightView.getCurrentDirection(),
            "manager_running" to trafficLightManager.isRunning()
        )
    }
}