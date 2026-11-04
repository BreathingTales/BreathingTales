package com.breathingtales.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class VehicleLiveState(
    val gear: String = "-",
    val brakePedal: String = "-",
    val hvacPower: String = "-",
    val doors: Map<String, String> = emptyMap(),
)

/**
 * Pleos Vehicle SDK 2.3.2 확인용 호출 모음 (기획서 10장, docs/dev/02_기술_확인.md).
 * 제어 대상 영역(Area)은 차종마다 다르므로 [checkCapabilities]로 먼저 찾은 뒤 쓴다.
 */
class VehicleProbe(context: Context, private val log: ProbeLog, private val scope: CoroutineScope) {
    private val vehicle = Vehicle(context.applicationContext)

    private val _live = MutableStateFlow(VehicleLiveState())
    val live: StateFlow<VehicleLiveState> = _live

    private var fanArea: SeatArea? = null
    private var tempArea: SeatArea? = null
    private var tempAreas: List<SeatArea> = emptyList() // 에뮬레이터: 운전석·조수석이 따로 있다
    private var ambientArea: SeatArea? = null
    private var rearSeatAreas: List<SeatArea> = emptyList()
    private var powerAreas: List<SeatArea> = emptyList() // HVAC_POWER_ON은 풍량과 영역 구성이 다르다

    private var snapshot: CabinSnapshot? = null

    /** 호출했는데 onComplete/onFailed 둘 다 오지 않는 경우(서비스 미연결 등)를 잡아내기 위한 대기 목록 */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** 전원을 켠 뒤 실제로 켜졌다는 이벤트(true)를 받으면 실행할 동작들 */
    private val afterPowerOn = mutableListOf<() -> Unit>()

    private val powerListener = object : HvacPowerStateListener {
        override fun onHvacPowerStateUpdated(state: Boolean?) {
            _live.update { it.copy(hvacPower = state.toString()) }
            log.add(HVAC, "공조 전원 이벤트: $state")
            if (state == true) {
                val actions = synchronized(afterPowerOn) { afterPowerOn.toList().also { afterPowerOn.clear() } }
                actions.forEach { it() }
            }
        }

        override fun onFailed(e: Exception) = fail(HVAC, "공조 전원 구독")(e)
    }

    private data class CabinSnapshot(
        val hvacPower: Boolean?,
        val fanSpeed: Int?,
        val fanDirection: FanDirectionType?,
        val temperatureC: Float?,
        val ambientColor: Int?,
        val ambientBrightness: Int?,
    )

    fun initialize() {
        vehicle.initialize()
        log.add(V, "initialize() 호출")
    }

    fun release() {
        unregisterListeners()
        vehicle.release()
    }

    // region 지원 범위

    fun checkCapabilities() {
        log.add(V, "지원 범위 확인 시작")
        listOf("공조 전원 영역", "풍량 범위", "풍향 지원", "온도 범위", "앰비언트 밝기 범위", "앰비언트 색상 지원", "시트 온도 범위", "도어 위치 범위")
            .forEach(pending::add)
        scope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            if (pending.isNotEmpty()) {
                log.add(V, "⚠️ ${RESPONSE_TIMEOUT_MS / 1000}초 동안 응답 없음: ${pending.joinToString()} → Vehicle 서비스 연결 문제일 수 있음")
            } else {
                log.add(V, "지원 범위 확인 완료")
            }
        }
        val hvac = vehicle.getHVAC()
        hvac.checkHvacPowerCapabilities({ config, areas ->
            pending.remove("공조 전원 영역")
            powerAreas = areas
            log.add(HVAC, "공조 전원 영역(${config.accessType}): ${areas.joinToString { it.label() }.ifEmpty { "(비어 있음)" }}")
            areas.firstOrNull()?.let { area ->
                hvac.registerHvacPowerState(area, powerListener)
                log.add(HVAC, "공조 전원 변화 구독 시작")
            }
        }, fail(HVAC, "공조 전원 영역"))
        hvac.checkHvacFanSpeedCapabilities({ config, map ->
            pending.remove("풍량 범위")
            fanArea = map.keys.firstOrNull()
            log.add(HVAC, "풍량 범위(${config.accessType}): ${map.describe()}")
        }, fail(HVAC, "풍량 범위"))
        hvac.checkHvacFanDirectionCapabilities({ config, map ->
            pending.remove("풍향 지원")
            log.add(HVAC, "풍향 지원(${config.accessType}): ${map.entries.joinToString { "${it.key.label()}=${it.value}" }}")
        }, fail(HVAC, "풍향 지원"))
        hvac.checkHvacTemperatureCapabilities({ config, map, range ->
            pending.remove("온도 범위")
            tempAreas = map.keys.toList()
            tempArea = tempAreas.firstOrNull()
            log.add(HVAC, "온도 범위(${config.accessType}): ${map.describe()} / 단위: $range")
        }, fail(HVAC, "온도 범위"))

