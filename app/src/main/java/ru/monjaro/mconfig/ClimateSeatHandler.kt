package ru.monjaro.mconfig

import android.os.Message
import android.util.Log
import androidx.core.content.ContextCompat
import ru.monjaro.mconfig.databinding.DataFragmentBinding

class ClimateSeatHandler(private val binding: DataFragmentBinding) {

    // 空调系统状态
    private var currentFanSpeed: Int = -1
    private var currentAutoFanSetting: Int = -1
    private var currentBlowingMode: Int = -1
    private var currentHvacAutoMode: Int = -1
    private var currentBlowingAutoMode: Int = -1
    private var currentCirculationMode: Int = -1
    private var currentACState: Int = -1

    // 座椅状态变量
    private var driverSeatHeating: Int = 0
    private var driverSeatVentilation: Int = 0
    private var passengerSeatHeating: Int = 0
    private var passengerSeatVentilation: Int = 0

    // 自动模式常量
    private companion object {
        private const val AUTO_MODE_ON = 1
        private const val AUTO_MODE_OFF = 0
    }

    // 清理资源方法
    fun cleanup() {
        // 清理可能的资源引用
        Log.d("ClimateSeatHandler", "清理资源")
    }

    fun handleMessage(msg: Message) {
        when (msg.what) {
            // 空调系统
            IdNames.HVAC_FUNC_FAN_SPEED -> handleFanSpeedMessage(msg)
            IdNames.HVAC_FUNC_AUTO_FAN_SETTING -> handleAutoFanMessage(msg)
            IdNames.HVAC_FUNC_AUTO -> handleHvacAutoMessage(msg)
            IdNames.HVAC_FUNC_BLOWING_MODE -> handleBlowingModeMessage(msg)
            IdNames.HVAC_FUNC_CIRCULATION -> handleCirculationMessage(msg)
            IdNames.HVAC_FUNC_AC -> handleACMessage(msg)

            // 座椅状态
            IdNames.SEAT_HEATING_DRIVER -> handleDriverSeatHeatingMessage(msg)
            IdNames.SEAT_HEATING_PASSENGER -> handlePassengerSeatHeatingMessage(msg)
            IdNames.SEAT_VENTILATION_DRIVER -> handleDriverSeatVentilationMessage(msg)
            IdNames.SEAT_VENTILATION_PASSENGER -> handlePassengerSeatVentilationMessage(msg)
        }
    }

    // 空调系统消息处理 - 减少日志
    private fun handleFanSpeedMessage(msg: Message) {
        currentFanSpeed = (msg.obj as? String)?.toIntOrNull() ?: -1
        // 减少日志频率：只在值变化时记录
        if (currentFanSpeed != -1) {
            Log.d("ClimateSeatHandler", "普通风量: $currentFanSpeed")
        }
        updateCombinedFanDisplay()
    }

    private fun handleAutoFanMessage(msg: Message) {
        currentAutoFanSetting = (msg.obj as? String)?.toIntOrNull() ?: -1
        // 减少日志频率：只在值变化时记录
        if (currentAutoFanSetting != -1) {
            Log.d("ClimateSeatHandler", "自动风量设置: $currentAutoFanSetting")
        }
        updateCombinedFanDisplay()
    }

    private fun handleHvacAutoMessage(msg: Message) {
        currentHvacAutoMode = (msg.obj as? String)?.toIntOrNull() ?: -1
        // 减少日志频率：只在值变化时记录
        if (currentHvacAutoMode != -1) {
            Log.d("ClimateSeatHandler", "空调自动模式: $currentHvacAutoMode")
        }
        updateCombinedFanDisplay()
        updateBlowingModeDisplay()
    }

    private fun handleBlowingModeMessage(msg: Message) {
        val newBlowingMode = (msg.obj as? String)?.toIntOrNull() ?: -1
        currentBlowingMode = newBlowingMode
        currentBlowingAutoMode = if (newBlowingMode == IdNames.BLOWING_MODE_AUTO_SWITCH) 1 else 0
        updateBlowingModeDisplay()
        updateCombinedFanDisplay()
        // 减少日志频率：只在值变化时记录
        if (newBlowingMode != -1) {
            Log.d("ClimateSeatHandler", "出风方向: $currentBlowingMode")
        }
    }

    private fun handleCirculationMessage(msg: Message) {
        currentCirculationMode = (msg.obj as? String)?.toIntOrNull() ?: -1
        // 减少日志频率：只在值变化时记录
        if (currentCirculationMode != -1) {
            Log.d("ClimateSeatHandler", "循环状态: $currentCirculationMode")
        }
        updateCirculationDisplay()
    }

    private fun handleACMessage(msg: Message) {
        currentACState = (msg.obj as? String)?.toIntOrNull() ?: -1
        // 减少日志频率：只在值变化时记录
        if (currentACState != -1) {
            Log.d("ClimateSeatHandler", "AC状态: $currentACState")
        }
        updateACDisplay()
    }

