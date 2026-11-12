package com.breathingtales.data.vehicle.fake

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.model.ZoneId
import com.breathingtales.domain.port.CabinCapabilities
import com.breathingtales.domain.port.SeatAxis
import com.breathingtales.domain.port.SeatMove
import com.breathingtales.domain.port.VehicleEffectPort
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 메모리 차량. 에뮬레이터 없이 엔진(07)·UI(09)를 개발하고 테스트할 때 쓴다.
 * Pleos 에뮬레이터와 같은 범위·영역을 갖고, 공조 전원이 꺼져 있으면 풍량·풍향·온도를 읽거나 바꿀 수 없다
 * (docs/dev/02_기술_확인.md 4장).
 */
@Singleton
class FakeVehicleEffectPort @Inject constructor() : VehicleEffectPort {

    private val _cabin = MutableStateFlow(INITIAL_CABIN)

    /** 현재 실내 상태. 디버그 화면에서 보여 준다 */
    val cabin: StateFlow<CabinSnapshot> = _cabin.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** 받은 제어 명령 순서대로 */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** 디버그 화면에서 시작 전 실내 상태를 바꿀 때 */
    fun setCabin(cabin: CabinSnapshot) {
        _cabin.value = cabin
    }

    override suspend fun capabilities() = CAPABILITIES

    override suspend fun readSnapshot(): CabinSnapshot {
        val cabin = _cabin.value
        return if (cabin.hvacPowerOn == true) cabin
        else cabin.copy(fanSpeed = null, airflow = null, temperaturesC = emptyMap())
    }

    override suspend fun setHvacPower(on: Boolean) = change("hvacPower=$on") { it.copy(hvacPowerOn = on) }

    override suspend fun setFanSpeed(speed: Int) {
        requirePower("풍량")
        change("fanSpeed=$speed") { it.copy(fanSpeed = speed) }
    }

    override suspend fun setAirflow(airflow: Airflow) {
        requirePower("풍향")
        change("airflow=$airflow") { it.copy(airflow = airflow) }
    }

    override suspend fun setTemperature(zone: ZoneId, celsius: Float) {
        requirePower("온도")
        change("temperature[${zone.raw}]=$celsius") { it.copy(temperaturesC = it.temperaturesC + (zone to celsius)) }
    }

    override suspend fun setAmbientMode(mode: AmbientMode) = change("ambientMode=$mode") { it.copy(ambientMode = mode) }

    override suspend fun setAmbientColor(rgb: Int) =
        change("ambientColor=%06X".format(rgb)) { it.copy(ambientColorRgb = rgb and 0xFFFFFF) }

    override suspend fun setAmbientBrightness(brightness: Int) =
        change("ambientBrightness=$brightness") { it.copy(ambientBrightness = brightness) }

    override suspend fun setAmbientAutoByDriveMode(enabled: Boolean) =
        change("ambientAuto=$enabled") { it.copy(ambientAutoByDriveMode = enabled) }

    override suspend fun setSeatHeat(zone: ZoneId, level: Int) =
        change("seatHeat[${zone.raw}]=$level") { it.copy(seatHeatLevels = it.seatHeatLevels + (zone to level)) }

    override suspend fun setCabinLight(switch: LampSwitch) = change("cabinLight=$switch") { it.copy(cabinLight = switch) }

    override suspend fun setReadingLight(zone: ZoneId, switch: LampSwitch) =
        change("readingLight[${zone.raw}]=$switch") { it.copy(readingLights = it.readingLights + (zone to switch)) }

    // 시트 높이·기울기: 위치를 읽을 때마다 움직이는 방향으로 SEAT_STEP씩 간다 (시계 없이 결정적으로)
    // SafetyGuard가 두 축을 동시에 움직이므로 여러 스레드에서 불릴 수 있다
    private val seatPositions = ConcurrentHashMap(mapOf((PASSENGER to SeatAxis.HEIGHT) to 40, (PASSENGER to SeatAxis.TILT) to 20))
    private val seatMoving = ConcurrentHashMap<Pair<ZoneId, SeatAxis>, SeatMove>()

    override suspend fun readSeatPosition(zone: ZoneId, axis: SeatAxis): Int? {
        val key = zone to axis
        val current = seatPositions[key] ?: return null
        val moving = seatMoving[key] ?: return current
        val next = (if (moving == SeatMove.UP) current + SEAT_STEP else current - SEAT_STEP).coerceIn(SEAT_RANGE)
        seatPositions[key] = next
        return next
    }

    override suspend fun isSeatOccupiedAndBuckled(zone: ZoneId) = zone == PASSENGER

    override suspend fun moveSeat(zone: ZoneId, axis: SeatAxis, move: SeatMove) {
        val key = zone to axis
        if (move == SeatMove.STOP) seatMoving.remove(key) else seatMoving[key] = move
        val name = if (axis == SeatAxis.HEIGHT) "seatHeight" else "seatTilt"
        _log.update { it + "$name[${zone.raw}]=$move" }
    }

    private fun requirePower(what: String) {
        check(_cabin.value.hvacPowerOn == true) { "$what: NOT_AVAILABLE (공조 전원 꺼짐)" }
    }

    private fun change(entry: String, transform: (CabinSnapshot) -> CabinSnapshot) {
        _cabin.update(transform)
        _log.update { it + entry }
    }

    companion object {
        val DRIVER = ZoneId(0x01)
        val PASSENGER = ZoneId(0x04)
        val REAR_LEFT = ZoneId(0x10)
        val REAR_RIGHT = ZoneId(0x40)

        val CAPABILITIES = CabinCapabilities(
            fanSpeedRange = 1..8,
            temperatureRangeC = 15f..30f,
            temperatureStepC = 0.5f,
            ambientBrightnessRange = 0..100,
            seatHeatZones = listOf(REAR_LEFT, REAR_RIGHT),
            seatHeatMaxLevel = 3,
            seatLiftZones = listOf(PASSENGER),
            seatHeightRange = 0..100,
            seatTiltRange = 0..100,
            cabinLight = true,
            spotlightZones = listOf(REAR_LEFT, REAR_RIGHT),
        )

        private val SEAT_RANGE = 0..100
        private const val SEAT_STEP = 3

        /** 에뮬레이터 기본 상태처럼 공조 전원이 꺼진 채로 시작한다 */
        val INITIAL_CABIN = CabinSnapshot(
            hvacPowerOn = false,
            fanSpeed = 3,
            airflow = Airflow.FACE_AND_FLOOR,
            temperaturesC = mapOf(DRIVER to 22f, PASSENGER to 22.5f),
            ambientMode = AmbientMode.AUTO,
            ambientColorRgb = 0xFFFFFF,
            ambientBrightness = 50,
            ambientAutoByDriveMode = true,
            seatHeatLevels = mapOf(REAR_LEFT to 0, REAR_RIGHT to 0),
            cabinLight = LampSwitch.OFF,
            readingLights = mapOf(REAR_LEFT to LampSwitch.OFF, REAR_RIGHT to LampSwitch.OFF),
        )
    }
}
