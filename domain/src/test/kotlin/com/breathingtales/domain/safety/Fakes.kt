package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.model.ZoneId
import com.breathingtales.domain.port.CabinCapabilities
import com.breathingtales.domain.port.SeatAxis
import com.breathingtales.domain.port.SeatMove
import com.breathingtales.domain.port.VehicleEffectPort
import com.breathingtales.domain.repository.VehicleStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

val DRIVER = ZoneId(1)
val PASSENGER = ZoneId(4)
val REAR_LEFT = ZoneId(16)
val REAR_RIGHT = ZoneId(64)

/** Pleos 에뮬레이터 확인값 (docs/dev/02_기술_확인.md 4장) */
val EMULATOR_CAPABILITIES = CabinCapabilities(
    fanSpeedRange = 1..8,
    temperatureRangeC = 15f..30f,
    temperatureStepC = 0.5f,
    ambientBrightnessRange = 0..100,
    seatHeatZones = listOf(REAR_LEFT, REAR_RIGHT),
    seatHeatMaxLevel = 3,
)

val PARKED = VehicleState(
    uxRestricted = false,
    gear = Gear.PARK,
    speedMps = 0f,
    doorsClosed = true,
    parkingBrakeOn = false,
)

class FakeVehicleStates(initial: VehicleState = PARKED) : VehicleStateRepository {
    override val state = MutableStateFlow(initial)
}