    // 座椅状态消息处理 - 减少日志
    private fun handleDriverSeatHeatingMessage(msg: Message) {
        driverSeatHeating = (msg.obj as? String)?.toIntOrNull() ?: 0
        // 减少日志：只在状态变化时记录
        if (driverSeatHeating != 0) {
            Log.d("ClimateSeatHandler", "驾驶员座椅加热: $driverSeatHeating")
        }
        updateSeatStatusDisplay()
    }

    private fun handlePassengerSeatHeatingMessage(msg: Message) {
        passengerSeatHeating = (msg.obj as? String)?.toIntOrNull() ?: 0
        // 减少日志：只在状态变化时记录
        if (passengerSeatHeating != 0) {
            Log.d("ClimateSeatHandler", "副驾驶座椅加热: $passengerSeatHeating")
        }
        updateSeatStatusDisplay()
    }

    private fun handleDriverSeatVentilationMessage(msg: Message) {
        driverSeatVentilation = (msg.obj as? String)?.toIntOrNull() ?: 0
        // 减少日志：只在状态变化时记录
        if (driverSeatVentilation != 0) {
            Log.d("ClimateSeatHandler", "驾驶员座椅通风: $driverSeatVentilation")
        }
        updateSeatStatusDisplay()
    }

    private fun handlePassengerSeatVentilationMessage(msg: Message) {
        passengerSeatVentilation = (msg.obj as? String)?.toIntOrNull() ?: 0
        // 减少日志：只在状态变化时记录
        if (passengerSeatVentilation != 0) {
            Log.d("ClimateSeatHandler", "副驾驶座椅通风: $passengerSeatVentilation")
        }
        updateSeatStatusDisplay()
    }

    // 显示更新方法
    private fun updateCombinedFanDisplay() {
        try {
            val isInAutoMode = currentHvacAutoMode == AUTO_MODE_ON || currentBlowingAutoMode == AUTO_MODE_ON
            val fanDisplayText = if (isInAutoMode) {
                when {
                    currentAutoFanSetting == 0 -> "风量: OFF"
                    currentAutoFanSetting != -1 && currentAutoFanSetting != 0 -> getAutoFanString(currentAutoFanSetting)
                    else -> "风量: AUTO"
                }
            } else {
                when {
                    currentFanSpeed == IdNames.FAN_SPEED_OFF -> "风量: OFF"
                    currentFanSpeed != -1 && currentFanSpeed != IdNames.FAN_SPEED_OFF -> getNormalFanString(currentFanSpeed)
                    else -> "风量: --"
                }
            }

            binding.tvFanSpeed.text = fanDisplayText
        } catch (e: Exception) {
            Log.e("ClimateSeatHandler", "更新风量显示失败", e)
            binding.tvFanSpeed.text = "风量: --"
        }
    }

    private fun updateBlowingModeDisplay() {
        try {
            val isInAutoMode = currentHvacAutoMode == AUTO_MODE_ON || currentBlowingAutoMode == AUTO_MODE_ON
            val blowingModeText = if (isInAutoMode) {
                "风向: AUTO"
            } else {
                getBlowingModeString(currentBlowingMode)
            }

            binding.tvBlowingMode.text = blowingModeText
        } catch (e: Exception) {
            Log.e("ClimateSeatHandler", "更新出风方向显示失败", e)
            binding.tvBlowingMode.text = "风向: --"
        }
    }

    private fun updateCirculationDisplay() {
        try {
            val circulationText = getCirculationString(currentCirculationMode)
            binding.tvCirculationStatus.text = circulationText
        } catch (e: Exception) {
            Log.e("ClimateSeatHandler", "更新循环状态显示失败", e)
            binding.tvCirculationStatus.text = "R: --"
        }
    }

    private fun updateACDisplay() {
        try {
            val acText = getACString(currentACState)
            binding.tvAcStatus.text = acText
        } catch (e: Exception) {
            Log.e("ClimateSeatHandler", "更新AC状态显示失败", e)
            binding.tvAcStatus.text = "AC: --"
        }
    }

