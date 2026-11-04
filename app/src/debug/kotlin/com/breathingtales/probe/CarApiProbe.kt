package com.breathingtales.probe

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class CarApiLiveState(
    val connected: Boolean = false,
    val uxRestricted: String = "-",
    val parkingBrake: String = "-",
    val speed: String = "-",
    val gear: String = "-",
    val ignition: String = "-",
)

/**
 * Vehicle SDK 공개 API에 없는 주차 브레이크·속도, 그리고 주행 UX 제한을 AAOS 표준 API로 읽는다.
 * - 주차 브레이크: PARKING_BRAKE_ON (android.car.permission.CAR_POWERTRAIN)
 * - 속도: PERF_VEHICLE_SPEED (android.car.permission.CAR_SPEED, 런타임 권한)
 * - 기어: GEAR_SELECTION (Pleos Gear 도메인과 교차 확인용)
 * - 시동: IGNITION_STATE (공조 전원이 켜지지 않는 원인 확인용)
 */
@Suppress("DEPRECATION") // registerCallback은 API 35+에서 subscribePropertyEvents로 대체됐지만 확인용으로는 충분하다
class CarApiProbe(private val context: Context, private val log: ProbeLog) {
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var propertyManager: CarPropertyManager? = null

    private val _live = MutableStateFlow(CarApiLiveState())
    val live: StateFlow<CarApiLiveState> = _live

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val v = value.value
            when (value.propertyId) {
                VehiclePropertyIds.PARKING_BRAKE_ON -> _live.update { it.copy(parkingBrake = v.toString()) }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> _live.update { it.copy(speed = "%.1f m/s".format(v as? Float ?: 0f)) }
                VehiclePropertyIds.GEAR_SELECTION -> _live.update { it.copy(gear = gearName(v as? Int)) }
                VehiclePropertyIds.IGNITION_STATE -> _live.update { it.copy(ignition = ignitionName(v as? Int)) }
            }
            if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                logSpeedIfChanged(v as? Float ?: 0f)
            } else {
                log.add(AAOS, "${VehiclePropertyIds.toString(value.propertyId)} = $v")
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            log.add(AAOS, "❌ ${VehiclePropertyIds.toString(propertyId)} 오류 이벤트 (area $areaId)")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { onUx(it) }

    private var lastLoggedSpeed = -1f

    /** 속도는 연속값이라 매번 남기지 않고, 정지↔주행이 바뀌거나 1 m/s 이상 변할 때만 남긴다. */
    private fun logSpeedIfChanged(speed: Float) {
        val stoppedChanged = (speed == 0f) != (lastLoggedSpeed == 0f)
        if (lastLoggedSpeed < 0 || stoppedChanged || kotlin.math.abs(speed - lastLoggedSpeed) >= 1f) {
            lastLoggedSpeed = speed
            log.add(AAOS, "속도 %.1f m/s (%.0f km/h)%s".format(speed, speed * 3.6f, if (speed == 0f) " — 정지" else ""))
        }
    }

    fun connect() {
        if (car != null) return log.add(AAOS, "이미 연결됨")
        Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { connectedCar, ready ->
            if (!ready) {
                log.add(AAOS, "Car 서비스 연결 끊김")
                _live.update { it.copy(connected = false) }
                return@createCar
            }
            car = connectedCar
            _live.update { it.copy(connected = true) }
            log.add(AAOS, "Car 서비스 연결됨")
            setUpUxRestrictions(connectedCar)
            setUpProperties(connectedCar)
        }
    }

    private fun setUpUxRestrictions(car: Car) {
        runCatching {
            val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = manager
            manager.registerListener(uxListener)
            manager.currentCarUxRestrictions?.let(::onUx)
        }.onFailure { log.add(AAOS, "❌ UX 제한 서비스: ${it.message}") }
    }

    private fun setUpProperties(car: Car) {
        val manager = runCatching { car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }
            .onFailure { log.add(AAOS, "❌ 속성 서비스: ${it.message}") }
            .getOrNull() ?: return
        propertyManager = manager
        listOf(
            VehiclePropertyIds.PARKING_BRAKE_ON to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.GEAR_SELECTION to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.IGNITION_STATE to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_NORMAL,
        ).forEach { (id, rate) ->
            val name = VehiclePropertyIds.toString(id)
            runCatching {
                if (manager.getCarPropertyConfig(id) == null) {
                    log.add(AAOS, "$name: 이 차량(에뮬레이터)에서 지원하지 않음")
                    return@runCatching
                }
                val ok = manager.registerCallback(propertyCallback, id, rate)
                log.add(AAOS, "$name 구독: ${if (ok) "성공" else "실패"}")
            }.onFailure { log.add(AAOS, "❌ $name 구독: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    private fun onUx(restrictions: CarUxRestrictions) {
        val text = if (restrictions.isRequiresDistractionOptimization) {
            "제한 중 (0x${Integer.toHexString(restrictions.activeRestrictions)})"
        } else {
            "제한 없음"
        }
        _live.update { it.copy(uxRestricted = text) }
        log.add(AAOS, "UX 제한: $text")
    }

    fun disconnect() {
        runCatching {
            uxManager?.unregisterListener()
            propertyManager?.unregisterCallback(propertyCallback)
            car?.disconnect()
        }
        car = null
    }

    private fun gearName(value: Int?) = when (value) {
        GEAR_PARK -> "P"
        GEAR_REVERSE -> "R"
        GEAR_NEUTRAL -> "N"
        GEAR_DRIVE -> "D"
        null -> "null"
        else -> value.toString()
    }

    // android.car.VehicleIgnitionState 상수값. 공조는 ON(시동)이어야 켜질 수 있다.
    private fun ignitionName(value: Int?) = when (value) {
        0 -> "UNDEFINED"
        1 -> "LOCK"
        2 -> "OFF"
        3 -> "ACC"
        4 -> "ON"
        5 -> "START"
        null -> "null"
        else -> value.toString()
    }

    private companion object {
        const val AAOS = "AAOS"

        // android.car.VehicleGear 상수값
        const val GEAR_NEUTRAL = 0x0001
        const val GEAR_REVERSE = 0x0002
        const val GEAR_PARK = 0x0004
        const val GEAR_DRIVE = 0x0008
    }
}
