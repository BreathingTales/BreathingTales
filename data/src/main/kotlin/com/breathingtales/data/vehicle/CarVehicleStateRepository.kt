package com.breathingtales.data.vehicle

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.repository.VehicleStateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 정차 판단용 차량 상태 (docs/dev/02_기술_확인.md 2장).
 * UX 제한·기어·속도·주차 브레이크는 AAOS 표준 API로, 도어는 Pleos Door로 읽는다.
 * Pleos Gear는 deprecated이고 속도 API가 없어 쓰지 않는다. 앱이 살아 있는 동안 계속 구독한다.
 */
@Suppress("DEPRECATION") // registerCallback은 API 35+에서 subscribePropertyEvents로 바뀌었지만 minSdk 31을 지원한다
@Singleton
class CarVehicleStateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    pleos: PleosVehicle,
) : VehicleStateRepository {

    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val vehicle = pleos.vehicle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var propertyManager: CarPropertyManager? = null

    /** 문 하나라도 값을 모르면 닫힘을 확정하지 않는다 */
    private val doorPositions = mutableMapOf<DoorValue, Int?>()
    private var watchedDoors: List<DoorValue> = DEFAULT_DOORS

    /** 속도 권한(CAR_SPEED, 런타임)을 받은 뒤 다시 구독한다. 권한이 없으면 속도가 null이라 정차로 보지 않는다 */
    fun onPermissionsGranted() {
        propertyManager?.let(::subscribeProperties)
    }

    // region AAOS

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val v = value.value
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> _state.update { it.copy(gear = (v as? Int).toGear()) }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> _state.update { it.copy(speedMps = v as? Float) }
                VehiclePropertyIds.PARKING_BRAKE_ON -> _state.update { it.copy(parkingBrakeOn = v as? Boolean) }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "${VehiclePropertyIds.toString(propertyId)} 오류 이벤트")
            when (propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> _state.update { it.copy(gear = null) }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> _state.update { it.copy(speedMps = null) }
                VehiclePropertyIds.PARKING_BRAKE_ON -> _state.update { it.copy(parkingBrakeOn = null) }
            }
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener(::onUx)

    // 리스너 프로퍼티보다 뒤에 둔다. Car.createCar는 이미 연결돼 있으면 콜백을 그 자리에서 불러서,
    // init이 앞에 있으면 아직 초기화되지 않은 propertyCallback(null)을 등록하게 된다
    init {
        connectCar()
        scope.launch { subscribeDoors() }
    }

    private fun connectCar() {
        Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { car, ready ->
            if (!ready) {
                Log.w(TAG, "Car 서비스 연결 끊김")
                propertyManager = null
                _state.update { it.copy(uxRestricted = null, gear = null, speedMps = null, parkingBrakeOn = null) }
                return@createCar
            }
            runCatching {
                val ux = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                ux.registerListener(uxListener)
                ux.currentCarUxRestrictions?.let(::onUx)
            }.onFailure { Log.w(TAG, "UX 제한 구독 실패: ${it.message}") }
            runCatching { car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }
                .onSuccess {
                    propertyManager = it
                    subscribeProperties(it)
                }
                .onFailure { Log.w(TAG, "속성 서비스 실패: ${it.message}") }
        }
    }

    private fun subscribeProperties(manager: CarPropertyManager) {
        listOf(
            VehiclePropertyIds.GEAR_SELECTION to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.PARKING_BRAKE_ON to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_NORMAL,
        ).forEach { (id, rate) ->
            // 등록한 적 없는 콜백을 해제하면 NullPointerException이 나므로 해제는 따로 감싼다
            runCatching { manager.unregisterCallback(propertyCallback, id) }
            runCatching { manager.registerCallback(propertyCallback, id, rate) }.onFailure { Log.w(TAG, "${VehiclePropertyIds.toString(id)} 구독 실패: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    private fun onUx(restrictions: CarUxRestrictions) {
        _state.update { it.copy(uxRestricted = restrictions.isRequiresDistractionOptimization) }
    }

    // endregion

    // region Pleos Door

    private suspend fun subscribeDoors() {
        // 지원하는 문 가운데 승객이 타고 내리는 문만 본다. 트렁크(REAR)·후드는 연출 중 열려도 위험하지 않다
        orNull("도어 범위") {
            retrying {
                pleosCall<Map<DoorArea, AreaBounds<Int>>>("도어 범위") { ok, ng ->
                    vehicle.getDoor().checkDoorPositionCapabilities(ok, ng)
                }
            }
        }?.keys
            ?.flatMap { it.values.toList() }
            ?.filter { it in PASSENGER_DOORS }
            ?.takeIf { it.isNotEmpty() }
            ?.let { watchedDoors = it }

        watchedDoors.forEach { door ->
            val area = DoorArea(door)
            vehicle.getDoor().registerDoorPosition(area, object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = onDoor(door, pos)

                override fun onFailed(e: Exception) {
                    Log.w(TAG, "${door.name} 도어 구독 실패: ${e.message}")
                    onDoor(door, null)
                }
            })
            orNull("${door.name} 도어 읽기") {
                pleosCall<Int?>("${door.name} 도어 읽기") { ok, ng -> vehicle.getDoor().getCurrentDoorPosition(area, ok, ng) }
            }?.let { onDoor(door, it) }
        }
        Log.i(TAG, "도어 구독: ${watchedDoors.joinToString { it.name }}")
    }

    private fun onDoor(door: DoorValue, position: Int?) {
        val closed = synchronized(doorPositions) {
            doorPositions[door] = position
            val positions = watchedDoors.map { doorPositions[it] }
            when {
                positions.any { it != null && it > 0 } -> false
                positions.all { it == 0 } -> true
                else -> null
            }
        }
        _state.update { it.copy(doorsClosed = closed) }
    }

    // endregion

    private companion object {
        val PASSENGER_DOORS = setOf(
            DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
            DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT,
            DoorValue.ROW_3_LEFT, DoorValue.ROW_3_RIGHT,
        )
        val DEFAULT_DOORS = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)

        // android.car.VehicleGear 상수값 (02 문서 2장: P=4, D=8)
        const val GEAR_NEUTRAL = 0x0001
        const val GEAR_REVERSE = 0x0002
        const val GEAR_PARK = 0x0004
        const val GEAR_DRIVE = 0x0008

        fun Int?.toGear(): Gear? = when (this) {
            null -> null
            GEAR_PARK -> Gear.PARK
            GEAR_REVERSE -> Gear.REVERSE
            GEAR_NEUTRAL -> Gear.NEUTRAL
            GEAR_DRIVE -> Gear.DRIVE
            else -> Gear.OTHER
        }
    }
}