    private fun updateSeatStatusDisplay() {
        try {
            // 驾驶员座椅状态
            val driverStatus = buildSeatStatus(driverSeatHeating, driverSeatVentilation)
            binding.tvDriverSeatStatus.text = driverStatus
            // 设置座椅状态颜色
            updateSeatStatusColor(binding.tvDriverSeatStatus, driverSeatHeating, driverSeatVentilation)

            // 副驾驶座椅状态
            val passengerStatus = buildSeatStatus(passengerSeatHeating, passengerSeatVentilation)
            binding.tvPassengerSeatStatus.text = passengerStatus
            // 设置座椅状态颜色
            updateSeatStatusColor(binding.tvPassengerSeatStatus, passengerSeatHeating, passengerSeatVentilation)

        } catch (e: Exception) {
            Log.e("ClimateSeatHandler", "更新座椅状态显示失败", e)
            binding.tvDriverSeatStatus.text = "--"
            binding.tvPassengerSeatStatus.text = "--"
            // 设置默认颜色
            binding.tvDriverSeatStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.seat_default))
            binding.tvPassengerSeatStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.seat_default))
        }
    }

    // 更新座椅状态颜色
    private fun updateSeatStatusColor(textView: android.widget.TextView, heating: Int, ventilation: Int) {
        val context = textView.context
        val heatingActive = heating != IdNames.SEAT_HEATING_OFF
        val ventilationActive = ventilation != IdNames.SEAT_VENTILATION_OFF

        when {
            heatingActive && ventilationActive -> {
                // 加热和通风同时打开 - 使用橙色
                textView.setTextColor(ContextCompat.getColor(context, R.color.seat_both))
            }
            heatingActive -> {
                // 只有加热打开 - 使用橙色
                textView.setTextColor(ContextCompat.getColor(context, R.color.seat_heating))
            }
            ventilationActive -> {
                // 只有通风打开 - 使用蓝色
                textView.setTextColor(ContextCompat.getColor(context, R.color.seat_ventilation))
            }
            else -> {
                // 默认颜色
                textView.setTextColor(ContextCompat.getColor(context, R.color.seat_default))
            }
        }
    }

    // 辅助方法
    private fun getAutoFanString(autoFanSetting: Int): String {
        return when (autoFanSetting) {
            IdNames.AUTO_FAN_SETTING_SILENT -> "风量: A2"
            IdNames.AUTO_FAN_SETTING_QUIETER -> "风量: A1"
            IdNames.AUTO_FAN_SETTING_NORMAL -> "风量: A3"
            IdNames.AUTO_FAN_SETTING_HIGHER -> "风量: A5"
            IdNames.AUTO_FAN_SETTING_HIGH -> "风量: A4"
            else -> "风量: OFF"
        }
    }

    private fun getNormalFanString(fanSpeed: Int): String {
        return when (fanSpeed) {
            IdNames.FAN_SPEED_OFF -> "风量: OFF"
            IdNames.FAN_SPEED_LEVEL_1 -> "风量: 1"
            IdNames.FAN_SPEED_LEVEL_2 -> "风量: 2"
            IdNames.FAN_SPEED_LEVEL_3 -> "风量: 3"
            IdNames.FAN_SPEED_LEVEL_4 -> "风量: 4"
            IdNames.FAN_SPEED_LEVEL_5 -> "风量: 5"
            IdNames.FAN_SPEED_LEVEL_6 -> "风量: 6"
            IdNames.FAN_SPEED_LEVEL_7 -> "风量: 7"
            IdNames.FAN_SPEED_LEVEL_8 -> "风量: 8"
            IdNames.FAN_SPEED_LEVEL_9 -> "风量: 9"
            IdNames.FAN_SPEED_LEVEL_AUTO -> "风量: AUTO"
            else -> "风量: --"
        }
    }

    private fun getBlowingModeString(blowingMode: Int): String {
        return when (blowingMode) {
            IdNames.BLOWING_MODE_FACE -> "风向: 面"
            IdNames.BLOWING_MODE_FACE_AND_LEG -> "风向: 面+脚"
            IdNames.BLOWING_MODE_LEG -> "风向: 脚"
            IdNames.BLOWING_MODE_FRONT_WINDOW -> "风向: 窗"
            IdNames.BLOWING_MODE_FACE_AND_FRONT_WINDOW -> "风向: 窗+面"
            IdNames.BLOWING_MODE_LEG_AND_FRONT_WINDOW -> "风向: 脚+窗"
            IdNames.BLOWING_MODE_ALL -> "风向: 全"
            IdNames.BLOWING_MODE_OFF -> "风向: OFF"
            else -> "风向: --"
        }
    }

    private fun getCirculationString(circulationMode: Int): String {
        return when (circulationMode) {
            IdNames.CIRCULATION_INNER -> "R: 内"
            IdNames.CIRCULATION_OUTSIDE -> "R: 外"
            IdNames.CIRCULATION_AUTO -> "R: A"
            IdNames.CIRCULATION_OFF -> "R: 外"
            else -> "R: --"
        }
    }

    private fun getACString(acState: Int): String {
        return when (acState) {
            1 -> "AC:ON"
            0 -> "AC:OFF"
            else -> "AC: --"
        }
    }

    private fun buildSeatStatus(heating: Int, ventilation: Int): String {
        val heatingActive = heating != IdNames.SEAT_HEATING_OFF
        val ventilationActive = ventilation != IdNames.SEAT_VENTILATION_OFF

        return when {
            heatingActive && ventilationActive -> "热 风"
            heatingActive -> "热"
            ventilationActive -> "风"
            else -> "--"
        }
    }

    fun initializeDisplay() {
        // 初始化空调系统显示
        updateCombinedFanDisplay()
        updateBlowingModeDisplay()
        updateCirculationDisplay()
        updateACDisplay()

        // 初始化座椅状态显示
        updateSeatStatusDisplay()
    }
}