package ru.monjaro.mconfig

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import com.ecarx.xui.adaptapi.FunctionStatus
import com.ecarx.xui.adaptapi.car.Car
import com.ecarx.xui.adaptapi.car.base.ICarFunction
import com.ecarx.xui.adaptapi.car.hvac.IHvac
import com.ecarx.xui.adaptapi.car.sensor.ISensor
import com.ecarx.xui.adaptapi.car.sensor.ISensor.ISensorListener
import com.ecarx.xui.adaptapi.car.sensor.ISensor.*
import com.ecarx.xui.adaptapi.car.sensor.ISensorEvent.*
import com.ecarx.xui.adaptapi.car.vehicle.IVehicle
import com.ecarx.xui.adaptapi.car.vehicle.IPAS
import com.ecarx.xui.adaptapi.vehicle.VehicleSeat
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean
import com.ecarx.xui.adaptapi.car.vehicle.IDriveMode
import com.ecarx.xui.adaptapi.car.hev.ITripData
import kotlin.math.PI
import kotlin.math.abs

class MConfigManager(applicationContext: Context, handler: Handler?) {

    private var threadWithRunnable: Thread? = null
    private var needToStop: AtomicBoolean = AtomicBoolean(false)
    private var needToUpdateData: AtomicBoolean = AtomicBoolean(true)
    var context = applicationContext
    private var handlerFromThread = handler

    companion object {
        var handleToThread: Handler? = null
        fun sendMessageToThread(what: Int, s: String) {
            if (handleToThread != null) {
                val msg: Message = handleToThread!!.obtainMessage()
                msg.what = what
                msg.obj = s
                handleToThread!!.sendMessage(msg)
            }
        }
    }

    init {
        threadWithRunnable = Thread(MConfigThread())
        threadWithRunnable!!.start()
    }

    fun destroy() {
        if (threadWithRunnable != null) {
            needToStop.set(true)
            threadWithRunnable!!.join(2000) // 等待2秒让线程正常结束
            threadWithRunnable = null
        }
        needToStop.set(false)
        // 清理静态引用
        handleToThread?.removeCallbacksAndMessages(null)
        handleToThread = null
    }