/** 호출을 문자열로 기록한다. [failing]에 든 접두어로 시작하는 호출은 예외를 던진다 */
class RecordingPort(
    var capabilities: CabinCapabilities = EMULATOR_CAPABILITIES,
    var snapshot: CabinSnapshot = CabinSnapshot(),
    /** 공조 전원을 켠 뒤 읽히는 상태 (꺼져 있을 때는 공조 값이 비어 있음) */
    var snapshotAfterPowerOn: CabinSnapshot? = null,
) : VehicleEffectPort {
    val calls = mutableListOf<String>()
    val failing = mutableSetOf<String>()

    private suspend fun record(call: String) {
        yield() // 실제 IPC처럼 중단 지점을 둔다 (취소 검사)
        calls += call
        if (failing.any { call.startsWith(it) }) error("실패: $call")
    }

    override suspend fun capabilities() = capabilities
    override suspend fun readSnapshot() = snapshot
    override suspend fun setHvacPower(on: Boolean) {
        record("hvacPower=$on")
        if (on) snapshotAfterPowerOn?.let { snapshot = it }
    }
    override suspend fun setFanSpeed(speed: Int) = record("fanSpeed=$speed")
    override suspend fun setAirflow(airflow: Airflow) = record("airflow=$airflow")
    override suspend fun setTemperature(zone: ZoneId, celsius: Float) = record("temperature[${zone.raw}]=$celsius")
    override suspend fun setAmbientMode(mode: AmbientMode) = record("ambientMode=$mode")
    override suspend fun setAmbientColor(rgb: Int) = record("ambientColor=%06X".format(rgb))
    override suspend fun setAmbientBrightness(brightness: Int) = record("ambientBrightness=$brightness")
    override suspend fun setAmbientAutoByDriveMode(enabled: Boolean) = record("ambientAuto=$enabled")
    override suspend fun setSeatHeat(zone: ZoneId, level: Int) = record("seatHeat[${zone.raw}]=$level")
    override suspend fun setCabinLight(switch: LampSwitch) = record("cabinLight=$switch")
    override suspend fun setReadingLight(zone: ZoneId, switch: LampSwitch) = record("readingLight[${zone.raw}]=$switch")

    // 시트 높이: [now] 시계로 [seatSpeedPerSec]씩 움직이는 시트. [seatUnreadable]이면 위치를 읽지 못한다(에뮬레이터 SDK의 늘 0을 어댑터가 null로 바꾼 경우)
    var now: () -> Long = { 0L }
    val seatHeights = mutableMapOf<ZoneId, Int>()
    /** 방석 기울기 위치. 높이와 같은 속도·지연으로 움직인다 */
    val seatTilts = mutableMapOf<ZoneId, Int>()
    /** 기울기만 위치를 읽지 못한다 */
    var seatTiltUnreadable = false
    var seatSpeedPerSec = 50
    var seatUnreadable = false
    val occupiedAndBuckled = mutableSetOf<ZoneId>()
    /** 0보다 크면 움직이기 시작하고 이 간격마다 한 칸씩 움직인다 (에뮬레이터: 100ms마다 5, 박자는 움직일 때마다 새로 셈) */
    var seatTickMs = 0L
    /** 명령(UP·DOWN·STOP)이 시트에 닿기까지 걸리는 시간. STOP을 보낸 뒤에도 그동안은 계속 움직인다 */
    var seatCommandLagMs = 0L
    /** 축마다 첫 명령은 시트에 닿고(움직이기 시작하고) 이만큼 지나서야 응답이 온다 (에뮬레이터 첫 명령 약 0.9초, 2026-11-12) */
    var seatFirstReplyLagMs = 0L
    /** 시트 명령을 보낸 시각과 명령 (응답을 기다리기 전) */
    val seatCommandTimes = mutableListOf<Pair<Long, String>>()
    private val seatReplied = mutableSetOf<Pair<ZoneId, SeatAxis>>()
    /** STOP만 이만큼 더 늦게 닿는다 (명령마다 닿는 시간이 달라 실제로 움직이는 시간이 보낸 간격보다 길어지는 경우) */
    var seatStopExtraLagMs = 0L

    /** 영역·축별 (시작 위치, 시트에 닿는 시각 순 명령). 첫 명령 때 그때 위치를 시작 위치로 잡는다 */
    private val seatTimelines = mutableMapOf<Pair<ZoneId, SeatAxis>, Pair<Int, MutableList<Pair<Long, SeatMove>>>>()

    private fun stored(axis: SeatAxis) = if (axis == SeatAxis.HEIGHT) seatHeights else seatTilts

    /** 0부터 [until]까지 1ms마다 본 실제 시트 위치의 최저~최고. 읽기 사이에 지나친 것도 잡는다 */
    fun seatExtremes(zone: ZoneId, until: Long, axis: SeatAxis = SeatAxis.HEIGHT): IntRange {
        val positions = (0..until).map { positionAt(zone, axis, it) }
        return positions.min()..positions.max()
    }

    private fun positionAt(zone: ZoneId, axis: SeatAxis, t: Long): Int {
        val (base, commands) = seatTimelines[zone to axis] ?: return stored(axis)[zone] ?: 0
        var position = base
        var move = SeatMove.STOP
        var since = 0L
        for ((at, next) in commands) {
            if (at > t) break
            position = moved(position, move, since, at)
            move = next
            since = at
        }
        return moved(position, move, since, t)
    }

    private fun moved(from: Int, move: SeatMove, since: Long, until: Long): Int {
        if (move == SeatMove.STOP || until <= since) return from
        val delta = if (seatTickMs > 0) {
            ((until - since) / seatTickMs * seatTickMs * seatSpeedPerSec / 1000).toInt()
        } else {
            ((until - since) * seatSpeedPerSec / 1000).toInt()
        }
        return (if (move == SeatMove.UP) from + delta else from - delta).coerceIn(0, 100)
    }

    override suspend fun readSeatPosition(zone: ZoneId, axis: SeatAxis): Int? {
        if (seatUnreadable || (axis == SeatAxis.TILT && seatTiltUnreadable)) return null
        if (axis == SeatAxis.TILT && zone !in seatTilts && (zone to axis) !in seatTimelines) return null
        return positionAt(zone, axis, now()).also { stored(axis)[zone] = it }
    }

    override suspend fun isSeatOccupiedAndBuckled(zone: ZoneId) = zone in occupiedAndBuckled

    override suspend fun moveSeat(zone: ZoneId, axis: SeatAxis, move: SeatMove) {
        val call = "${if (axis == SeatAxis.HEIGHT) "seatHeight" else "seatTilt"}[${zone.raw}]=$move"
        seatCommandTimes += now() to call
        record(call)
        val timeline = seatTimelines.getOrPut(zone to axis) { (stored(axis)[zone] ?: 0) to mutableListOf() }
        val extra = if (move == SeatMove.STOP) seatStopExtraLagMs else 0L
        timeline.second += (now() + seatCommandLagMs + extra) to move
        timeline.second.sortBy { it.first }
        if (seatReplied.add(zone to axis) && seatFirstReplyLagMs > 0) delay(seatFirstReplyLagMs)
    }
}