        val light = vehicle.getLight()
        light.checkAmbientLightBrightnessCapabilities({ config, map ->
            pending.remove("앰비언트 밝기 범위")
            ambientArea = map.keys.firstOrNull()
            log.add(LIGHT, "앰비언트 밝기 범위(${config.accessType}): ${map.describe()}")
        }, fail(LIGHT, "앰비언트 밝기 범위"))
        light.checkAmbientLightBrightnessColorCapabilities({ config, areas ->
            pending.remove("앰비언트 색상 지원")
            if (ambientArea == null) ambientArea = areas.firstOrNull()
            log.add(LIGHT, "앰비언트 색상 지원(${config.accessType}): ${areas.joinToString { it.label() }}")
        }, fail(LIGHT, "앰비언트 색상 지원"))

        vehicle.getSeat().checkSeatTemperatureCapabilities({ config, map ->
            pending.remove("시트 온도 범위")
            rearSeatAreas = map.keys.filter { area -> area.values.any { it.name.startsWith("ROW_2") } }
            log.add(SEAT, "시트 온도 범위(${config.accessType}): ${map.describe()} / 뒷좌석: ${rearSeatAreas.joinToString { it.label() }}")
        }, fail(SEAT, "시트 온도 범위"))

        vehicle.getDoor().checkDoorPositionCapabilities({ config, map ->
            pending.remove("도어 위치 범위")
            log.add(DOOR, "도어 위치 범위(${config.accessType}): ${map.entries.joinToString { "${it.key.label()}=${it.value.minValue}..${it.value.maxValue}" }}")
        }, fail(DOOR, "도어 위치 범위"))
    }

    // endregion

    // region 현재값 읽기

    fun readAll() {
        val hvac = vehicle.getHVAC()
        fanArea?.let { area ->
            hvac.getHvacFanSpeed(area, { log.add(HVAC, "현재 풍량: $it") }, fail(HVAC, "풍량 읽기"))
            hvac.getHvacFanDirection(area, { log.add(HVAC, "현재 풍향: $it") }, fail(HVAC, "풍향 읽기"))
        } ?: needCapabilities(HVAC)
        powerAreas.forEach { area ->
            hvac.getHvacPowerState(area, { log.add(HVAC, "${area.label()} 공조 전원: $it") }, fail(HVAC, "전원 읽기"))
        }
        tempArea?.let { area ->
            hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, { log.add(HVAC, "설정 온도: $it℃") }, fail(HVAC, "온도 읽기"))
        }

        val light = vehicle.getLight()
        ambientArea?.let { area ->
            light.getAmbientLightSwitch(area, { log.add(LIGHT, "앰비언트 스위치: $it") }, fail(LIGHT, "스위치 읽기"))
            light.getAmbientLightBrightness(area, { log.add(LIGHT, "앰비언트 밝기: $it") }, fail(LIGHT, "밝기 읽기"))
            light.getAmbientLightBrightnessColor(area, { log.add(LIGHT, "앰비언트 색상: ${it?.toArgbHex()}") }, fail(LIGHT, "색상 읽기"))
        } ?: needCapabilities(LIGHT)

        rearSeatAreas.forEach { area ->
            vehicle.getSeat().getHvacSeatTemperature(area, { log.add(SEAT, "${area.label()} 시트 온도: $it") }, fail(SEAT, "시트 온도 읽기"))
        }

        // Pleos Gear API는 deprecated("use the Google GEAR_SELECTION property"). AAOS 값과 비교하려고 호출한다.
        @Suppress("DEPRECATION")
        vehicle.getGear().getCurrentGearSelectionState({ log.add(DRIVE, "기어: $it") }, fail(DRIVE, "기어 읽기"))
        vehicle.getBrake().getCurrentBrakePedalStatus({ log.add(DRIVE, "브레이크 페달: $it") }, fail(DRIVE, "브레이크 페달 읽기"))
        vehicle.getDrivingMode().getCurrentDrivingModeState({ log.add(DRIVE, "주행 모드: $it") }, fail(DRIVE, "주행 모드 읽기"))

        // 주차 브레이크는 Vehicle SDK 공개 API에 없다. 경고등(telltale)으로 대신 읽을 수 있는지 확인한다.
        vehicle.getSafety().getTelltalesInfo({ telltales ->
            val parking = telltales.telltales.filter {
                it.type == TelltaleType.PARKING_BRAKE || it.type == TelltaleType.ELECTRONIC_PARKING_BRAKE
            }
            log.add(DRIVE, "경고등 ${telltales.telltales.size}개, 주차 브레이크 관련: ${parking.ifEmpty { "없음" }}")
        }, fail(DRIVE, "경고등 읽기"))
    }

    // endregion

    // region 제어

    fun setHvacPower(on: Boolean, then: () -> Unit = {}) {
        if (powerAreas.isEmpty()) return needCapabilities(HVAC)
        var remaining = powerAreas.size
        powerAreas.forEach { area ->
            vehicle.getHVAC().setHvacPower(area, on, { a, v ->
                log.add(HVAC, "${a.label()} 공조 전원 설정됨: $v")
                if (--remaining == 0) then()
            }, fail(HVAC, "전원 설정"))
        }
    }

    /**
     * AAOS에서는 공조 전원이 꺼져 있으면 풍량·온도·풍향이 NOT_AVAILABLE(오류 코드 2)이 된다.
     * 제어 전에 전원을 확인하고, 꺼져 있으면 켠 뒤 이어서 실행한다.
     */
    private fun withHvacOn(action: () -> Unit) {
        val area = powerAreas.firstOrNull() ?: return needCapabilities(HVAC)
        vehicle.getHVAC().getHvacPowerState(area, { on ->
            if (on == true) {
                action()
            } else {
                // set 응답(onComplete)은 요청 접수일 뿐이다. 실제로 켜졌다는 전원 이벤트를 받은 뒤 실행한다.
                log.add(HVAC, "공조 전원이 꺼져 있음($on) → 켜고, 켜짐 이벤트를 기다린 뒤 실행")
                synchronized(afterPowerOn) {
                    afterPowerOn += {
                        fillSnapshotTemperature()
                        action()
                    }
                }
                setHvacPower(true)
                scope.launch {
                    delay(POWER_ON_TIMEOUT_MS)
                    val dropped = synchronized(afterPowerOn) { afterPowerOn.size.also { afterPowerOn.clear() } }
                    if (dropped > 0) {
                        log.add(HVAC, "⚠️ ${POWER_ON_TIMEOUT_MS / 1000}초 안에 공조 전원이 켜지지 않아 동작 ${dropped}개 취소 → 에뮬레이터 공조 시뮬레이터 상태(Virtual Console 공조 전원)를 확인하세요")
                    }
                }
            }
        }, fail(HVAC, "전원 확인"))
    }

    /**
     * 전원이 꺼진 상태에서 저장한 스냅샷은 설정 온도가 null이다(꺼져 있으면 NOT_AVAILABLE).
     * 전원을 켠 직후, 아직 아무것도 바꾸기 전에 읽어 복원 기준값으로 채운다.
     */
    private fun fillSnapshotTemperature() {
        val s = snapshot ?: return
        if (s.temperatureC != null) return
        val area = tempArea ?: return
        vehicle.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, { temp ->
            snapshot = s.copy(temperatureC = temp)
            log.add(SNAP, "전원을 켠 직후 설정 온도를 읽어 기준값으로 저장: $temp℃")
        }, fail(SNAP, "전원 켠 뒤 온도 읽기"))
    }

    fun setFanSpeed(level: Int) = withArea(fanArea, HVAC) { area ->
        withHvacOn {
            vehicle.getHVAC().setHvacFanSpeed(area, level, { _, v -> log.add(HVAC, "풍량 설정됨: $v") }, fail(HVAC, "풍량 $level 설정"))
        }
    }

    fun setFanDirection(direction: FanDirectionType) = withArea(fanArea, HVAC) { area ->
        withHvacOn {
            vehicle.getHVAC().setHvacFanDirection(area, direction, { _, v -> log.add(HVAC, "풍향 설정됨: $v") }, fail(HVAC, "풍향 설정"))
        }
    }

    /** 기획서의 temperatureDelta: 영역마다 현재 설정 온도를 읽고 상대값만큼 바꾼다. */
    fun shiftTemperature(deltaC: Float) {
        if (tempAreas.isEmpty()) return needCapabilities(HVAC)
        withHvacOn { shiftTemperatureNow(deltaC) }
    }

    private fun shiftTemperatureNow(deltaC: Float) {
        val hvac = vehicle.getHVAC()
        tempAreas.forEach { area ->
            hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, { current ->
                if (current == null) {
                    log.add(HVAC, "${area.label()} 현재 온도를 읽지 못해 변경하지 않음")
                    return@getHvacTemperature
                }
                hvac.setHvacTemperature(area, current + deltaC, TemperatureUnit.CELSIUS,
                    { a, v -> log.add(HVAC, "${a.label()} 온도 $current → $v℃") }, fail(HVAC, "온도 설정"))
            }, fail(HVAC, "온도 읽기"))
        }
    }

    fun setAmbientSwitch(on: Boolean) = withArea(ambientArea, LIGHT) { area ->
        vehicle.getLight().setAmbientLightSwitch(area, if (on) LightSwitch.ON else LightSwitch.OFF,
            { _, v -> log.add(LIGHT, "앰비언트 스위치 설정됨: $v") }, fail(LIGHT, "스위치 설정"))
    }

    fun setAmbient(argb: Int, brightness: Int) = withArea(ambientArea, LIGHT) { area ->
        val light = vehicle.getLight()
        light.setAmbientLightBrightnessColor(area, argb, { _, v -> log.add(LIGHT, "색상 설정됨: ${v.toArgbHex()}") }, fail(LIGHT, "색상 설정"))
        light.setAmbientLightBrightness(area, brightness, { _, v -> log.add(LIGHT, "밝기 설정됨: $v") }, fail(LIGHT, "밝기 설정"))
    }

    fun disableAmbientAutoByDriveMode() {
        vehicle.getLight().setAmbientColorAutoByDriveModeEnabled(false,
            { log.add(LIGHT, "주행 모드 연동 자동 색상: $it") }, fail(LIGHT, "자동 색상 해제"))
    }

    fun setRearSeatHeat(level: Int) {
        if (rearSeatAreas.isEmpty()) {
            log.add(SEAT, "뒷좌석 시트 온도 제어 영역 없음 (지원 범위 결과에 ROW_2가 없으면 미지원)")
            return
        }
        rearSeatAreas.forEach { area ->
            vehicle.getSeat().setHvacSeatTemperature(area, level,
                { a, v -> log.add(SEAT, "${a.label()} 시트 온도 설정됨: $v") }, fail(SEAT, "시트 온도 설정"))
        }
    }

    // endregion

    // region 스냅샷·복원 (Safety Guard 원상 복구 확인)

    fun takeSnapshot() {
        val fan = fanArea ?: return needCapabilities(HVAC)
        val power = powerAreas.firstOrNull() ?: return needCapabilities(HVAC)
        val tArea = tempArea ?: fan
        val hvac = vehicle.getHVAC()
        val light = vehicle.getLight()
        // 콜백을 차례로 잇는다. 한 값을 못 읽어도(예: 전원이 꺼져 NOT_AVAILABLE) null로 두고 계속 저장한다.
        // 실제 구현에서는 suspend 함수로 감싼다.
        orNull<Boolean>(SNAP, "전원 저장", { ok, ng -> hvac.getHvacPowerState(power, ok, ng) }) { powerOn ->
            orNull<Int>(SNAP, "풍량 저장", { ok, ng -> hvac.getHvacFanSpeed(fan, ok, ng) }) { speed ->
                orNull<FanDirectionType>(SNAP, "풍향 저장", { ok, ng -> hvac.getHvacFanDirection(fan, ok, ng) }) { direction ->
                    orNull<Float>(SNAP, "온도 저장", { ok, ng -> hvac.getHvacTemperature(tArea, TemperatureUnit.CELSIUS, ok, ng) }) { temp ->
                        val aArea = ambientArea
                        if (aArea == null) {
                            saveSnapshot(CabinSnapshot(powerOn, speed, direction, temp, null, null))
                        } else {
                            orNull<Int>(SNAP, "색상 저장", { ok, ng -> light.getAmbientLightBrightnessColor(aArea, ok, ng) }) { color ->
                                orNull<Int>(SNAP, "밝기 저장", { ok, ng -> light.getAmbientLightBrightness(aArea, ok, ng) }) { brightness ->
                                    saveSnapshot(CabinSnapshot(powerOn, speed, direction, temp, color, brightness))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** get 호출의 실패를 null 결과로 바꿔 다음 단계로 넘긴다. */
    private fun <T> orNull(
        section: String,
        what: String,
        call: (onComplete: (T?) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
        next: (T?) -> Unit,
    ) {
        call({ next(it) }, { e ->
            log.add(section, "⚠️ $what 실패 → null로 저장: ${e.message}")
            next(null)
        })
    }

    private fun saveSnapshot(s: CabinSnapshot) {
        snapshot = s
        log.add(SNAP, "저장: $s (색상 ${s.ambientColor?.toArgbHex()}, 읽을 때는 알파 없이 RGB만 옴)")
        if (s.hvacPower == false && s.temperatureC == null) {
            log.add(SNAP, "공조 전원이 꺼져 있어 온도는 비어 있음 → 처음 공조를 켤 때 채움")
        }
    }

    fun restoreSnapshot() {
        val s = snapshot ?: return log.add(SNAP, "저장된 스냅샷 없음")
        if (s.ambientColor != null && s.ambientBrightness != null) setAmbient(s.ambientColor, s.ambientBrightness)
        // 공조 값은 전원이 켜져 있어야 바꿀 수 있으므로 켠 상태에서 복원하고, 원래 꺼져 있었다면 마지막에 끈다.
        withHvacOn {
            val hvac = vehicle.getHVAC()
            val fan = fanArea
            if (fan != null) {
                s.fanSpeed?.let { hvac.setHvacFanSpeed(fan, it, { _, v -> log.add(SNAP, "풍량 복원: $v") }, fail(SNAP, "풍량 복원")) }
                s.fanDirection?.let { hvac.setHvacFanDirection(fan, it, { _, v -> log.add(SNAP, "풍향 복원: $v") }, fail(SNAP, "풍향 복원")) }
            }
            s.temperatureC?.let { t ->
                tempAreas.forEach { area ->
                    hvac.setHvacTemperature(area, t, TemperatureUnit.CELSIUS,
                        { a, v -> log.add(SNAP, "${a.label()} 온도 복원: $v") }, fail(SNAP, "온도 복원"))
                }
            }
            if (s.hvacPower == false) {
                scope.launch {
                    delay(500) // 위 설정이 먼저 반영되도록 잠깐 기다린다
                    setHvacPower(false) { log.add(SNAP, "공조 전원을 원래대로 끔") }
                }
            }
            log.add(SNAP, "복원 명령 보냄")
        }
    }

    // endregion

    // region 상태 구독 (정차 판단·중단 조건)

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            _live.update { it.copy(gear = gear?.name ?: "null") }
            log.add(DRIVE, "기어 변경: $gear")
        }

        override fun onFailed(e: Exception) = fail(DRIVE, "기어 구독")(e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            _live.update { it.copy(brakePedal = status.toString()) }
            log.add(DRIVE, "브레이크 페달: $status")
        }

        override fun onFailed(e: Exception) = fail(DRIVE, "브레이크 페달 구독")(e)
    }

    private val doorListeners = WATCHED_DOORS.associateWith { door ->
        object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) {
                _live.update { it.copy(doors = it.doors + (door.name to pos.toString())) }
                log.add(DOOR, "${door.name} 위치: $pos")
            }

            override fun onFailed(e: Exception) = fail(DOOR, "${door.name} 구독")(e)
        }
    }

    @Suppress("DEPRECATION")
    fun registerListeners() {
        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        doorListeners.forEach { (door, listener) -> vehicle.getDoor().registerDoorPosition(DoorArea(door), listener) }
        log.add(V, "기어·브레이크 페달·도어 ${WATCHED_DOORS.size}곳 구독 시작")
    }

    @Suppress("DEPRECATION")
    fun unregisterListeners() {
        runCatching {
            powerAreas.firstOrNull()?.let { vehicle.getHVAC().unregisterHvacPowerState(it, powerListener) }
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            doorListeners.forEach { (door, listener) -> vehicle.getDoor().unregisterDoorPosition(DoorArea(door), listener) }
        }
    }

    // endregion

    private inline fun withArea(area: SeatArea?, section: String, block: (SeatArea) -> Unit) {
        if (area == null) needCapabilities(section) else block(area)
    }

    private fun needCapabilities(section: String) {
        log.add(section, "제어 영역을 아직 모름 → 지원 범위 확인을 자동 실행합니다. 결과가 나오면 다시 눌러 주세요")
        checkCapabilities()
    }

    // SDK의 VehicleException은 배포 AAR에서 난독화되어(클래스명 `a`) 타입으로 쓸 수 없다. 메시지에 ErrorCode 이름이 담긴다.
    private fun fail(section: String, what: String): (Exception) -> Unit = { e ->
        pending.remove(what)
        log.add(section, "❌ $what 실패: ${e.message ?: e.javaClass.name}")
    }

    private fun <V> Map<SeatArea, V>.describe() = entries.joinToString { "${it.key.label()}=${it.value}" }.ifEmpty { "(비어 있음)" }

    private companion object {
        const val V = "Vehicle"
        const val HVAC = "HVAC"
        const val LIGHT = "Light"
        const val SEAT = "Seat"
        const val DOOR = "Door"
        const val DRIVE = "Drive"
        const val SNAP = "Snapshot"
        const val RESPONSE_TIMEOUT_MS = 5_000L
        const val POWER_ON_TIMEOUT_MS = 3_000L

        val WATCHED_DOORS = listOf(
            DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
            DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT,
            DoorValue.REAR,
        )
    }
}

private fun SeatArea.label() = values.joinToString("+") { it.name }

private fun DoorArea.label() = values.joinToString("+") { it.name }

private fun Int.toArgbHex() = "#%08X".format(this)
