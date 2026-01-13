package ru.monjaro.mconfig

class IdNames  {
    companion object {
        val ERROR:Int = -1
        val TOAST:Int = -2
        val UPDATE_UI:Int = -3
        val IGNITION_STATE: Int = 1
        val AMBIENT_TEMPERATURE: Int = 2
        val INT_TEMPERATURE: Int = 3
        val FUEL_LEVEL: Int = 4
        val CAR_SPEED: Int = 5
        val SENSOR_RPM: Int = 6
        val SENSOR_OIL_LEVEL: Int = 7
        val DRIVE_SPORT_KEYPRESSED: Int = 8
        val DRIVE_SNOW_KEYPRESSED: Int = 9
        val DRIVE_OFFROAD_KEYPRESSED: Int = 10
        val SEAT_OCCUPATION_STATUS_PASSENGER: Int = 11
        val SENSOR_TYPE_GEAR: Int = 12

        const val HVAC_FUNC_CIRCULATION = 268632320
        const val HVAC_FUNC_AC = 268501760

        // 循环模式常量
        const val CIRCULATION_AUTO = 268632323
        const val CIRCULATION_INNER = 268632321
        const val CIRCULATION_OUTSIDE = 268632322
        const val CIRCULATION_OFF = 0

        // 座椅状态相关消息类型
        const val SEAT_HEATING_DRIVER = 1001
        const val SEAT_HEATING_PASSENGER = 1002
        const val SEAT_VENTILATION_DRIVER = 1003
        const val SEAT_VENTILATION_PASSENGER = 1004

        //总里程相关消息
        const val ODOMETER = 1005

        // 座椅加热常量
        const val SEAT_HEATING_OFF = 0
        const val SEAT_HEATING_LEVEL_1 = 268763649
        const val SEAT_HEATING_LEVEL_2 = 268763650
        const val SEAT_HEATING_LEVEL_3 = 268763651

        // 座椅通风常量
        const val SEAT_VENTILATION_OFF = 0
        const val SEAT_VENTILATION_LEVEL_1 = 268763393
        const val SEAT_VENTILATION_LEVEL_2 = 268763394
        const val SEAT_VENTILATION_LEVEL_3 = 268763395

        // 座椅区域常量
        const val SEAT_ROW_1_LEFT = 1  // 驾驶员
        const val SEAT_ROW_1_RIGHT = 4 // 副驾驶

        // 转向灯相关消息类型
        const val TURN_SIGNAL = 2000
        const val LIGHT_LEFT_TURN = 2001
        const val LIGHT_RIGHT_TURN = 2002
        const val LIGHT_HAZARD_FLASHERS = 2006

        // BCM 灯光功能常量
        const val BCM_FUNC_LIGHT_LEFT_TURN_SIGNAL = 553980160
        const val BCM_FUNC_LIGHT_RIGHT_TURN_SIGNAL = 553980416
        const val BCM_FUNC_LIGHT_HAZARD_FLASHERS = 553979648

        // 新增：胎压胎温传感器ID
        const val TIRE_PRESSURE_FRONT_LEFT = 5243136
        const val TIRE_PRESSURE_FRONT_RIGHT = 5243392
        const val TIRE_PRESSURE_REAR_LEFT = 5243648
        const val TIRE_PRESSURE_REAR_RIGHT = 5243904

        const val TIRE_TEMPERATURE_FRONT_LEFT = 5244160
        const val TIRE_TEMPERATURE_FRONT_RIGHT = 5244416
        const val TIRE_TEMPERATURE_REAR_LEFT = 5244672
        const val TIRE_TEMPERATURE_REAR_RIGHT = 5244928

        // 胎压胎温消息类型
        const val TIRE_DATA = 3000
        const val TIRE_PRESSURE_FL = 3001
        const val TIRE_PRESSURE_FR = 3002
        const val TIRE_PRESSURE_RL = 3003
        const val TIRE_PRESSURE_RR = 3004
        const val TIRE_TEMP_FL = 3005
        const val TIRE_TEMP_FR = 3006
        const val TIRE_TEMP_RL = 3007
        const val TIRE_TEMP_RR = 3008

        // 瞬时油耗相关消息类型
        const val FUEL_CONSUMPTION_DATA = 4000
        const val INSTANT_FUEL_CONSUMPTION = 4001
        const val SENSOR_AVG_FUEL_CONSUMPTION = 4002  // 单次点火平均油耗

        // 油耗传感器常量
        // INS瞬时油耗 (L/100km)
        const val SENSOR_INSTANT_FUEL_CONSUMPTION = 4194816
        const val SENSOR_AVG_FUEL_CONSUMPTION_VALUE = 4195072  // TYPE_AVG_FUEL_CONSUMPTION_ONE_IGNITION

        // 删除熄火时间记录相关的常量

        val StartService_key: String = "StartService"
        val debugToast_key: String = "debugToastCfg"

        // 风量相关ID
        val HVAC_FUNC_FAN_SPEED: Int = 268566784
        val HVAC_FUNC_AUTO_FAN_SETTING: Int = 268567040
        val HVAC_FUNC_AUTO: Int = 268501504

        // 风量级别常量
        val FAN_SPEED_LEVEL_1: Int = 268566785
        val FAN_SPEED_LEVEL_2: Int = 268566786
        val FAN_SPEED_LEVEL_3: Int = 268566787
        val FAN_SPEED_LEVEL_4: Int = 268566788
        val FAN_SPEED_LEVEL_5: Int = 268566789
        val FAN_SPEED_LEVEL_6: Int = 268566790
        val FAN_SPEED_LEVEL_7: Int = 268566791
        val FAN_SPEED_LEVEL_8: Int = 268566792
        val FAN_SPEED_LEVEL_9: Int = 268566793
        val FAN_SPEED_LEVEL_AUTO: Int = 268566794
        val FAN_SPEED_OFF: Int = 0

        // 自动风量设置
        val AUTO_FAN_SETTING_SILENT: Int = 268567041
        val AUTO_FAN_SETTING_NORMAL: Int = 268567042
        val AUTO_FAN_SETTING_HIGH: Int = 268567043
        val AUTO_FAN_SETTING_QUIETER: Int = 268567044
        val AUTO_FAN_SETTING_HIGHER: Int = 268567045

        // 出风方向相关常量
        val HVAC_FUNC_BLOWING_MODE: Int = 268894464
        val BLOWING_MODE_FACE: Int = 268894465
        val BLOWING_MODE_FACE_AND_LEG: Int = 268894467
        val BLOWING_MODE_LEG: Int = 268894466
        val BLOWING_MODE_FRONT_WINDOW: Int = 268894468
        val BLOWING_MODE_FACE_AND_FRONT_WINDOW: Int = 268894469
        val BLOWING_MODE_LEG_AND_FRONT_WINDOW: Int = 268894470
        val BLOWING_MODE_ALL: Int = 268894471
        val BLOWING_MODE_AUTO_SWITCH: Int = 268894472
        val BLOWING_MODE_OFF: Int = 0

        // 驾驶模式常量
        const val DRIVE_MODE_SELECTION_ECO = 570491137
        const val DRIVE_MODE_SELECTION_COMFORT = 570491138
        const val DRIVE_MODE_SELECTION_DYNAMIC = 570491139
        const val DRIVE_MODE_SELECTION_ADAPTIVE = 570491158
        const val DRIVE_MODE_SELECTION_SNOW = 570491145  // 雪地模式
        const val DRIVE_MODE_SELECTION_OFFROAD = 570491155
    }
}