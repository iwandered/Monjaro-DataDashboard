package ru.monjaro.mconfig

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import ru.monjaro.mconfig.R
import androidx.preference.PreferenceManager
import ru.monjaro.mconfig.databinding.DataFragmentBinding
import java.text.SimpleDateFormat
import java.util.*


class BasicDataHandler(
    private val binding: DataFragmentBinding,
    private val fragment: androidx.fragment.app.Fragment
) {

    // 使用 lambda 表达式的正确方式
    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences: SharedPreferences, key: String? ->
            onSharedPreferenceChanged(sharedPreferences, key ?: "")
        }

    private var timeUpdateHandler: Handler? = null
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateDateTime()
            timeUpdateHandler?.postDelayed(this, 1000)
        }
    }

    // 转向灯状态变量
    private var isLeftTurnOn = false
    private var isRightTurnOn = false
    private var isHazardOn = false
    private var preHazardLeftTurnState = false
    private var preHazardRightTurnState = false

    // 转向灯闪烁Handler
    private var blinkHandler = Handler(Looper.getMainLooper())
    private var blinkRunnable: Runnable? = null
    private var blinkState = true

    // 轮胎数据
    private var tirePressureFL: Float = Float.MIN_VALUE
    private var tirePressureFR: Float = Float.MIN_VALUE
    private var tirePressureRL: Float = Float.MIN_VALUE
    private var tirePressureRR: Float = Float.MIN_VALUE
    private var tireTempFL: Float = Float.MIN_VALUE
    private var tireTempFR: Float = Float.MIN_VALUE
    private var tireTempRL: Float = Float.MIN_VALUE
    private var tireTempRR: Float = Float.MIN_VALUE

    // 油耗数据变量
    private var instantFuelConsumption: Float = Float.MIN_VALUE
    private var avgFuelConsumption: Float = Float.MIN_VALUE  // 单次点火平均油耗

    // 油耗缓存和状态跟踪
    private var lastValidInstantFuel: Float = Float.MIN_VALUE
    private var lastValidAvgFuel: Float? = null
    private var avgFuelLastUpdateTime: Long = 0
    private val AVG_FUEL_UPDATE_THRESHOLD = 3000L // 修改为3秒内无更新使用缓存值
    private var hasEverReceivedAvgFuel = false

    // 驾驶模式常量
    companion object {
        private const val DRIVE_MODE_SELECTION_ECO = 570491137
        private const val DRIVE_MODE_SELECTION_COMFORT = 570491138
        private const val DRIVE_MODE_SELECTION_DYNAMIC = 570491139
        private const val DRIVE_MODE_SELECTION_ADAPTIVE = 570491158
        private const val DRIVE_MODE_SELECTION_SNOW = 570491145  // 雪地模式
        private const val DRIVE_MODE_SELECTION_OFFROAD = 570491155
        private const val DRIVE_MODE_UNKNOWN = -1

        const val PREF_ODOMETER_KEY = "odometerValue"
        const val PREF_DRIVE_MODE_KEY = "driveModeCfg"
    }

    fun start() {
        setupDataListeners()
        startTimeUpdates()
        initializeDisplay()
    }

    fun stop() {
        stopTimeUpdates()
        stopTurnSignalBlink()
        unregisterPreferenceListeners()
        // 清理Handler
        blinkHandler.removeCallbacksAndMessages(null)
        timeUpdateHandler?.removeCallbacksAndMessages(null)
    }

    fun handleMessage(msg: Message) {
        when (msg.what) {
            // 基本数据
            IdNames.ODOMETER -> handleOdometerMessage(msg)
            IdNames.IGNITION_STATE -> handleIgnitionStateMessage(msg)
            IdNames.CAR_SPEED -> handleCarSpeedMessage(msg)
            IdNames.SENSOR_RPM -> handleRpmMessage(msg)
            IdNames.AMBIENT_TEMPERATURE -> handleAmbientTempMessage(msg)
            IdNames.INT_TEMPERATURE -> handleIntTempMessage(msg)
            IdNames.FUEL_LEVEL -> handleFuelLevelMessage(msg)
            IdNames.SENSOR_OIL_LEVEL -> handleOilLevelMessage(msg)
            IdNames.SENSOR_TYPE_GEAR -> handleGearMessage(msg)
            IdNames.SEAT_OCCUPATION_STATUS_PASSENGER -> handleSeatOccupationMessage(msg)

            // 转向灯
            IdNames.LIGHT_LEFT_TURN -> handleLeftTurnMessage(msg)
            IdNames.LIGHT_RIGHT_TURN -> handleRightTurnMessage(msg)
            IdNames.LIGHT_HAZARD_FLASHERS -> handleHazardMessage(msg)

            // 胎压胎温
            IdNames.TIRE_PRESSURE_FL -> handleTirePressureFLMessage(msg)
            IdNames.TIRE_PRESSURE_FR -> handleTirePressureFRMessage(msg)
            IdNames.TIRE_PRESSURE_RL -> handleTirePressureRLMessage(msg)
            IdNames.TIRE_PRESSURE_RR -> handleTirePressureRRMessage(msg)
            IdNames.TIRE_TEMP_FL -> handleTireTempFLMessage(msg)
            IdNames.TIRE_TEMP_FR -> handleTireTempFRMessage(msg)
            IdNames.TIRE_TEMP_RL -> handleTireTempRLMessage(msg)
            IdNames.TIRE_TEMP_RR -> handleTireTempRRMessage(msg)

            // 油耗数据
            IdNames.INSTANT_FUEL_CONSUMPTION -> handleInstantFuelMessage(msg)
            IdNames.SENSOR_AVG_FUEL_CONSUMPTION -> handleAvgFuelMessage(msg)
        }
    }

    // ============== 基本数据消息处理 ==============
    private fun handleOdometerMessage(msg: Message) {
        try {
            val mileageText = msg.obj as String
            val mileage = mileageText.toInt()
            updateOdometerDisplay(mileage.toFloat())
            // 减少日志：只在里程有显著变化时记录
            if (mileage % 10 == 0) {
                Log.d("BasicDataHandler", "里程: $mileageText km")
            }
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理里程数据失败", e)
        }
    }

    private fun handleIgnitionStateMessage(msg: Message) {
        try {
            val state = msg.obj as? String ?: "OFF"
            Log.d("BasicDataHandler", "点火状态: $state")
            // 这里不再处理熄火时间记录逻辑
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理点火状态失败", e)
        }
    }

    private fun handleCarSpeedMessage(msg: Message) {
        val speedText = msg.obj as? String ?: "0"
        // 减少日志：只在速度有显著变化时记录
        updateCarSpeedDisplay(speedText)
    }

    private fun handleRpmMessage(msg: Message) {
        val rpmText = msg.obj as? String ?: "0"
        // 减少日志：只在转速有显著变化时记录
        updateRpmDisplay(rpmText)
    }

    private fun handleAmbientTempMessage(msg: Message) {
        // 减少日志：只在温度变化时记录
        val temp = msg.obj as? String
        if (temp != null) {
            Log.d("BasicDataHandler", "外温: $temp")
        }
        updateAmbientTempDisplay(temp)
    }

    private fun handleIntTempMessage(msg: Message) {
        // 减少日志：只在温度变化时记录
        val temp = msg.obj as? String
        if (temp != null) {
            Log.d("BasicDataHandler", "内温: $temp")
        }
        updateIntTempDisplay(temp)
    }

    private fun handleFuelLevelMessage(msg: Message) {
        val fuel = msg.obj as? String
        // 减少日志：只在油量变化时记录
        if (fuel != null) {
            Log.d("BasicDataHandler", "油量: $fuel")
        }
        updateFuelLevelDisplay(fuel)
    }

    private fun handleOilLevelMessage(msg: Message) {
        val oil = msg.obj as? String
        // 减少日志：只在机油状态变化时记录
        if (oil != null && oil != "ok") {
            Log.d("BasicDataHandler", "机油状态: $oil")
        }
        updateOilLevelDisplay(oil)
    }

    private fun handleGearMessage(msg: Message) {
        val gearText = msg.obj as? String ?: "P"
        // 减少日志：只在档位变化时记录
        Log.d("BasicDataHandler", "档位: $gearText")
        updateGearDisplay(gearText)
    }

    private fun handleSeatOccupationMessage(msg: Message) {
        // 减少日志：只在状态变化时记录
        Log.d("BasicDataHandler", "乘客座位状态: ${msg.obj}")
    }

    // ============== 转向灯消息处理 ==============
    private fun handleLeftTurnMessage(msg: Message) {
        val status = msg.obj as String
        if (!isHazardOn) {
            isLeftTurnOn = status == "1"
        }
        updateTurnSignals()
        // 减少日志：只在状态变化时记录
        if (isLeftTurnOn) {
            Log.d("BasicDataHandler", "左转向灯开启")
        }
    }

    private fun handleRightTurnMessage(msg: Message) {
        val status = msg.obj as String
        if (!isHazardOn) {
            isRightTurnOn = status == "1"
        }
        updateTurnSignals()
        // 减少日志：只在状态变化时记录
        if (isRightTurnOn) {
            Log.d("BasicDataHandler", "右转向灯开启")
        }
    }

    private fun handleHazardMessage(msg: Message) {
        val status = msg.obj as String
        val wasHazardOn = isHazardOn
        isHazardOn = status == "1"

        if (isHazardOn && !wasHazardOn) {
            preHazardLeftTurnState = isLeftTurnOn
            preHazardRightTurnState = isRightTurnOn
            isLeftTurnOn = true
            isRightTurnOn = true
        } else if (!isHazardOn && wasHazardOn) {
            isLeftTurnOn = preHazardLeftTurnState
            isRightTurnOn = preHazardRightTurnState
            preHazardLeftTurnState = false
            preHazardRightTurnState = false
        }
        updateTurnSignals()
        // 减少日志：只在状态变化时记录
        Log.d("BasicDataHandler", "双闪灯: ${if (isHazardOn) "开启" else "关闭"}")
    }

    // ============== 转向灯显示方法 ==============
    private fun updateTurnSignals() {
        fragment.activity?.runOnUiThread {
            if (isHazardOn) {
                // 双闪模式：同时显示左右转向灯
                binding.tvLeftTurn.text = "◀"
                binding.tvRightTurn.text = "▶"
                binding.tvLeftTurn.visibility = View.VISIBLE
                binding.tvRightTurn.visibility = View.VISIBLE
            } else {
                // 普通转向模式
                binding.tvLeftTurn.text = if (isLeftTurnOn) "◀" else ""
                binding.tvRightTurn.text = if (isRightTurnOn) "▶" else ""
                binding.tvLeftTurn.visibility = if (isLeftTurnOn) View.VISIBLE else View.INVISIBLE
                binding.tvRightTurn.visibility = if (isRightTurnOn) View.VISIBLE else View.INVISIBLE
            }

            if (isLeftTurnOn || isRightTurnOn || isHazardOn) {
                startTurnSignalBlink()
            } else {
                stopTurnSignalBlink()
            }
        }
    }

    private fun startTurnSignalBlink() {
        if (blinkRunnable != null) return

        blinkRunnable = object : Runnable {
            override fun run() {
                fragment.activity?.runOnUiThread {
                    if (isLeftTurnOn || isRightTurnOn || isHazardOn) {
                        if (blinkState) {
                            // 闪烁状态：高亮
                            binding.tvLeftTurn.alpha = if (isLeftTurnOn || isHazardOn) 1.0f else 0.3f
                            binding.tvRightTurn.alpha = if (isRightTurnOn || isHazardOn) 1.0f else 0.3f
                        } else {
                            // 非闪烁状态：暗淡
                            binding.tvLeftTurn.alpha = 0.3f
                            binding.tvRightTurn.alpha = 0.3f
                        }
                        blinkState = !blinkState
                        // 修改：将闪烁间隔从500ms改为250ms，频率提高一倍
                        blinkHandler.postDelayed(this, 250)
                    }
                }
            }
        }
        blinkHandler.post(blinkRunnable!!)
    }

    private fun stopTurnSignalBlink() {
        blinkRunnable?.let {
            blinkHandler.removeCallbacks(it)
            blinkRunnable = null
        }
        fragment.activity?.runOnUiThread {
            binding.tvLeftTurn.alpha = 1.0f
            binding.tvRightTurn.alpha = 1.0f
        }
    }

    // ============== 胎压胎温消息处理 ==============
    private fun handleTirePressureFLMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tirePressureFL = value
            updateTirePressureDisplay()
            // 减少日志：只在值有显著变化时记录
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理左前轮胎压数据失败", e)
        }
    }

    private fun handleTirePressureFRMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tirePressureFR = value
            updateTirePressureDisplay()
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理右前轮胎压数据失败", e)
        }
    }

    private fun handleTirePressureRLMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tirePressureRL = value
            updateTirePressureDisplay()
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理左后轮胎压数据失败", e)
        }
    }

    private fun handleTirePressureRRMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tirePressureRR = value
            updateTirePressureDisplay()
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理右后轮胎压数据失败", e)
        }
    }

    private fun handleTireTempFLMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tireTempFL = value
            updateTireTemperatureDisplay()
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理左前轮胎温数据失败", e)
        }
    }

    private fun handleTireTempFRMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tireTempFR = value
            updateTireTemperatureDisplay()
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理右前轮胎温数据失败", e)
        }
    }

    private fun handleTireTempRLMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tireTempRL = value
            updateTireTemperatureDisplay()
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理左后轮胎温数据失败", e)
        }
    }

    private fun handleTireTempRRMessage(msg: Message) {
        try {
            val value = (msg.obj as? String)?.toFloatOrNull() ?: Float.MIN_VALUE
            tireTempRR = value
            updateTireTemperatureDisplay()
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理右后轮胎温数据失败", e)
        }
    }

    // ============== 油耗消息处理 ==============
    private fun handleInstantFuelMessage(msg: Message) {
        try {
            val valueText = msg.obj as? String ?: "--"
            val value = if (valueText == "--") Float.MIN_VALUE else valueText.toFloatOrNull() ?: Float.MIN_VALUE

            // 更新最近有效的瞬时油耗值
            if (!isInvalidValue(value)) {
                lastValidInstantFuel = value
            }

            // 总是更新瞬时油耗显示
            instantFuelConsumption = value
            updateInstantFuelDisplay()

            // 减少日志：只在油耗有显著变化时记录
            if (value != Float.MIN_VALUE && value % 1 == 0f) {
                Log.d("BasicDataHandler", "瞬时油耗: $valueText L/100km")
            }
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理瞬时油耗数据失败", e)
        }
    }

    private fun handleAvgFuelMessage(msg: Message) {
        try {
            val valueText = msg.obj as? String ?: "--"
            val value = if (valueText == "--") Float.MIN_VALUE else valueText.toFloatOrNull() ?: Float.MIN_VALUE
            val currentTime = System.currentTimeMillis()

            if (!isInvalidValue(value)) {
                // 收到有效数据，更新缓存值
                lastValidAvgFuel = value
                avgFuelLastUpdateTime = currentTime
                hasEverReceivedAvgFuel = true
                Log.d("BasicDataHandler", "收到新单次点火平均油耗数据: $valueText")
            } else if (hasEverReceivedAvgFuel &&
                (currentTime - avgFuelLastUpdateTime < AVG_FUEL_UPDATE_THRESHOLD)) {
                // 数据无效，但仍在3秒阈值内，使用缓存值
                Log.d("BasicDataHandler", "使用缓存的单次点火平均油耗数据")
            } else {
                // 数据无效且超过3秒阈值，显示占位符
                lastValidAvgFuel = null
                Log.d("BasicDataHandler", "单次点火平均油耗数据无效或过期")
            }

            // 更新显示（会使用缓存值或占位符）
            avgFuelConsumption = value
            updateAvgFuelDisplay()

        } catch (e: Exception) {
            Log.e("BasicDataHandler", "处理平均油耗数据失败", e)
        }
    }

    // ============== 时间更新方法 ==============
    private fun startTimeUpdates() {
        if (timeUpdateHandler == null) {
            timeUpdateHandler = Handler(Looper.getMainLooper())
            timeUpdateHandler?.post(timeUpdateRunnable)
        }
    }

    private fun stopTimeUpdates() {
        timeUpdateHandler?.removeCallbacks(timeUpdateRunnable)
        timeUpdateHandler = null
    }

    private fun updateDateTime() {
        try {
            val currentTime = System.currentTimeMillis()

            // 格式化日期
            val dateFormat = SimpleDateFormat("MM月dd日", Locale.getDefault())
            val dateString = dateFormat.format(currentTime)

            // 格式化时间
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val timeString = timeFormat.format(currentTime)

            // 获取星期几
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = currentTime
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

            // 使用 when 表达式
            val weekDayString = when (dayOfWeek) {
                1 -> "周日"
                2 -> "周一"
                3 -> "周二"
                4 -> "周三"
                5 -> "周四"
                6 -> "周五"
                7 -> "周六"
                else -> ""
            }

            // 更新UI
            binding.tvDate.text = "$dateString $weekDayString"
            binding.tvTime.text = timeString
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新日期时间失败", e)
        }
    }

    // ============== 显示更新方法 ==============
    private fun updateCarSpeedDisplay(speed: String?) {
        binding.carSpeed.text = speed ?: "0"
    }

    private fun updateRpmDisplay(rpm: String?) {
        binding.rpmValue.text = rpm ?: "0"
    }

    private fun updateGearDisplay(gear: String?) {
        // 确保档位显示正确格式
        val displayGear = when {
            gear == null || gear.isEmpty() -> "P"
            gear.matches(Regex("^[DM][1-8]$")) -> gear // D1-D8 或 M1-M8
            gear == "P" || gear == "R" || gear == "N" || gear == "D" || gear == "M" -> gear
            // 处理纯数字档位（从传感器直接返回的1-8）
            gear.matches(Regex("^[1-8]$")) -> {
                // 根据上下文判断是D档还是M档，这里默认使用D档
                "D$gear"
            }
            // 新增：处理"D1"和"D2"格式（来自MConfigThread的静止D档显示）
            gear == "D1" -> "D1"
            gear == "D2" -> "D2"
            else -> {
                Log.w("BasicDataHandler", "未知档位格式: $gear")
                "P"
            }
        }

        // 只有当档位真正变化时才更新UI，避免不必要的刷新
        if (binding.gearStatus.text != displayGear) {
            binding.gearStatus.text = displayGear
        }
    }

    private fun updateFuelLevelDisplay(fuelLevel: String?) {
        binding.FuelLevel.text = fuelLevel ?: "0"
    }

    private fun updateIntTempDisplay(temp: String?) {
        binding.intTemp.text = temp ?: "0"
    }

    private fun updateAmbientTempDisplay(temp: String?) {
        binding.aTemp.text = temp ?: "0"
    }

    private fun updateOilLevelDisplay(oilLevel: String?) {
        try {
            val oilText = oilLevel ?: "--"

            // 更新机油数值显示
            binding.OilLevel.text = oilText

            // 获取机油标题和整个机油布局
            val oilTitle = binding.root.findViewById<android.widget.TextView>(R.id.oil_level_title)
            val oilLayout = binding.root.findViewById<android.view.ViewGroup>(R.id.oil_level_layout)

            if (oilLayout != null) {
                // 当机油状态为"ok"时隐藏整个机油显示（标题+数值）
                if (oilText.equals("ok", ignoreCase = true)) {
                    oilLayout.visibility = View.INVISIBLE
                } else {
                    oilLayout.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新机油显示失败", e)
            binding.OilLevel.text = "--"
            // 确保显示机油布局
            val oilLayout = binding.root.findViewById<android.view.ViewGroup>(R.id.oil_level_layout)
            oilLayout?.visibility = View.VISIBLE
        }
    }


    // ============== 胎压胎温显示方法 ==============
    private fun updateTirePressureDisplay() {
        try {
            // 格式化胎压显示（取整数）
            binding.tvTireFlPressure.text = formatTirePressure(tirePressureFL)
            binding.tvTireFrPressure.text = formatTirePressure(tirePressureFR)
            binding.tvTireRlPressure.text = formatTirePressure(tirePressureRL)
            binding.tvTireRrPressure.text = formatTirePressure(tirePressureRR)
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新胎压显示失败", e)
        }
    }

    private fun updateTireTemperatureDisplay() {
        try {
            // 格式化胎温显示（取整数）
            binding.tvTireFlTemp.text = formatTireTemperature(tireTempFL)
            binding.tvTireFrTemp.text = formatTireTemperature(tireTempFR)
            binding.tvTireRlTemp.text = formatTireTemperature(tireTempRL)
            binding.tvTireRrTemp.text = formatTireTemperature(tireTempRR)
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新胎温显示失败", e)
        }
    }

    private fun formatTirePressure(value: Float): String {
        return if (isInvalidValue(value)) {
            "--"
        } else {
            // 显示整数，无单位
            String.format("%.0f", value)
        }
    }

    private fun formatTireTemperature(value: Float): String {
        return if (isInvalidValue(value)) {
            "--"
        } else {
            // 显示整数，无单位
            String.format("%.0f", value)
        }
    }

    // ============== 油耗显示方法 ==============
    // 分离的瞬时油耗显示方法
    private fun updateInstantFuelDisplay() {
        try {
            val instantFuelText = formatInstantFuelConsumption(instantFuelConsumption)

            // 控制瞬时油耗显示
            if (isInvalidValue(instantFuelConsumption)) {
                binding.tvInstantFuel.visibility = View.INVISIBLE
                binding.tvInstantFuel.text = ""
            } else {
                binding.tvInstantFuel.visibility = View.VISIBLE
                binding.tvInstantFuel.text = instantFuelText
            }

            // 更新容器可见性
            updateFuelContainerVisibility()

        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新瞬时油耗显示失败", e)
        }
    }

    // 分离的平均油耗显示方法
    private fun updateAvgFuelDisplay() {
        try {
            // 决定显示哪个值：优先使用缓存值，其次使用当前值，最后使用占位符
            val displayValue = when {
                lastValidAvgFuel != null -> lastValidAvgFuel!!
                !isInvalidValue(avgFuelConsumption) -> avgFuelConsumption
                else -> Float.MIN_VALUE
            }

            val avgFuelText = formatAvgFuelConsumption(displayValue)

            fragment.activity?.runOnUiThread {
                if (isInvalidValue(displayValue) && !hasEverReceivedAvgFuel) {
                    binding.tvAvgFuel.text = "--"
                    binding.tvAvgFuel.visibility = View.VISIBLE
                } else if (isInvalidValue(displayValue)) {
                    binding.tvAvgFuel.text = "--"
                    binding.tvAvgFuel.visibility = View.VISIBLE
                } else {
                    binding.tvAvgFuel.text = avgFuelText
                    binding.tvAvgFuel.visibility = View.VISIBLE
                }

                updateFuelContainerVisibility()
            }

        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新平均油耗显示失败", e)
            fragment.activity?.runOnUiThread {
                binding.tvAvgFuel.text = lastValidAvgFuel?.let { formatAvgFuelConsumption(it) } ?: "--"
                binding.tvAvgFuel.visibility = View.VISIBLE
                updateFuelContainerVisibility()
            }
        }
    }

    // 统一更新油耗容器可见性
    private fun updateFuelContainerVisibility() {
        val hasInstantFuel = !isInvalidValue(instantFuelConsumption)
        val hasAvgFuel = !isInvalidValue(avgFuelConsumption) || lastValidAvgFuel != null

        fragment.activity?.runOnUiThread {
            binding.fuelConsumptionContainer.visibility =
                if (hasInstantFuel || hasAvgFuel) View.VISIBLE else View.GONE
        }
    }

    private fun formatInstantFuelConsumption(value: Float): String {
        return if (isInvalidValue(value)) {
            ""
        } else if (value == 0.0f) {
            "0"
        } else {
            String.format("%.1f", value)
        }
    }

    private fun formatAvgFuelConsumption(value: Float?): String {
        return when {
            value == null -> "--"
            isInvalidValue(value) -> "--"
            value == 0.0f -> "0"
            else -> String.format("%.1f", value)
        }
    }

    private fun isInvalidValue(value: Float): Boolean {
        // Float.MIN_VALUE 表示无效数据
        return value == Float.MIN_VALUE || value == -1f
    }


    fun updateOdometerDisplay(odometerValue: Float) {
        try {
            val integerOdometer = odometerValue.toInt()
            val formattedOdometer = "$integerOdometer km"
            binding.tvOdometer.text = formattedOdometer
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新里程显示失败", e)
            binding.tvOdometer.text = "-- km"
        }
    }
    // ============== 驾驶模式显示方法 ==============
    fun updateDrivingModeDisplay(driveMode: Int) {
        try {
            val modeText = getDrivingModeString(driveMode)
            binding.tvDrivingModeValue.text = modeText

            // 根据驾驶模式设置不同的文本颜色
            val colorResId = when (driveMode) {
                DRIVE_MODE_SELECTION_COMFORT -> R.color.comfort_mode_color     // 舒适模式 - 浅蓝色
                DRIVE_MODE_SELECTION_SNOW -> R.color.snow_mode_color           // 雪地模式 - 白色
                DRIVE_MODE_SELECTION_ADAPTIVE -> R.color.adaptive_mode_color   // 智能模式 - 橙色
                DRIVE_MODE_SELECTION_DYNAMIC -> R.color.dynamic_mode_color     // 运动模式 - 红色
                DRIVE_MODE_SELECTION_ECO -> R.color.eco_mode_color             // 经济模式 - 绿色
                DRIVE_MODE_SELECTION_OFFROAD -> R.color.offroad_mode_color     // 越野模式 - 紫色
                else -> R.color.unknown_mode_color                              // 未知模式 - 白色
            }

            // 设置文本颜色
            binding.tvDrivingModeValue.setTextColor(
                ContextCompat.getColor(fragment.requireContext(), colorResId)
            )

        } catch (e: Exception) {
            Log.e("BasicDataHandler", "更新驾驶模式显示失败", e)
            binding.tvDrivingModeValue.text = "未知"
            binding.tvDrivingModeValue.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.unknown_mode_color))
        }
    }

    private fun getDrivingModeString(mode: Int): String {
        return when (mode) {
            DRIVE_MODE_SELECTION_ECO -> "经济"
            DRIVE_MODE_SELECTION_COMFORT -> "舒适"
            DRIVE_MODE_SELECTION_DYNAMIC -> "运动"
            DRIVE_MODE_SELECTION_ADAPTIVE -> "智能"
            DRIVE_MODE_SELECTION_SNOW -> "雪地"  // 雪地模式
            DRIVE_MODE_SELECTION_OFFROAD -> "越野"
            else -> "加载"
        }
    }

    // ============== 数据监听器 ==============
    private fun setupDataListeners() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            PREF_DRIVE_MODE_KEY -> {
                val driveMode = sharedPreferences.getInt(key, DRIVE_MODE_UNKNOWN)
                fragment.activity?.runOnUiThread {
                    updateDrivingModeDisplay(driveMode)
                }
            }
            PREF_ODOMETER_KEY -> {
                val odometerValue = sharedPreferences.getFloat(key, 0f)
                fragment.activity?.runOnUiThread {
                    updateOdometerDisplay(odometerValue)
                }
            }
        }
    }

    private fun unregisterPreferenceListeners() {
        try {
            val preferences = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        } catch (e: Exception) {
            Log.e("BasicDataHandler", "取消注册监听器时出错", e)
        }
    }

    // ============== 初始化方法 ==============
    private fun initializeDisplay() {
        // 初始化车辆数据显示
        binding.rpmValue.text = "0"
        binding.carSpeed.text = "0"
        binding.gearStatus.text = "P"
        binding.FuelLevel.text = "0"
        binding.intTemp.text = "0"
        binding.aTemp.text = "0"
        binding.OilLevel.text = "--"

        // 初始化转向灯显示
        binding.tvLeftTurn.text = ""
        binding.tvRightTurn.text = ""
        binding.tvLeftTurn.visibility = View.INVISIBLE
        binding.tvRightTurn.visibility = View.INVISIBLE

        // 初始化胎压胎温显示
        binding.tvTireFlPressure.text = "--"
        binding.tvTireFrPressure.text = "--"
        binding.tvTireRlPressure.text = "--"
        binding.tvTireRrPressure.text = "--"
        binding.tvTireFlTemp.text = "--"
        binding.tvTireFrTemp.text = "--"
        binding.tvTireRlTemp.text = "--"
        binding.tvTireRrTemp.text = "--"

        // 初始化油耗显示
        binding.tvInstantFuel.text = ""
        binding.tvInstantFuel.visibility = View.INVISIBLE
        binding.tvAvgFuel.text = "--"
        binding.tvAvgFuel.visibility = View.VISIBLE
        binding.fuelConsumptionContainer.visibility = View.GONE

        // 初始化机油显示（默认为正常状态，隐藏显示）
        val oilLayout = binding.root.findViewById<android.view.ViewGroup>(R.id.oil_level_layout)
        oilLayout?.visibility = View.INVISIBLE

        // 强制更新一次显示
        val preferences = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
        val currentDriveMode = preferences.getInt(PREF_DRIVE_MODE_KEY, DRIVE_MODE_UNKNOWN)
        val currentOdometer = preferences.getFloat(PREF_ODOMETER_KEY, 0f)

        updateDrivingModeDisplay(currentDriveMode)
        updateOdometerDisplay(currentOdometer)
    }
}