    inner class MConfigThread : Runnable {
        private var preferences: SharedPreferences? = null

        private var iCarFunction: ICarFunction? = null
        private var iCarSensors: ISensor? = null
        private var iSensorListener: ISensorListener? = null
        private var iFunctionValueWatcher: ICarFunction.IFunctionValueWatcher? = null

        private var ht: HandlerThread? = null

        // 档位计算相关变量
        @Volatile private var lastCarSpeed: Float = 0f
        @Volatile private var lastRpm: Float = 0f
        @Volatile private var calculatedGear: String = "P"
        @Volatile private var currentSensorGear: String = "P"
        @Volatile private var lastCalculatedGear: String = "P"
        @Volatile private var lastGearCalculationTime: Long = 0

        // 新增：档位计算历史记录，用于平滑输出
        private val gearHistory = mutableListOf<String>()
        private val maxHistorySize = 3
        @Volatile private var lastValidGear: String = "P"

        // 新增：降档检测相关
        @Volatile private var isDownshifting: Boolean = false
        @Volatile private var downshiftStartTime: Long = 0
        @Volatile private var lastSpeedBeforeDownshift: Float = 0f
        @Volatile private var lastRpmBeforeDownshift: Float = 0f
        private val downshiftDurationThreshold = 500L // 降档过程持续时间阈值（毫秒）

        // 新增：当前驾驶模式
        @Volatile private var currentDriveMode: Int = -1

        // 新增：雪地模式静止状态标志
        @Volatile private var wasSnowModeInMotion: Boolean = false

        // 车辆参数 - 吉利星越L 2021款四驱旗舰
        private val finalDriveRatio = 3.329f // 主减速比
        private val gearRatios = arrayOf(
            5.25f,    // 1档
            3.029f,   // 2档
            1.95f,    // 3档
            1.457f,   // 4档
            1.221f,   // 5档
            1.0f,     // 6档
            0.809f,   // 7档
            0.673f    // 8档
        )

        // 轮胎规格：245/45 R20 - 预计算轮胎半径
        private val tireRadius: Float by lazy {
            calculateTireRadius(245f, 0.45f, 20f)
        }

        // 新增：档位车速-转速对应范围表（用于备份验证）
        private val gearSpeedRpmRanges = mapOf(
            1 to Pair(Pair(0f, 30f), Pair(800f, 3500f)),   // 1档: 0-30km/h, 800-3500rpm
            2 to Pair(Pair(15f, 50f), Pair(1000f, 3500f)), // 2档: 15-50km/h
            3 to Pair(Pair(25f, 70f), Pair(1200f, 3500f)), // 3档: 25-70km/h
            4 to Pair(Pair(40f, 90f), Pair(1300f, 3500f)), // 4档: 40-90km/h
            5 to Pair(Pair(50f, 110f), Pair(1400f, 3500f)), // 5档: 50-110km/h
            6 to Pair(Pair(60f, 130f), Pair(1500f, 3500f)), // 6档: 60-130km/h
            7 to Pair(Pair(70f, 150f), Pair(1600f, 3500f)), // 7档: 70-150km/h
            8 to Pair(Pair(80f, 200f), Pair(1700f, 3500f))  // 8档: 80-200km/h
        )

        // 添加定时器用于实时档位计算
        private val gearCalculationHandler = Handler(Looper.getMainLooper())
        private var gearCalculationRunnable: Runnable? = null

        // 日志限制：避免过多的日志输出
        private var lastLogTime: Long = 0
        private val LOG_INTERVAL = 5000L // 5秒内相同日志只记录一次

        // 新增：档位计算状态标志
        @Volatile private var isFirstStart: Boolean = true

        // 内部类定义
        private inner class MySensorListener : ISensorListener {
            override fun onSensorEventChanged(sensorType: Int, value: Int) {
                var s: String = ""
                var what: Int = 0
                when (sensorType) {
                    SENSOR_TYPE_IGNITION_STATE -> {
                        what = IdNames.IGNITION_STATE
                        val newState = onIgnitionStateChanged(value)
                        s = newState

                        // 减少日志：只在状态变化时记录
                        Log.d("MConfigThread", "点火状态: $s")
                    }
                    SENSOR_TYPE_ENGINE_OIL_LEVEL -> {
                        what = IdNames.SENSOR_OIL_LEVEL
                        s = when (value) {
                            ENGINE_OIL_LEVEL_HIGH -> "H "
                            ENGINE_OIL_LEVEL_LOW_1 -> "L1"
                            ENGINE_OIL_LEVEL_LOW_2 -> "L2"
                            ENGINE_OIL_LEVEL_OK -> "ok"
                            ENGINE_OIL_LEVEL_UNKNOWN -> "未知"
                            else -> "未知"
                        }
                        // 减少日志：只在异常状态时记录
                        if (s != "ok") {
                            Log.d("MConfigThread", "机油状态: $s")
                        }
                    }
                    SENSOR_TYPE_SEAT_OCCUPATION_STATUS_PASSENGER -> {
                        what = IdNames.SEAT_OCCUPATION_STATUS_PASSENGER
                        s = onSeatOccupationStateChanged(value)
                    }
                    SENSOR_TYPE_GEAR -> {
                        what = IdNames.SENSOR_TYPE_GEAR
                        val newSensorGear = onGearStateChanged(value)

                        // 检测档位传感器变化
                        val gearChanged = currentSensorGear != newSensorGear
                        currentSensorGear = newSensorGear

                        if (gearChanged) {
                            // 减少日志频率
                            val now = System.currentTimeMillis()
                            if (now - lastLogTime > LOG_INTERVAL) {
                                Log.d("MConfigThread", "传感器档位变化: $currentSensorGear")
                                lastLogTime = now
                            }
                            // 重置降档检测状态
                            isDownshifting = false

                            // 立即处理档位显示（避免UI停留在N）
                            if (currentSensorGear == "D") {
                                // D档立即显示（静止时显示D1，雪地模式显示D2）
                                if (lastCarSpeed <= 0.5f) {
                                    // 车辆静止时直接显示
                                    if (currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW) {
                                        // 雪地模式：显示D2
                                        s = "D2"
                                    } else {
                                        // 非雪地模式：显示D1
                                        s = "D1"
                                    }
                                    sendMessageToUI(what, s)
                                } else {
                                    // 有车速时计算具体档位
                                    calculateAndSendGear(immediate = true)
                                }
                            } else if (currentSensorGear == "M") {
                                // M档立即显示
                                if (lastCarSpeed <= 0.5f) {
                                    // M档静止时直接显示M
                                    s = "M"
                                    sendMessageToUI(what, s)
                                } else {
                                    // 有车速时计算具体档位
                                    calculateAndSendGear(immediate = true)
                                }
                            } else {
                                // P, R, N档直接显示
                                s = currentSensorGear
                                sendMessageToUI(what, s)
                            }
                        }
                    }
                }
                if (s.isNotEmpty() && sensorType != SENSOR_TYPE_GEAR) {
                    sendMessageToUI(what, s)
                }
            }

            override fun onSensorSupportChanged(i: Int, functionStatus: FunctionStatus?) {}

            override fun onSensorValueChanged(sensorType: Int, value: Float) {
                var s: String = ""
                var what: Int = 0
                when (sensorType) {
                    SENSOR_TYPE_TEMPERATURE_AMBIENT -> {
                        what = IdNames.AMBIENT_TEMPERATURE
                        s = String.format("%.1f", value)
                    }
                    SENSOR_TYPE_TEMPERATURE_INDOOR -> {
                        what = IdNames.INT_TEMPERATURE
                        s = String.format("%.1f", value)
                    }
                    SENSOR_TYPE_FUEL_LEVEL -> {
                        what = IdNames.FUEL_LEVEL
                        val fuelPercentage = value / 1000
                        s = String.format("%.1f", fuelPercentage)
                    }
                    SENSOR_TYPE_CAR_SPEED -> {
                        what = IdNames.CAR_SPEED
                        val speedKmh = (value * 3.72).roundToInt()
                        s = speedKmh.toString()
                        val oldSpeed = lastCarSpeed
                        lastCarSpeed = speedKmh.toFloat()

                        // 检测从运动到静止的转变（用于雪地模式）
                        if (oldSpeed > 0.5f && lastCarSpeed <= 0.5f) {
                            // 车辆从运动到静止
                            if (currentSensorGear == "D" && currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW) {
                                // 雪地模式下车辆静止，强制显示D2
                                gearCalculationHandler.post {
                                    calculateAndSendGear(immediate = true, forceSnowMode = true)
                                }
                            }
                        }

                        // 降低车速变化检测阈值，提高响应速度
                        if (abs(oldSpeed - lastCarSpeed) > 0.3f) {
                            if (currentSensorGear == "D" || currentSensorGear == "M") {
                                // 检测降档过程
                                detectDownshift(oldSpeed, lastCarSpeed, lastRpm)
                                calculateAndSendGear()
                            }
                        }
                    }
                    SENSOR_TYPE_RPM -> {
                        what = IdNames.SENSOR_RPM
                        val rpmValue = value.toInt()
                        s = rpmValue.toString()
                        val oldRpm = lastRpm
                        lastRpm = value

                        // 降低转速变化检测阈值，提高响应速度
                        if (abs(oldRpm - lastRpm) > 20.0f) {
                            if (currentSensorGear == "D" || currentSensorGear == "M") {
                                // 检测降档过程
                                detectDownshift(lastCarSpeed, lastCarSpeed, oldRpm, lastRpm)
                                calculateAndSendGear()
                            }
                        }
                    }
                    SENSOR_TYPE_ODOMETER -> {
                        what = IdNames.ODOMETER
                        s = value.toString()
                        preferences?.edit()?.putFloat("odometerValue", value)?.apply()
                        // 减少日志：只在里程有显著变化时记录
                        if (value.toInt() % 10 == 0) {
                            Log.d("MConfigThread", "里程: $value km")
                        }
                    }
                    // ============== 胎压胎温传感器 ==============
                    IdNames.TIRE_PRESSURE_FRONT_LEFT -> {
                        what = IdNames.TIRE_PRESSURE_FL
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    IdNames.TIRE_PRESSURE_FRONT_RIGHT -> {
                        what = IdNames.TIRE_PRESSURE_FR
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    IdNames.TIRE_PRESSURE_REAR_LEFT -> {
                        what = IdNames.TIRE_PRESSURE_RL
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    IdNames.TIRE_PRESSURE_REAR_RIGHT -> {
                        what = IdNames.TIRE_PRESSURE_RR
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    IdNames.TIRE_TEMPERATURE_FRONT_LEFT -> {
                        what = IdNames.TIRE_TEMP_FL
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    IdNames.TIRE_TEMPERATURE_FRONT_RIGHT -> {
                        what = IdNames.TIRE_TEMP_FR
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    IdNames.TIRE_TEMPERATURE_REAR_LEFT -> {
                        what = IdNames.TIRE_TEMP_RL
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    IdNames.TIRE_TEMPERATURE_REAR_RIGHT -> {
                        what = IdNames.TIRE_TEMP_RR
                        s = if (value == Float.MIN_VALUE) "--" else String.format("%.0f", value)
                    }
                    // ============== 胎压胎温传感器结束 ==============
                    // ============== 油耗传感器 ==============
                    IdNames.SENSOR_INSTANT_FUEL_CONSUMPTION -> {
                        what = IdNames.INSTANT_FUEL_CONSUMPTION
                        s = if (value == Float.MIN_VALUE) "--" else formatInstantFuel(value)
                    }
                    // ============== 单次点火平均油耗传感器 ==============
                    IdNames.SENSOR_AVG_FUEL_CONSUMPTION_VALUE -> {
                        what = IdNames.SENSOR_AVG_FUEL_CONSUMPTION
                        s = if (value == Float.MIN_VALUE) "--" else formatInstantFuel(value)
                    }
                    // ============== 平均油耗传感器结束 ==============

                    else -> return
                }
                sendMessageToUI(what, s)
            }
        }

        private inner class MyFunctionValueWatcher : ICarFunction.IFunctionValueWatcher {
            override fun onCustomizeFunctionValueChanged(i: Int, i2: Int, f: Float) {}

            override fun onFunctionChanged(i: Int) {}

            override fun onFunctionValueChanged(functionId: Int, zone: Int, value: Int) {
                when (functionId) {
                    IDriveMode.DM_FUNC_DRIVE_MODE_SELECT -> {
                        val oldMode = currentDriveMode
                        currentDriveMode = value  // 保存当前驾驶模式
                        preferences?.edit()?.putInt("driveModeCfg", value)?.apply()

                        // 减少日志：只在驾驶模式变化时记录
                        Log.d("MConfigThread", "驾驶模式: $value")

                        // 新增：驾驶模式切换时立即重新计算档位
                        if (oldMode != value) {
                            // 记录是否为雪地模式切换
                            val isSnowMode = value == IdNames.DRIVE_MODE_SELECTION_SNOW
                            val wasSnowMode = oldMode == IdNames.DRIVE_MODE_SELECTION_SNOW

                            // 立即触发档位重新计算
                            gearCalculationHandler.post {
                                // 强制重新计算档位，特别是雪地模式
                                calculateAndSendGear(immediate = true, forceSnowMode = isSnowMode)
                            }

                            // 如果切换到雪地模式且车辆静止，标记需要特殊处理
                            if (isSnowMode && lastCarSpeed <= 0.5f && currentSensorGear == "D") {
                                wasSnowModeInMotion = false
                            }
                        }
                    }

                    // ============== 转向灯功能 ==============
                    IdNames.BCM_FUNC_LIGHT_LEFT_TURN_SIGNAL -> {
                        sendMessageToUI(IdNames.LIGHT_LEFT_TURN, value.toString())
                    }

                    IdNames.BCM_FUNC_LIGHT_RIGHT_TURN_SIGNAL -> {
                        sendMessageToUI(IdNames.LIGHT_RIGHT_TURN, value.toString())
                    }

                    IdNames.BCM_FUNC_LIGHT_HAZARD_FLASHERS -> {
                        sendMessageToUI(IdNames.LIGHT_HAZARD_FLASHERS, value.toString())
                    }
                    // ============== 转向灯结束 ==============

                    IHvac.HVAC_FUNC_FAN_SPEED -> {
                        sendMessageToUI(IdNames.HVAC_FUNC_FAN_SPEED, value.toString())
                    }

                    IHvac.HVAC_FUNC_AUTO_FAN_SETTING -> {
                        sendMessageToUI(IdNames.HVAC_FUNC_AUTO_FAN_SETTING, value.toString())
                    }

                    IHvac.HVAC_FUNC_AUTO -> {
                        sendMessageToUI(IdNames.HVAC_FUNC_AUTO, value.toString())
                    }

                    IHvac.HVAC_FUNC_BLOWING_MODE -> {
                        sendMessageToUI(IdNames.HVAC_FUNC_BLOWING_MODE, value.toString())
                    }

                    IdNames.HVAC_FUNC_CIRCULATION -> {
                        sendMessageToUI(IdNames.HVAC_FUNC_CIRCULATION, value.toString())
                    }

                    IdNames.HVAC_FUNC_AC -> {
                        sendMessageToUI(IdNames.HVAC_FUNC_AC, value.toString())
                    }

                    IHvac.HVAC_FUNC_SEAT_HEATING -> {
                        when (zone) {
                            VehicleSeat.SEAT_ROW_1_LEFT -> {
                                sendMessageToUI(IdNames.SEAT_HEATING_DRIVER, value.toString())
                            }
                            VehicleSeat.SEAT_ROW_1_RIGHT -> {
                                sendMessageToUI(IdNames.SEAT_HEATING_PASSENGER, value.toString())
                            }
                        }
                    }

                    IHvac.HVAC_FUNC_SEAT_VENTILATION -> {
                        when (zone) {
                            1 -> {
                                sendMessageToUI(IdNames.SEAT_VENTILATION_DRIVER, value.toString())
                            }
                            4 -> {
                                sendMessageToUI(IdNames.SEAT_VENTILATION_PASSENGER, value.toString())
                            }
                        }
                    }
                }
            }

            override fun onSupportedFunctionStatusChanged(i: Int, i2: Int, functionStatus: FunctionStatus?) {}

            override fun onSupportedFunctionValueChanged(i: Int, iArr: IntArray?) {}
        }

        // 辅助方法
        @Synchronized
        fun sendMessageToUI(what: Int, s: String) {
            if (handlerFromThread != null) {
                val msg: Message = handlerFromThread!!.obtainMessage()
                msg.what = what
                msg.obj = s
                handlerFromThread!!.sendMessage(msg)
            }
        }

        private fun initICarFunction(): Int {
            var ret = -1
            try {
                iCarFunction = Car.create(context).iCarFunction
                ret = 0
            } catch (_: Exception) {
            } catch (_: Error) {}
            return ret
        }

        private fun initICarSensors(): Int {
            var ret = -1
            try {
                iCarSensors = Car.create(context).sensorManager
                ret = 0
            } catch (_: Exception) {
            } catch (_: Error) {}
            return ret
        }

        private fun isICarFunctionAvailable(i: Int): Boolean {
            return if (this.iCarFunction != null) {
                try {
                    val isFunctionSupported: FunctionStatus = this.iCarFunction!!.isFunctionSupported(i)
                    isFunctionSupported == FunctionStatus.active
                } catch (_: Exception) {
                    false
                }
            } else false
        }

        private fun isICarFunctionAvailable(i: Int, i1: Int): Boolean {
            return if (this.iCarFunction != null) {
                try {
                    val isFunctionSupported: FunctionStatus = this.iCarFunction!!.isFunctionSupported(i, i1)
                    isFunctionSupported == FunctionStatus.active
                } catch (_: Exception) {
                    false
                }
            } else false
        }

        private fun isICarSensorAvailable(i: Int): Boolean {
            return if (this.iCarSensors != null) {
                try {
                    val isSensorSupported: FunctionStatus = iCarSensors!!.isSensorSupported(i)
                    isSensorSupported != FunctionStatus.notavailable && isSensorSupported != FunctionStatus.error
                } catch (_: Exception) {
                    false
                }
            } else false
        }

        private fun getSensorEvent(v: Int): Int {
            try {
                if (this.iCarSensors != null) {
                    return this.iCarSensors!!.getSensorEvent(v)
                }
            } catch (_: Exception) {}
            return -1
        }

        private fun getSensorValue(v: Int): Float {
            try {
                if (this.iCarSensors != null) {
                    return this.iCarSensors!!.getSensorLatestValue(v)
                }
            } catch (_: Exception) {}
            return 0.toFloat()
        }

        private fun getFunctionValue(v: Int): Int {
            try {
                if (this.iCarFunction != null) {
                    return this.iCarFunction!!.getFunctionValue(v)
                }
            } catch (_: Exception) {}
            return -1
        }

        private fun getFunctionValue(v: Int, v1: Int): Int {
            try {
                if (this.iCarFunction != null) {
                    return this.iCarFunction!!.getFunctionValue(v, v1)
                }
            } catch (_: Exception) {}
            return -1
        }

        // 状态转换方法
        private fun onIgnitionStateChanged(i2: Int): String {
            return when (i2) {
                IGNITION_STATE_ACC -> "ACC"
                IGNITION_STATE_DRIVING -> "DRIVE"
                IGNITION_STATE_LOCK -> "LOCK"
                IGNITION_STATE_OFF -> "OFF"
                IGNITION_STATE_ON -> "ON"
                IGNITION_STATE_START -> "START"
                else -> "未知"
            }
        }

        private fun onSeatOccupationStateChanged(i2: Int): String {
            return when (i2) {
                SEAT_OCCUPATION_STATUS_FAULT -> "故障"
                SEAT_OCCUPATION_STATUS_NONE -> "无人"
                SEAT_OCCUPATION_STATUS_OCCUPIED -> "有人"
                else -> "未知"
            }
        }

        private fun onGearStateChanged(i2: Int): String {
            return when (i2) {
                GEAR_DRIVE -> "D"
                GEAR_EIGHTH -> "8"
                GEAR_FIFTH -> "5"
                GEAR_FIRST -> "1"
                GEAR_FOURTH -> "4"
                GEAR_NEUTRAL -> "N"
                GEAR_NINTH -> "9"
                GEAR_PARK -> "P"
                GEAR_REVERSE -> "R"
                GEAR_SECOND -> "2"
                GEAR_SEVENTH -> "7"
                GEAR_SIXTH -> "6"
                GEAR_TENTH -> "10"
                GEAR_THIRD -> "3"
                GEAR_UNKNOWN -> "M"
                else -> "未知"
            }
        }

        // 新增方法：计算轮胎半径（米）
        private fun calculateTireRadius(widthMm: Float, aspectRatio: Float, rimDiameterInch: Float): Float {
            val sidewallHeight = widthMm * aspectRatio
            val tireDiameterMm = (rimDiameterInch * 25.4f) + (2 * sidewallHeight)
            return (tireDiameterMm / 1000f) / 2f
        }

        // 新增方法：检测降档过程
        private fun detectDownshift(oldSpeed: Float, newSpeed: Float, oldRpm: Float = 0f, newRpm: Float = 0f) {
            val now = System.currentTimeMillis()

            // 降档特征：车速明显下降，同时转速上升或保持
            if (newSpeed < oldSpeed - 3 && (newRpm >= oldRpm - 100 || oldRpm == 0f)) {
                if (!isDownshifting) {
                    // 开始降档
                    isDownshifting = true
                    downshiftStartTime = now
                    lastSpeedBeforeDownshift = oldSpeed
                    lastRpmBeforeDownshift = if (oldRpm > 0) oldRpm else lastRpm
                }
            } else if (newSpeed > oldSpeed + 1 || newRpm < oldRpm - 200) {
                // 车速上升或转速明显下降，结束降档
                isDownshifting = false
                downshiftStartTime = 0
            }

            // 检查降档是否超时
            if (isDownshifting && now - downshiftStartTime > downshiftDurationThreshold) {
                isDownshifting = false
                downshiftStartTime = 0
            }
        }

        // 修改方法：优化档位计算方法，添加forceSnowMode参数
        private fun calculateGearByRatio(speedKmh: Float, rpm: Float, forceSnowMode: Boolean = false): String {
            if (speedKmh <= 0.5f || rpm <= 0.1f) {
                // 车辆静止时的档位处理
                if (currentSensorGear == "D") {
                    // 雪地模式特殊处理：静止时显示2档
                    if (currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) {
                        return "D2"
                    }
                    // 非雪地模式：静止时显示D1
                    return "D1"
                } else if (currentSensorGear == "M") {
                    // M档静止时显示M
                    return "M"
                }
                return currentSensorGear
            }

            // 雪地模式：低速起步时也显示2档
            if ((currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) && currentSensorGear == "D") {
                // 静止或低速时显示2档
                if (speedKmh < 10.0f && rpm < 1500f) {
                    // 确保在低速低转速时显示D2
                    return "D2"
                }
                // 标记雪地模式已经进入运动状态
                wasSnowModeInMotion = true
            }

            val speedMps = speedKmh / 3.6f
            val rpmRadPerSec = rpm * (2f * PI.toFloat() / 60f)
            val calculatedRatio = (rpmRadPerSec * tireRadius) / (speedMps * finalDriveRatio)

            var minDiff = Float.MAX_VALUE
            var bestGear = 0

            for (i in gearRatios.indices) {
                val diff = abs(calculatedRatio - gearRatios[i])
                if (diff < minDiff) {
                    minDiff = diff
                    bestGear = i + 1
                }
            }

            // 雪地模式：限制最低档位为2档
            if ((currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) && bestGear < 2) {
                bestGear = 2
            }

            // 动态误差阈值：低速时放宽，高速时收紧
            val errorThreshold = if (speedKmh < 20) 1.0f else if (speedKmh < 60) 0.6f else 0.4f

            if (minDiff < errorThreshold) {
                return "${currentSensorGear}$bestGear"
            }

            return currentSensorGear
        }

        // 修改方法：添加雪地模式判断
        private fun calculateGearBySpeedRpm(speedKmh: Float, rpm: Float, forceSnowMode: Boolean = false): String {
            // 车辆静止时的特殊处理
            if (speedKmh <= 0.5f || rpm <= 0.1f) {
                if (currentSensorGear == "D") {
                    // 雪地模式特殊处理：静止时显示2档
                    if (currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) {
                        return "D2"
                    }
                    // 非雪地模式：静止时显示D1
                    return "D1"
                } else if (currentSensorGear == "M") {
                    // M档静止时显示M
                    return "M"
                }
                return currentSensorGear
            }

            // 雪地模式特殊处理
            if (currentSensorGear == "D") {
                if (currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) {
                    // 静止或低速时显示2档
                    if (speedKmh < 10.0f && rpm < 1500f) {
                        return "D2"
                    }
                    // 标记雪地模式已经进入运动状态
                    wasSnowModeInMotion = true
                }
            }

            // 使用车速-转速范围表进行判断
            for ((gear, ranges) in gearSpeedRpmRanges) {
                val speedRange = ranges.first
                val rpmRange = ranges.second

                if (speedKmh >= speedRange.first && speedKmh <= speedRange.second &&
                    rpm >= rpmRange.first && rpm <= rpmRange.second) {
                    // 雪地模式：限制最低档位为2档
                    val finalGear = if ((currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) && gear < 2) 2 else gear
                    return "${currentSensorGear}$finalGear"
                }
            }

            return currentSensorGear
        }

        // 新增方法：档位输出平滑处理
        private fun smoothGearOutput(newGear: String): String {
            // 添加新档位到历史记录
            gearHistory.add(newGear)

            // 保持历史记录大小
            if (gearHistory.size > maxHistorySize) {
                gearHistory.removeAt(0)
            }

            // 如果历史记录不足，直接返回
            if (gearHistory.size < 2) {
                lastValidGear = newGear
                return newGear
            }

            // 检查历史记录中是否一致
            val lastThree = gearHistory.takeLast(3)
            val mode = lastThree.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

            // 如果最近3次中有2次相同，则使用该档位
            if (mode != null && lastThree.count { it == mode } >= 2) {
                lastValidGear = mode
                return mode
            }

            // 否则保持上次有效档位
            return lastValidGear
        }

        // 优化的档位计算和发送方法，添加forceSnowMode参数
        private fun calculateAndSendGear(immediate: Boolean = false, forceSnowMode: Boolean = false) {
            val now = System.currentTimeMillis()

            // 非立即模式下的频率控制 - 降低间隔到50ms以提高刷新率
            if (!immediate && now - lastGearCalculationTime < 50) {
                return
            }

            lastGearCalculationTime = now

            // 处理静止状态
            if (lastCarSpeed <= 0.5f && (currentSensorGear == "D" || currentSensorGear == "M")) {
                if (currentSensorGear == "D") {
                    // D档静止时的显示
                    val finalGear = if (currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) {
                        // 雪地模式：静止时显示D2
                        "D2"
                    } else {
                        // 非雪地模式：静止时显示D1
                        "D1"
                    }

                    if (finalGear != lastCalculatedGear) {
                        lastCalculatedGear = finalGear
                        calculatedGear = finalGear
                        sendMessageToUI(IdNames.SENSOR_TYPE_GEAR, finalGear)
                        return
                    }
                } else if (currentSensorGear == "M") {
                    // M档静止时显示M
                    val finalGear = "M"
                    if (finalGear != lastCalculatedGear) {
                        lastCalculatedGear = finalGear
                        calculatedGear = finalGear
                        sendMessageToUI(IdNames.SENSOR_TYPE_GEAR, finalGear)
                        return
                    }
                }
            }

            // 使用主计算方法
            val mainResult = calculateGearByRatio(lastCarSpeed, lastRpm, forceSnowMode)

            // 使用备份计算方法
            val backupResult = calculateGearBySpeedRpm(lastCarSpeed, lastRpm, forceSnowMode)

            // 决策逻辑
            var finalGear = mainResult

            // 如果主方法和备份方法结果不同
            if (mainResult != backupResult) {
                // 如果主方法只能识别到D/M，而备份方法可以识别具体档位
                if (backupResult != currentSensorGear && mainResult == currentSensorGear) {
                    finalGear = backupResult
                }
            }

            // 特别处理雪地模式下的静止状态
            if (currentSensorGear == "D" && lastCarSpeed <= 0.5f) {
                if (currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW || forceSnowMode) {
                    // 雪地模式下，车辆静止时强制显示D2
                    if (!finalGear.endsWith("2")) {
                        finalGear = "D2"
                    }
                } else {
                    // 非雪地模式下，车辆静止时强制显示D1
                    if (finalGear == "N" || finalGear == "P" || finalGear == "R") {
                        finalGear = "D1"
                    }
                }
            }

            // 平滑处理
            finalGear = smoothGearOutput(finalGear)

            // 只有当档位真正变化时才发送消息
            if (finalGear != lastCalculatedGear) {
                lastCalculatedGear = finalGear
                calculatedGear = finalGear
                sendMessageToUI(IdNames.SENSOR_TYPE_GEAR, finalGear)

                // 减少日志：只在档位变化时记录，且限制频率
                val currentTime = System.currentTimeMillis()
                if (finalGear.contains(Regex("[0-9]")) && currentTime - lastLogTime > LOG_INTERVAL) {
                    Log.d("MConfigThread", "档位: $finalGear, 车速: ${String.format("%.1f", lastCarSpeed)}km/h, 转速: ${String.format("%.0f", lastRpm)}rpm")
                    lastLogTime = currentTime
                }
            }
        }

        // 新增方法：格式化瞬时油耗，0.0时显示0
        private fun formatInstantFuel(value: Float): String {
            return if (value == 0.0f) {
                "0"
            } else {
                String.format("%.1f", value)
            }
        }

        // 启动实时档位计算定时器（提高频率到50ms）
        private fun startRealTimeGearCalculation() {
            stopRealTimeGearCalculation()

            gearCalculationRunnable = object : Runnable {
                override fun run() {
                    try {
                        // 只有在D档或M档时才实时计算
                        if (currentSensorGear == "D" || currentSensorGear == "M") {
                            // 特殊处理：如果是第一次启动，立即计算一次
                            if (isFirstStart) {
                                isFirstStart = false
                                calculateAndSendGear(immediate = true)
                            } else {
                                // 正常计算
                                calculateAndSendGear()
                            }
                        }

                        // 提高计算频率到50ms（20次/秒）
                        gearCalculationHandler.postDelayed(this, 50)
                    } catch (e: Exception) {
                        Log.e("MConfigThread", "实时档位计算异常", e)
                    }

                }
            }

            gearCalculationHandler.post(gearCalculationRunnable!!)
        }

        // 停止实时档位计算
        private fun stopRealTimeGearCalculation() {
            gearCalculationRunnable?.let {
                gearCalculationHandler.removeCallbacks(it)
                gearCalculationRunnable = null
            }
        }

        // 主动查询所有DataFragment需要的数据
        private fun queryAndSendAllData() {
            try {
                // 查询传感器数据
                if (isICarSensorAvailable(SENSOR_TYPE_IGNITION_STATE)) {
                    val ignition = getSensorEvent(SENSOR_TYPE_IGNITION_STATE)
                    if (ignition != -1) {
                        sendMessageToUI(IdNames.IGNITION_STATE, onIgnitionStateChanged(ignition))
                    }
                }

                if (isICarSensorAvailable(SENSOR_TYPE_ENGINE_OIL_LEVEL)) {
                    val oil = getSensorEvent(SENSOR_TYPE_ENGINE_OIL_LEVEL)
                    if (oil != -1) {
                        val oilStr = when (oil) {
                            ENGINE_OIL_LEVEL_HIGH -> "H "
                            ENGINE_OIL_LEVEL_LOW_1 -> "L1"
                            ENGINE_OIL_LEVEL_LOW_2 -> "L2"
                            ENGINE_OIL_LEVEL_OK -> "ok"
                            ENGINE_OIL_LEVEL_UNKNOWN -> "未知"
                            else -> "未知"
                        }
                        sendMessageToUI(IdNames.SENSOR_OIL_LEVEL, oilStr)
                    }
                }

                if (isICarSensorAvailable(SENSOR_TYPE_SEAT_OCCUPATION_STATUS_PASSENGER)) {
                    val seat = getSensorEvent(SENSOR_TYPE_SEAT_OCCUPATION_STATUS_PASSENGER)
                    if (seat != -1) {
                        sendMessageToUI(IdNames.SEAT_OCCUPATION_STATUS_PASSENGER, onSeatOccupationStateChanged(seat))
                    }
                }

                if (isICarSensorAvailable(SENSOR_TYPE_GEAR)) {
                    val gear = getSensorEvent(SENSOR_TYPE_GEAR)
                    if (gear != -1) {
                        currentSensorGear = onGearStateChanged(gear)

                        // 处理静止状态的D档
                        if (currentSensorGear == "D") {
                            if (lastCarSpeed <= 0.5f) {
                                // 车辆静止
                                if (currentDriveMode == IdNames.DRIVE_MODE_SELECTION_SNOW) {
                                    // 雪地模式：显示D2
                                    sendMessageToUI(IdNames.SENSOR_TYPE_GEAR, "D2")
                                } else {
                                    // 非雪地模式：显示D1
                                    sendMessageToUI(IdNames.SENSOR_TYPE_GEAR, "D1")
                                }
                            } else {
                                // 有车速，尝试计算具体档位
                                calculateAndSendGear(immediate = true)
                            }
                        } else if (currentSensorGear == "M") {
                            if (lastCarSpeed <= 0.5f) {
                                // M档静止，直接显示M
                                sendMessageToUI(IdNames.SENSOR_TYPE_GEAR, "M")
                            } else {
                                // M档有车速，尝试计算具体档位
                                calculateAndSendGear(immediate = true)
                            }
                        } else {
                            sendMessageToUI(IdNames.SENSOR_TYPE_GEAR, currentSensorGear)
                        }
                    }
                }

                // 查询传感器数值
                if (isICarSensorAvailable(SENSOR_TYPE_TEMPERATURE_AMBIENT)) {
                    val ambientTemp = getSensorValue(SENSOR_TYPE_TEMPERATURE_AMBIENT)
                    sendMessageToUI(IdNames.AMBIENT_TEMPERATURE, String.format("%.1f", ambientTemp))
                }

                if (isICarSensorAvailable(SENSOR_TYPE_TEMPERATURE_INDOOR)) {
                    val indoorTemp = getSensorValue(SENSOR_TYPE_TEMPERATURE_INDOOR)
                    sendMessageToUI(IdNames.INT_TEMPERATURE, String.format("%.1f", indoorTemp))
                }

                if (isICarSensorAvailable(SENSOR_TYPE_FUEL_LEVEL)) {
                    val fuel = getSensorValue(SENSOR_TYPE_FUEL_LEVEL)
                    val fuelPercentage = fuel / 1000
                    sendMessageToUI(IdNames.FUEL_LEVEL, String.format("%.1f", fuelPercentage))
                }

                if (isICarSensorAvailable(SENSOR_TYPE_CAR_SPEED)) {
                    val speed = getSensorValue(SENSOR_TYPE_CAR_SPEED)
                    val speedKmh = (speed * 3.72).roundToInt()
                    lastCarSpeed = speedKmh.toFloat()
                    sendMessageToUI(IdNames.CAR_SPEED, speedKmh.toString())
                }

                if (isICarSensorAvailable(SENSOR_TYPE_RPM)) {
                    val rpm = getSensorValue(SENSOR_TYPE_RPM)
                    lastRpm = rpm
                    sendMessageToUI(IdNames.SENSOR_RPM, rpm.toInt().toString())
                }

                if (isICarSensorAvailable(SENSOR_TYPE_ODOMETER)) {
                    val odometer = getSensorValue(SENSOR_TYPE_ODOMETER)
                    sendMessageToUI(IdNames.ODOMETER, odometer.toString())
                }

                // ============== 胎压胎温数据 ==============
                if (isICarSensorAvailable(IdNames.TIRE_PRESSURE_FRONT_LEFT)) {
                    val pressureFL = getSensorValue(IdNames.TIRE_PRESSURE_FRONT_LEFT)
                    sendMessageToUI(IdNames.TIRE_PRESSURE_FL,
                        if (pressureFL == Float.MIN_VALUE) "--" else String.format("%.0f", pressureFL))
                }

                if (isICarSensorAvailable(IdNames.TIRE_PRESSURE_FRONT_RIGHT)) {
                    val pressureFR = getSensorValue(IdNames.TIRE_PRESSURE_FRONT_RIGHT)
                    sendMessageToUI(IdNames.TIRE_PRESSURE_FR,
                        if (pressureFR == Float.MIN_VALUE) "--" else String.format("%.0f", pressureFR))
                }

                if (isICarSensorAvailable(IdNames.TIRE_PRESSURE_REAR_LEFT)) {
                    val pressureRL = getSensorValue(IdNames.TIRE_PRESSURE_REAR_LEFT)
                    sendMessageToUI(IdNames.TIRE_PRESSURE_RL,
                        if (pressureRL == Float.MIN_VALUE) "--" else String.format("%.0f", pressureRL))
                }

                if (isICarSensorAvailable(IdNames.TIRE_PRESSURE_REAR_RIGHT)) {
                    val pressureRR = getSensorValue(IdNames.TIRE_PRESSURE_REAR_RIGHT)
                    sendMessageToUI(IdNames.TIRE_PRESSURE_RR,
                        if (pressureRR == Float.MIN_VALUE) "--" else String.format("%.0f", pressureRR))
                }

                if (isICarSensorAvailable(IdNames.TIRE_TEMPERATURE_FRONT_LEFT)) {
                    val tempFL = getSensorValue(IdNames.TIRE_TEMPERATURE_FRONT_LEFT)
                    sendMessageToUI(IdNames.TIRE_TEMP_FL,
                        if (tempFL == Float.MIN_VALUE) "--" else String.format("%.0f", tempFL))
                }

                if (isICarSensorAvailable(IdNames.TIRE_TEMPERATURE_FRONT_RIGHT)) {
                    val tempFR = getSensorValue(IdNames.TIRE_TEMPERATURE_FRONT_RIGHT)
                    sendMessageToUI(IdNames.TIRE_TEMP_FR,
                        if (tempFR == Float.MIN_VALUE) "--" else String.format("%.0f", tempFR))
                }

                if (isICarSensorAvailable(IdNames.TIRE_TEMPERATURE_REAR_LEFT)) {
                    val tempRL = getSensorValue(IdNames.TIRE_TEMPERATURE_REAR_LEFT)
                    sendMessageToUI(IdNames.TIRE_TEMP_RL,
                        if (tempRL == Float.MIN_VALUE) "--" else String.format("%.0f", tempRL))
                }

                if (isICarSensorAvailable(IdNames.TIRE_TEMPERATURE_REAR_RIGHT)) {
                    val tempRR = getSensorValue(IdNames.TIRE_TEMPERATURE_REAR_RIGHT)
                    sendMessageToUI(IdNames.TIRE_TEMP_RR,
                        if (tempRR == Float.MIN_VALUE) "--" else String.format("%.0f", tempRR))
                }
                // ============== 胎压胎温查询结束 ==============

                // ============== 油耗数据 ==============
                if (isICarSensorAvailable(IdNames.SENSOR_INSTANT_FUEL_CONSUMPTION)) {
                    val instantFuel = getSensorValue(IdNames.SENSOR_INSTANT_FUEL_CONSUMPTION)
                    sendMessageToUI(IdNames.INSTANT_FUEL_CONSUMPTION,
                        if (instantFuel == Float.MIN_VALUE) "--" else formatInstantFuel(instantFuel))
                }
                // ============== 单次点火平均油耗数据 ==============
                if (isICarSensorAvailable(IdNames.SENSOR_AVG_FUEL_CONSUMPTION_VALUE)) {
                    // 直接查询油耗数据，不进行熄火检查
                    val avgFuel = getSensorValue(IdNames.SENSOR_AVG_FUEL_CONSUMPTION_VALUE)
                    sendMessageToUI(IdNames.SENSOR_AVG_FUEL_CONSUMPTION,
                        if (avgFuel == Float.MIN_VALUE) "--" else formatInstantFuel(avgFuel))
                }
                // ============== 平均油耗查询结束 ==============

                // 查询车辆功能状态
                // 查询驾驶模式
                if (isICarFunctionAvailable(IDriveMode.DM_FUNC_DRIVE_MODE_SELECT)) {
                    val driveMode = getFunctionValue(IDriveMode.DM_FUNC_DRIVE_MODE_SELECT)
                    currentDriveMode = driveMode
                }

                // 查询HVAC状态
                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_FAN_SPEED)) {
                    val fanSpeed = getFunctionValue(IHvac.HVAC_FUNC_FAN_SPEED)
                    sendMessageToUI(IdNames.HVAC_FUNC_FAN_SPEED, fanSpeed.toString())
                }

                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_AUTO_FAN_SETTING)) {
                    val autoFan = getFunctionValue(IHvac.HVAC_FUNC_AUTO_FAN_SETTING)
                    sendMessageToUI(IdNames.HVAC_FUNC_AUTO_FAN_SETTING, autoFan.toString())
                }

                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_AUTO)) {
                    val autoMode = getFunctionValue(IHvac.HVAC_FUNC_AUTO)
                    sendMessageToUI(IdNames.HVAC_FUNC_AUTO, autoMode.toString())
                }

                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_BLOWING_MODE)) {
                    val blowingMode = getFunctionValue(IHvac.HVAC_FUNC_BLOWING_MODE)
                    sendMessageToUI(IdNames.HVAC_FUNC_BLOWING_MODE, blowingMode.toString())
                }

                if (isICarFunctionAvailable(IdNames.HVAC_FUNC_CIRCULATION)) {
                    val circulation = getFunctionValue(IdNames.HVAC_FUNC_CIRCULATION)
                    sendMessageToUI(IdNames.HVAC_FUNC_CIRCULATION, circulation.toString())
                }

                if (isICarFunctionAvailable(IdNames.HVAC_FUNC_AC)) {
                    val ac = getFunctionValue(IdNames.HVAC_FUNC_AC)
                    sendMessageToUI(IdNames.HVAC_FUNC_AC, ac.toString())
                }

                // 查询座椅状态
                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_SEAT_HEATING, VehicleSeat.SEAT_ROW_1_LEFT)) {
                    val driverHeating = getFunctionValue(IHvac.HVAC_FUNC_SEAT_HEATING, VehicleSeat.SEAT_ROW_1_LEFT)
                    sendMessageToUI(IdNames.SEAT_HEATING_DRIVER, driverHeating.toString())
                }

                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_SEAT_HEATING, VehicleSeat.SEAT_ROW_1_RIGHT)) {
                    val passengerHeating = getFunctionValue(IHvac.HVAC_FUNC_SEAT_HEATING, VehicleSeat.SEAT_ROW_1_RIGHT)
                    sendMessageToUI(IdNames.SEAT_HEATING_PASSENGER, passengerHeating.toString())
                }

                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_SEAT_VENTILATION, 1)) {
                    val driverVentilation = getFunctionValue(IHvac.HVAC_FUNC_SEAT_VENTILATION, 1)
                    sendMessageToUI(IdNames.SEAT_VENTILATION_DRIVER, driverVentilation.toString())
                }

                if (isICarFunctionAvailable(IHvac.HVAC_FUNC_SEAT_VENTILATION, 4)) {
                    val passengerVentilation = getFunctionValue(IHvac.HVAC_FUNC_SEAT_VENTILATION, 4)
                    sendMessageToUI(IdNames.SEAT_VENTILATION_PASSENGER, passengerVentilation.toString())
                }

                // ============== 转向灯状态 ==============
                if (isICarFunctionAvailable(IdNames.BCM_FUNC_LIGHT_LEFT_TURN_SIGNAL)) {
                    val leftTurn = getFunctionValue(IdNames.BCM_FUNC_LIGHT_LEFT_TURN_SIGNAL)
                    sendMessageToUI(IdNames.LIGHT_LEFT_TURN, leftTurn.toString())
                }

                if (isICarSensorAvailable(IdNames.BCM_FUNC_LIGHT_RIGHT_TURN_SIGNAL)) {
                    val rightTurn = getSensorValue(IdNames.BCM_FUNC_LIGHT_RIGHT_TURN_SIGNAL)
                    sendMessageToUI(IdNames.LIGHT_RIGHT_TURN, rightTurn.toString())
                }

                if (isICarSensorAvailable(IdNames.BCM_FUNC_LIGHT_HAZARD_FLASHERS)) {
                    val hazard = getSensorValue(IdNames.BCM_FUNC_LIGHT_HAZARD_FLASHERS)
                    sendMessageToUI(IdNames.LIGHT_HAZARD_FLASHERS, hazard.toString())
                }
                // ============== 转向灯查询结束 ==============

            } catch (e: Exception) {
                Log.e("MConfigThread", "查询数据失败", e)
            }
        }

        // 主要的 run 方法
        override fun run() {
            preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (preferences == null) {
                sendMessageToUI(IdNames.ERROR, "error_quit")
                return
            }

            val sUpTime = SystemClock.uptimeMillis()
            var tm = if (sUpTime < 17000) (17000 - sUpTime) else 100
            if (tm < 100) {
                tm = 100
            }
            Thread.sleep(tm)

            if (ht == null) {
                ht = HandlerThread("MConfigHandlerThread")
                ht!!.start()
            }

            handleToThread = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    super.handleMessage(msg)
                    when (msg.what) {
                        IdNames.UPDATE_UI -> needToUpdateData.set(true)
                    }
                }
            }

            try {
                var carInitialized: Boolean = false
                for (i in 1 until 4) {
                    if (initICarFunction() == 0 && iCarFunction != null &&
                        initICarSensors() == 0 && iCarSensors != null) {

                        carInitialized = true
                        break
                    }
                    Thread.sleep((500 * i).toLong())
                }

                if (carInitialized) {
                    iSensorListener = MySensorListener()
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_IGNITION_STATE)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_TEMPERATURE_AMBIENT)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_TEMPERATURE_INDOOR)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_FUEL_LEVEL)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_CAR_SPEED)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_RPM)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_ENGINE_OIL_LEVEL)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_ODOMETER)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_SEAT_OCCUPATION_STATUS_PASSENGER)
                    iCarSensors?.registerListener(iSensorListener, SENSOR_TYPE_GEAR)

                    // ============== 注册胎压胎温传感器监听 ==============
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_PRESSURE_FRONT_LEFT)
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_PRESSURE_FRONT_RIGHT)
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_PRESSURE_REAR_LEFT)
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_PRESSURE_REAR_RIGHT)
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_TEMPERATURE_FRONT_LEFT)
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_TEMPERATURE_FRONT_RIGHT)
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_TEMPERATURE_REAR_LEFT)
                    iCarSensors?.registerListener(iSensorListener, IdNames.TIRE_TEMPERATURE_REAR_RIGHT)
                    // ============== 胎压胎温传感器注册结束 ==============

                    // ============== 注册油耗传感器监听 ==============
                    iCarSensors?.registerListener(iSensorListener, IdNames.SENSOR_INSTANT_FUEL_CONSUMPTION)
                    iCarSensors?.registerListener(iSensorListener, IdNames.SENSOR_AVG_FUEL_CONSUMPTION_VALUE)
                    // ============== 油耗传感器注册结束 ==============

                    iFunctionValueWatcher = MyFunctionValueWatcher()
                    // 注册所有需要的功能监听器
                    iCarFunction?.registerFunctionValueWatcher(
                        IHvac.HVAC_FUNC_FAN_SPEED,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IHvac.HVAC_FUNC_AUTO_FAN_SETTING,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IHvac.HVAC_FUNC_AUTO,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IHvac.HVAC_FUNC_BLOWING_MODE,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IdNames.HVAC_FUNC_CIRCULATION,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IdNames.HVAC_FUNC_AC,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IHvac.HVAC_FUNC_SEAT_HEATING,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IHvac.HVAC_FUNC_SEAT_VENTILATION,
                        iFunctionValueWatcher
                    )
                    // ============== 注册转向灯功能监听 ==============
                    iCarFunction?.registerFunctionValueWatcher(
                        IdNames.BCM_FUNC_LIGHT_LEFT_TURN_SIGNAL,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IdNames.BCM_FUNC_LIGHT_RIGHT_TURN_SIGNAL,
                        iFunctionValueWatcher
                    )
                    iCarFunction?.registerFunctionValueWatcher(
                        IdNames.BCM_FUNC_LIGHT_HAZARD_FLASHERS,
                        iFunctionValueWatcher
                    )
                    // ============== 转向灯功能注册结束 ==============
                    iCarFunction?.registerFunctionValueWatcher(
                        IDriveMode.DM_FUNC_DRIVE_MODE_SELECT,
                        iFunctionValueWatcher
                    )

                    Thread.sleep(2000)

                    // 启动实时档位计算定时器（提高频率到50ms）
                    startRealTimeGearCalculation()

                    // 主动查询所有初始数据
                    Handler(ht!!.looper).postDelayed({
                        queryAndSendAllData()
                    }, 1000)

                    needToUpdateData.set(true)

                    // 主循环
                    while (true) {
                        if (needToStop.get()) {
                            break
                        }

                        if (needToUpdateData.compareAndSet(true, false)) {
                            queryAndSendAllData()
                        }

                        Thread.sleep(1000)
                    }
                } else {
                    sendMessageToUI(IdNames.ERROR, "无法初始化车辆接口")
                }
            } catch (e: Exception) {
                Log.e("MConfigThread", "运行异常", e)
                sendMessageToUI(IdNames.ERROR, "运行异常: ${e.message}")
            } finally {
                // 停止实时档位计算
                stopRealTimeGearCalculation()

                // 清理资源
                iCarSensors?.unregisterListener(iSensorListener)
                iCarFunction?.unregisterFunctionValueWatcher(iFunctionValueWatcher)

                // 清理HandlerThread
                ht?.quit()
                ht = null

                // 清理静态Handler
                handleToThread?.removeCallbacksAndMessages(null)
            }

            sendMessageToUI(IdNames.ERROR, if (!needToStop.get()) "error_quit" else "quit")
            needToStop.set(false)
        }
    }
}