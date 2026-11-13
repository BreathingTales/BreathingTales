package com.breathingtales.data.vehicle

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import android.util.Log
import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.model.ZoneId
import com.breathingtales.domain.port.CabinCapabilities
import com.breathingtales.domain.port.SeatAxis
import com.breathingtales.domain.port.SeatMove
import com.breathingtales.domain.port.VehicleEffectPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vehicle SDK 2.3.2로 공조·조명·시트를 제어한다 (docs/dev/02_기술_확인.md 4장).
 * 제어 영역은 하드코딩하지 않고 `check…Capabilities`로 받는다. 기능마다 영역 구성이 다르다
 * (공조 전원은 전 좌석 공용, 풍량은 앞좌석 공용, 온도는 운전석·조수석 따로).
 * 범위 제한은 SafetyGuard가 끝낸 값만 들어오므로 여기서는 하지 않는다. 지원하지 않는 기능은 로그만 남기고 건너뛴다.
 */
@Singleton
class PleosVehicleAdapter @Inject constructor(
    pleos: PleosVehicle,
    private val emulator: EmulatorSeatState,
) : VehicleEffectPort {

    private val vehicle = pleos.vehicle
    private val hvac get() = vehicle.getHVAC()
    private val light get() = vehicle.getLight()
    private val seat get() = vehicle.getSeat()

    private class Areas(
        val power: List<SeatArea>,
        val fan: SeatArea?,
        val temperatures: Map<ZoneId, SeatArea>,
        val ambient: SeatArea?,
        val rearSeats: Map<ZoneId, SeatArea>,
        val liftSeats: Map<ZoneId, SeatArea>,
        /** [liftSeats] 중 방석 기울기 이동·위치 읽기도 되는 좌석 */
        val tiltSeats: Set<ZoneId>,
        val readingLights: Map<ZoneId, SeatArea>,
        val capabilities: CabinCapabilities,
    )

    private val areasMutex = Mutex()
    private var cachedAreas: Areas? = null

    /** 주행모드 자동색은 차종에 따라 속성이 없다 (Pleos 에뮬레이터 EV 구성: not supported). 한 번 실패하면 다시 부르지 않는다 */
    @Volatile
    private var autoColorSupported = true

    /** 전원 이벤트로 받은 실제 공조 전원 상태 */
    private val hvacPower = MutableStateFlow<Boolean?>(null)

    private val powerListener = object : HvacPowerStateListener {
        override fun onHvacPowerStateUpdated(state: Boolean?) {
            hvacPower.value = state
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "공조 전원 구독 실패: ${e.message}")
        }
    }

    override suspend fun capabilities(): CabinCapabilities = areas().capabilities

    override suspend fun readSnapshot(): CabinSnapshot {
        val a = areas()
        val powerOn = a.power.firstOrNull()?.let { readPower(it) }
        return CabinSnapshot(
            hvacPowerOn = powerOn,
            // 전원이 꺼져 있으면 NOT_AVAILABLE이라 null이 된다. SafetyGuard가 전원을 켠 뒤 온도를 다시 읽는다
            fanSpeed = a.fan?.let { area ->
                orNull("풍량 읽기") { pleosCall<Int?>("풍량 읽기") { ok, ng -> hvac.getHvacFanSpeed(area, ok, ng) } }
            },
            airflow = a.fan?.let { area ->
                orNull("풍향 읽기") {
                    pleosCall<FanDirectionType?>("풍향 읽기") { ok, ng -> hvac.getHvacFanDirection(area, ok, ng) }
                }
            }?.toAirflow(),
            temperaturesC = if (powerOn == true) readTemperatures() else emptyMap(),
            ambientMode = a.ambient?.let { area ->
                orNull("조명 스위치 읽기") {
                    pleosCall<LightSwitch?>("조명 스위치 읽기") { ok, ng -> light.getAmbientLightSwitch(area, ok, ng) }
                }
            }?.toAmbientMode(),
            // 읽으면 알파 없이 RGB만 온다
            ambientColorRgb = a.ambient?.let { area ->
                orNull("조명 색 읽기") {
                    pleosCall<Int?>("조명 색 읽기") { ok, ng -> light.getAmbientLightBrightnessColor(area, ok, ng) }
                }
            }?.and(0xFFFFFF),
            ambientBrightness = a.ambient?.let { area ->
                orNull("조명 밝기 읽기") {
                    pleosCall<Int?>("조명 밝기 읽기") { ok, ng -> light.getAmbientLightBrightness(area, ok, ng) }
                }
            },
            ambientAutoByDriveMode = a.ambient?.takeIf { autoColorSupported }?.let {
                orNull("주행모드 자동색 읽기") {
                    pleosCall<Boolean?>("주행모드 자동색 읽기") { ok, ng -> light.getAmbientColorAutoByDriveModeEnabled(ok, ng) }
                }
            },
            seatHeatLevels = a.rearSeats.mapNotNull { (zone, area) ->
                orNull("열선 읽기") {
                    pleosCall<Int?>("열선 읽기") { ok, ng -> seat.getHvacSeatTemperature(area, ok, ng) }
                }?.let { zone to it }
            }.toMap(),
            cabinLight = if (a.capabilities.cabinLight) {
                orNull("실내등 읽기") {
                    pleosCall<LightSwitch?>("실내등 읽기") { ok, ng -> light.getCabinLightSwitch(ok, ng) }
                }?.toLampSwitch()
            } else null,
            readingLights = a.readingLights.mapNotNull { (zone, area) ->
                orNull("독서등 읽기") {
                    pleosCall<LightSwitch?>("독서등 읽기") { ok, ng -> light.getReadingLightSwitch(area, ok, ng) }
                }?.toLampSwitch()?.let { zone to it }
            }.toMap(),
        )
    }

    private suspend fun readTemperatures(): Map<ZoneId, Float> =
        areas().temperatures.mapNotNull { (zone, area) ->
            orNull("온도 읽기") {
                pleosCall<Float?>("온도 읽기") { ok, ng -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, ng) }
            }?.let { zone to it }
        }.toMap()

    /**
     * set 응답(onComplete)은 요청을 받았다는 뜻일 뿐이라, 전원 이벤트로 실제로 바뀐 것을 확인한 뒤 돌아온다
     * (02 문서 4장 구현 규칙 2).
     */
    override suspend fun setHvacPower(on: Boolean) {
        val a = areas()
        val area = a.power.firstOrNull() ?: return skip("공조 전원")
        if (readPower(area) == on) return
        a.power.forEach { powerArea ->
            pleosSet("공조 전원 $on") { ok, ng -> hvac.setHvacPower(powerArea, on, ok, ng) }
        }
        withTimeoutOrNull(POWER_EVENT_TIMEOUT_MS) { hvacPower.first { it == on } }
            ?: throw VehicleCallException(
                "공조 전원이 ${POWER_EVENT_TIMEOUT_MS}ms 안에 $on 이 되지 않음 (Virtual Console AVD=Enabled 확인)",
            )
    }

    override suspend fun setFanSpeed(speed: Int) {
        val area = areas().fan ?: return skip("풍량")
        pleosSet("풍량 $speed") { ok, ng -> hvac.setHvacFanSpeed(area, speed, ok, ng) }
    }

    override suspend fun setAirflow(airflow: Airflow) {
        val area = areas().fan ?: return skip("풍향")
        // 에뮬레이터는 지원 목록 조회 결과가 비어 있지만 설정은 된다 (02 문서 4장)
        pleosSet("풍향 $airflow") { ok, ng -> hvac.setHvacFanDirection(area, FanDirectionType.valueOf(airflow.name), ok, ng) }
    }

    override suspend fun setTemperature(zone: ZoneId, celsius: Float) {
        val area = areas().temperatures[zone] ?: return skip("온도 영역 ${zone.raw}")
        pleosSet("온도 $celsius") { ok, ng -> hvac.setHvacTemperature(area, celsius, TemperatureUnit.CELSIUS, ok, ng) }
    }

    override suspend fun setAmbientMode(mode: AmbientMode) {
        val area = areas().ambient ?: return skip("조명 스위치")
        val value = when (mode) {
            AmbientMode.OFF -> LightSwitch.OFF
            AmbientMode.ON -> LightSwitch.ON
            AmbientMode.AUTO -> LightSwitch.AUTOMATIC
        }
        pleosSet("조명 스위치 $mode") { ok, ng -> light.setAmbientLightSwitch(area, value, ok, ng) }
    }

    override suspend fun setAmbientColor(rgb: Int) {
        val area = areas().ambient ?: return skip("조명 색")
        val argb = OPAQUE or (rgb and 0xFFFFFF)
        pleosSet("조명 색") { ok, ng -> light.setAmbientLightBrightnessColor(area, argb, ok, ng) }
    }

    override suspend fun setAmbientBrightness(brightness: Int) {
        val area = areas().ambient ?: return skip("조명 밝기")
        pleosSet("조명 밝기 $brightness") { ok, ng -> light.setAmbientLightBrightness(area, brightness, ok, ng) }
    }

    override suspend fun setAmbientAutoByDriveMode(enabled: Boolean) {
        if (areas().ambient == null || !autoColorSupported) return skip("주행모드 자동색")
        try {
            pleosCall<Unit>("주행모드 자동색 $enabled") { ok, ng ->
                light.setAmbientColorAutoByDriveModeEnabled(enabled, { ok(Unit) }, ng)
            }
        } catch (e: VehicleCallException) {
            if (e.message?.contains(NOT_SUPPORTED) != true) throw e
            autoColorSupported = false
            skip("주행모드 자동색")
        }
    }

    override suspend fun setSeatHeat(zone: ZoneId, level: Int) {
        val area = areas().rearSeats[zone] ?: return skip("열선 영역 ${zone.raw}")
        pleosSet("열선 $level") { ok, ng -> seat.setHvacSeatTemperature(area, level, ok, ng) }
    }

    override suspend fun setCabinLight(switch: LampSwitch) {
        if (!areas().capabilities.cabinLight) return skip("실내등")
        pleosCall<Unit>("실내등 $switch") { ok, ng -> light.setCabinLightSwitch(switch.toLightSwitch(), { ok(Unit) }, ng) }
    }

    override suspend fun setReadingLight(zone: ZoneId, switch: LampSwitch) {
        val area = areas().readingLights[zone] ?: return skip("독서등 영역 ${zone.raw}")
        pleosCall<Unit>("독서등 $switch") { ok, ng -> light.setReadingLightSwitch(area, switch.toLightSwitch(), { ok(Unit) }, ng) }
    }

    override suspend fun readSeatPosition(zone: ZoneId, axis: SeatAxis): Int? {
        val areas = areas()
        val area = areas.liftSeats[zone] ?: return null
        if (axis == SeatAxis.TILT && zone !in areas.tiltSeats) return null
        val name = axis.label()
        val sdk = orNull("$name 읽기") {
            pleosCall<Int?>("$name 읽기") { ok, ng ->
                when (axis) {
                    SeatAxis.HEIGHT -> seat.getCurrentSeatHeightPosition(area, ok, ng)
                    SeatAxis.TILT -> seat.getCurrentSeatTiltPosition(area, ok, ng)
                }
            }
        }
        // Pleos 에뮬레이터: SDK는 늘 0을 돌려주므로 Virtual Console의 실제 위치로 대신 읽는다 (실차에서는 꺼짐)
        if ((sdk == null || sdk == 0) && emulator.enabled()) {
            EmulatorSeatState.seatKey(area.values.map { it.name })?.let { key -> emulator.position(key, axis)?.let { return it } }
        }
        // 에뮬레이터 SDK의 0은 실제 위치가 아니다. 읽지 못한 것으로 돌려줘 SafetyGuard가 건너뛰게 한다 (실차의 0은 진짜 맨 아래)
        if (sdk == 0 && EmulatorSeatState.isEmulator) return null
        return sdk
    }

    override suspend fun isSeatOccupiedAndBuckled(zone: ZoneId): Boolean {
        val area = areas().liftSeats[zone] ?: return false
        val occupied = orNull("착석 읽기") {
            pleosCall<Boolean?>("착석 읽기") { ok, ng -> seat.getCurrentSeatOccupancy(area, ok, ng) }
        }
        val buckled = orNull("안전벨트 읽기") {
            pleosCall<Boolean?>("안전벨트 읽기") { ok, ng -> seat.getCurrentSeatBeltBuckledState(area, ok, ng) }
        }
        if (occupied == true && buckled == true) return true
        // Pleos 에뮬레이터: 안전벨트 상태가 없어(SDK는 늘 false) Virtual Console의 착석만 본다 (실차에서는 꺼짐)
        if (emulator.enabled()) {
            return EmulatorSeatState.seatKey(area.values.map { it.name })?.let { emulator.occupied(it) } == true
        }
        return false
    }

    /** 높이·기울기 모두 FORWARD가 값이 커지는 방향 (02 문서 4장, 에뮬레이터 확인) */
    override suspend fun moveSeat(zone: ZoneId, axis: SeatAxis, move: SeatMove) {
        val areas = areas()
        val area = areas.liftSeats[zone]?.takeIf { axis == SeatAxis.HEIGHT || zone in areas.tiltSeats }
            ?: return skip("${axis.label()} 영역 ${zone.raw}")
        val control = when (move) {
            SeatMove.UP -> MoveControl.FORWARD
            SeatMove.DOWN -> MoveControl.BACKWARD
            SeatMove.STOP -> MoveControl.STOP
        }
        pleosSet("${axis.label()} $move") { ok, ng ->
            when (axis) {
                SeatAxis.HEIGHT -> seat.moveSeatHeight(area, control, ok, ng)
                SeatAxis.TILT -> seat.moveSeatTilt(area, control, ok, ng)
            }
        }
    }

    private suspend fun readPower(area: SeatArea): Boolean? =
        orNull("공조 전원 읽기") {
            pleosCall<Boolean?>("공조 전원 읽기") { ok, ng -> hvac.getHvacPowerState(area, ok, ng) }
        }?.also { hvacPower.value = it }

    /** 지원 범위는 한 번만 조회한다. 서비스 연결 전이라 공조 전원 영역 조회가 실패하면 저장하지 않고 다음에 다시 조회한다 */
    private suspend fun areas(): Areas = areasMutex.withLock {
        cachedAreas ?: loadAreas().also { cachedAreas = it }
    }

    private suspend fun loadAreas(): Areas {
        val power = retrying {
            pleosCall<List<SeatArea>>("공조 전원 영역") { ok, ng -> hvac.checkHvacPowerCapabilities({ _, areas -> ok(areas) }, ng) }
        }
        power.firstOrNull()?.let { hvac.registerHvacPowerState(it, powerListener) }

        val fan = orNull("풍량 범위") {
            pleosCall<Map<SeatArea, AreaBounds<Int>>>("풍량 범위") { ok, ng -> hvac.checkHvacFanSpeedCapabilities({ _, map -> ok(map) }, ng) }
        }?.entries?.firstOrNull()

        val temperature = orNull("온도 범위") {
            pleosCall<Pair<Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>>("온도 범위") { ok, ng ->
                hvac.checkHvacTemperatureCapabilities(
                    { _: CapabilityConfig, map: Map<SeatArea, AreaBounds<Float>>, config: HvacTemperatureConfigArray? ->
                        ok(map to config)
                    },
                    ng,
                )
            }
        }
        val temperatureAreas = temperature?.first.orEmpty()
        val temperatureConfig = temperature?.second

        val ambient = orNull("조명 밝기 범위") {
            pleosCall<Map<SeatArea, AreaBounds<Int>>>("조명 밝기 범위") { ok, ng ->
                light.checkAmbientLightBrightnessCapabilities({ _, map -> ok(map) }, ng)
            }
        }?.entries?.firstOrNull()

        // 뒷좌석(ROW_2)만 연출에 쓴다. 앞좌석은 -3~3(냉방 포함)이라 범위도 다르다
        val rearSeats = orNull("열선 범위") {
            pleosCall<Map<SeatArea, AreaBounds<Int>>>("열선 범위") { ok, ng -> seat.checkSeatTemperatureCapabilities({ _, map -> ok(map) }, ng) }
        }.orEmpty().filterKeys { area -> area.values.all { it.name.startsWith("ROW_2") } }

        // 시트 들썩임 (기획서 5장 7번): 운전석이 아닌 앞좌석만. 좌핸들 기준 조수석(ROW_1_RIGHT).
        // 높이 이동·위치·착석·벨트를 모두 지원하는 영역만 쓴다
        val moveHeight = orNull("시트 높이 이동 영역") {
            pleosCall<List<SeatArea>>("시트 높이 이동 영역") { ok, ng -> seat.checkMoveSeatHeightCapabilities({ _, list -> ok(list) }, ng) }
        }.orEmpty()
        val heightPositions = orNull("시트 높이 위치 범위") {
            pleosCall<Map<SeatArea, AreaBounds<Int>>>("시트 높이 위치 범위") { ok, ng ->
                seat.checkSeatHeightPositionCapabilities({ _, map -> ok(map) }, ng)
            }
        }.orEmpty().mapKeys { it.key.zoneId() }
        val occupancyZones = orNull("착석 감지 영역") {
            pleosCall<List<SeatArea>>("착석 감지 영역") { ok, ng -> seat.checkSeatOccupancyCapabilities({ _, list -> ok(list) }, ng) }
        }.orEmpty().map { it.zoneId() }.toSet()
        val buckleZones = orNull("안전벨트 영역") {
            pleosCall<List<SeatArea>>("안전벨트 영역") { ok, ng -> vehicle.getSafety().checkSeatBuckledCapabilities({ _, list -> ok(list) }, ng) }
        }.orEmpty().map { it.zoneId() }.toSet()
        val liftSeats = moveHeight
            .filter { area -> area.values.map { it.name } == listOf(FRONT_PASSENGER) }
            .filter { area -> area.zoneId().let { it in heightPositions && it in occupancyZones && it in buckleZones } }
            .associateBy { it.zoneId() }
        // 방석 기울기: 들썩일 좌석이 기울기 이동·위치 읽기도 지원하면 높이와 함께 움직인다.
        // 등받이(moveSeatBackrestAngle)는 뒷좌석 아이 쪽으로 움직이므로 쓰지 않는다 (기획서 5장 7번)
        val moveTilt = orNull("시트 기울기 이동 영역") {
            pleosCall<List<SeatArea>>("시트 기울기 이동 영역") { ok, ng -> seat.checkMoveSeatTiltCapabilities({ _, list -> ok(list) }, ng) }
        }.orEmpty().map { it.zoneId() }.toSet()
        val tiltPositions = orNull("시트 기울기 위치 범위") {
            pleosCall<Map<SeatArea, AreaBounds<Int>>>("시트 기울기 위치 범위") { ok, ng ->
                seat.checkSeatTiltPositionCapabilities({ _, map -> ok(map) }, ng)
            }
        }.orEmpty().mapKeys { it.key.zoneId() }
        val tiltSeats = liftSeats.keys.filter { it in moveTilt && it in tiltPositions }.toSet()
        // 실내등·독서등 (10 대본 재작성). 켜기·끄기를 모두 지원해야 쓴다. 독서등은 아이가 앉는 뒷좌석(ROW_2)만
        val cabinLight = orNull("실내등 지원") {
            pleosCall<List<LightSwitch>>("실내등 지원") { ok, ng -> light.checkCabinLightSwitchCapabilities({ _, list -> ok(list) }, ng) }
        }.orEmpty().containsAll(ON_OFF)
        val readingLights = orNull("독서등 지원") {
            pleosCall<Map<SeatArea, List<LightSwitch>>>("독서등 지원") { ok, ng ->
                light.checkReadingLightSwitchCapabilities({ _, map -> ok(map) }, ng)
            }
        }.orEmpty()
            .filter { (area, switches) -> area.values.all { it.name.startsWith("ROW_2") } && switches.containsAll(ON_OFF) }
            .keys.associateBy { it.zoneId() }

        val heightRange = liftSeats.keys.firstOrNull()?.let { heightPositions[it] }?.let { it.minValue..it.maxValue }
        val tiltRange = liftSeats.keys.firstOrNull()?.takeIf { it in tiltSeats }?.let { tiltPositions[it] }?.let { it.minValue..it.maxValue }

        val capabilities = CabinCapabilities(
            fanSpeedRange = fan?.value?.let { it.minValue..it.maxValue },
            temperatureRangeC = temperatureConfig?.let { it.minCelsius..it.maxCelsius }
                ?: temperatureAreas.values.takeIf { it.isNotEmpty() }
                    ?.let { bounds -> bounds.maxOf { it.minValue }..bounds.minOf { it.maxValue } },
            temperatureStepC = temperatureConfig?.incrementCelsius?.takeIf { it > 0f } ?: DEFAULT_TEMPERATURE_STEP_C,
            ambientBrightnessRange = ambient?.value?.let { it.minValue..it.maxValue },
            seatHeatZones = rearSeats.keys.map { it.zoneId() },
            seatHeatMaxLevel = rearSeats.values.minOfOrNull { it.maxValue } ?: 0,
            seatLiftZones = liftSeats.keys.toList(),
            seatHeightRange = heightRange,
            seatTiltRange = tiltRange,
            cabinLight = cabinLight,
            readingLightZones = readingLights.keys.toList(),
        )
        Log.i(TAG, "지원 범위: $capabilities / 전원 영역 ${power.size}개")
        return Areas(
            power = power,
            fan = fan?.key,
            temperatures = temperatureAreas.keys.associateBy { it.zoneId() },
            ambient = ambient?.key,
            rearSeats = rearSeats.keys.associateBy { it.zoneId() },
            liftSeats = liftSeats,
            tiltSeats = tiltSeats,
            readingLights = readingLights,
            capabilities = capabilities,
        )
    }

    private fun SeatAxis.label() = when (this) {
        SeatAxis.HEIGHT -> "시트 높이"
        SeatAxis.TILT -> "시트 기울기"
    }

    private fun skip(what: String) {
        Log.i(TAG, "$what: 이 차량에서 지원하지 않아 건너뜀")
    }

    private companion object {
        const val POWER_EVENT_TIMEOUT_MS = 3_000L
        const val DEFAULT_TEMPERATURE_STEP_C = 0.5f
        const val OPAQUE = 0xFF shl 24
        const val NOT_SUPPORTED = "not supported"
        const val FRONT_PASSENGER = "ROW_1_RIGHT"
        val ON_OFF = listOf(LightSwitch.ON, LightSwitch.OFF)
    }
}

/** AAOS 영역 비트를 합친 값. 같은 좌석 구성이면 같은 값이 나온다 */
private fun SeatArea.zoneId() = ZoneId(values.fold(0) { acc, value -> acc or value.id })

private fun FanDirectionType.toAirflow(): Airflow? = Airflow.entries.firstOrNull { it.name == name }

private fun LightSwitch.toAmbientMode(): AmbientMode? = when (this) {
    LightSwitch.OFF -> AmbientMode.OFF
    LightSwitch.ON -> AmbientMode.ON
    LightSwitch.AUTOMATIC -> AmbientMode.AUTO
    else -> null
}

private fun LightSwitch.toLampSwitch(): LampSwitch? = when (this) {
    LightSwitch.OFF -> LampSwitch.OFF
    LightSwitch.ON -> LampSwitch.ON
    LightSwitch.AUTOMATIC -> LampSwitch.AUTO
    else -> null
}

private fun LampSwitch.toLightSwitch(): LightSwitch = when (this) {
    LampSwitch.OFF -> LightSwitch.OFF
    LampSwitch.ON -> LightSwitch.ON
    LampSwitch.AUTO -> LightSwitch.AUTOMATIC
}
