package com.breathingtales.domain.safety

import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.model.ZoneId
import com.breathingtales.domain.port.CabinCapabilities
import com.breathingtales.domain.port.SeatAxis
import com.breathingtales.domain.port.SeatMove
import com.breathingtales.domain.port.VehicleEffectPort
import com.breathingtales.domain.repository.VehicleStateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 모든 차량 제어가 지나는 단일 관문 (기획서 5장, 8-2).
 * [begin]에서 실내 상태를 저장하고, [apply]로 효과를 허용 범위 안의 값으로 내보내고, [restore]로 되돌린다.
 * 정차가 아니면 시작하지도 제어하지도 않는다. 정차 해제를 감지해 중단하는 일은 엔진(07)이
 * [com.breathingtales.domain.usecase.ObserveParkedStateUseCase.duringShow]로 한다.
 */
@Singleton
class SafetyGuard(
    private val port: VehicleEffectPort,
    private val vehicleStates: VehicleStateRepository,
    private val limits: SafetyLimits,
    /**
     * 시트 이동 시간을 재는 단조 시계(ms). 시트 명령은 응답이 오기 전부터 움직이므로(첫 명령은 응답까지 약 0.9초,
     * 02 문서 4장) 기다린 시간만 세면 시간 상한을 넘긴다. 테스트는 가상 시계를 넣는다
     */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Inject
    constructor(port: VehicleEffectPort, vehicleStates: VehicleStateRepository) :
        this(port, vehicleStates, SafetyLimits())

    private class Session(
        val snapshot: CabinSnapshot,
        val capabilities: CabinCapabilities,
        val policy: SafetyPolicy,
        val allowSeatMotion: Boolean,
    ) {
        var ambientReady = false

        /** 연출이 마지막으로 보낸 무드등 값 (차량 단위). 보낸 적이 없으면 null */
        var ambientShown: AmbientValue? = null

        /** 시트 연출은 연출마다 한 번만 (그 한 번 안에서 들썩임을 되풀이할 수 있다) */
        var seatLiftUsed = false

        /** 올린 시트를 제자리로 되돌리지 못했으면 그 영역과 축 */
        var seatLiftUnrestored: Pair<ZoneId, Set<SeatAxis>>? = null

        /** 연출이 바꾼 조명만 되돌린다 (시작 상태가 AUTO면 다시 쓰는 것만으로도 차량 동작이 바뀔 수 있다) */
        var cabinLightTouched = false
        val readingLightsTouched = mutableSetOf<ZoneId>()
    }

    /**
     * 복원하지 못한 등 스위치의 시작 상태. 차는 기어가 P가 아니면 등 스위치 명령을 거부하므로
     * (Pleos 에뮬레이터 2026-11-10 확인: `IllegalArgumentException: 50001`, 공조·무드등은 받음)
     * 기어를 바꿔 중단하면 등만 남는다. 다시 정차하면 [watchPendingRestore]가, 그 전에 다음 연출이 시작되면 [begin]이 되돌린다.
     */
    private class PendingLamps(
        var cabinLight: LampSwitch? = null,
        val readingLights: MutableMap<ZoneId, LampSwitch> = mutableMapOf(),
    ) {
        fun names(): List<String> =
            listOfNotNull(cabinLight?.let { "cabinLight" }) + readingLights.keys.map { "readingLight[${it.raw}]" }
    }

    private val mutex = Mutex()
    private var session: Session? = null
    private var pendingLamps = PendingLamps()
    private val _pendingRestore = MutableStateFlow<List<String>>(emptyList())

    /** 복원하지 못해 다시 정차하기를 기다리는 항목 ([SafetyMonitor.restoreFailures]와 같은 이름). 되돌리면 빠진다 */
    val pendingRestore: StateFlow<List<String>> = _pendingRestore.asStateFlow()

    private val _monitor = MutableStateFlow(SafetyMonitor())

    /** 마지막으로 처리한 제어 (요청 값 → 보낸 값). 시연 HUD가 본다 (11) */
    val monitor: StateFlow<SafetyMonitor> = _monitor.asStateFlow()

    /**
     * 실내 상태를 저장하고 연출을 시작한다. 정차가 아니면 아무것도 바꾸지 않고 false.
     * 공조 전원이 꺼져 있으면 풍량·풍향·온도를 읽을 수 없으므로, 켠 직후 아무것도 바꾸기 전에 읽어
     * 복원 기준값으로 쓴다 (02 문서 4장). 이 값을 비워 두면 다음에 사용자가 공조를 켰을 때 연출 값이 남는다.
     * @param allowSeatMotion 부모 설정의 시트 움직임 허용. 꺼져 있으면 [Effect.SeatLift]를 무시한다 (기본 꺼짐)
     */
    suspend fun begin(intensity: Intensity, allowSeatMotion: Boolean = false): Boolean = mutex.withLock {
        check(session == null) { "이미 연출 중" }
        if (!isParked()) return false

        // 지난 연출이 남긴 등부터 되돌린다. 그래도 남으면 이번 시작 상태로 이어받아 이번 복원이 되돌린다
        // (지금 상태를 그대로 저장하면 지난 연출이 바꾼 값을 시작 상태로 착각한다)
        retryPendingLamps()
        val carried = pendingLamps
        pendingLamps = PendingLamps()
        _pendingRestore.value = emptyList()

        val capabilities = port.capabilities()
        var snapshot = port.readSnapshot()
        snapshot = snapshot.copy(
            cabinLight = carried.cabinLight ?: snapshot.cabinLight,
            readingLights = snapshot.readingLights + carried.readingLights,
        )
        if (snapshot.hvacPowerOn != true) {
            // 전원을 못 켜도 조명·시트 연출은 이어 간다. 기준 온도가 없으면 온도 효과만 건너뛴다
            if (attempt { port.setHvacPower(true) } != null) {
                attempt { port.readSnapshot() }?.let { powered ->
                    snapshot = snapshot.copy(
                        fanSpeed = snapshot.fanSpeed ?: powered.fanSpeed,
                        airflow = snapshot.airflow ?: powered.airflow,
                        temperaturesC = snapshot.temperaturesC.ifEmpty { powered.temperaturesC },
                    )
                }
            }
        }
        session = Session(snapshot, capabilities, SafetyPolicy(capabilities, intensity, limits), allowSeatMotion).apply {
            cabinLightTouched = carried.cabinLight != null
            readingLightsTouched += carried.readingLights.keys
        }
        _monitor.value = SafetyMonitor(active = true, intensity = intensity)
        true
    }

    /**
     * 효과 하나를 허용 범위 안의 값으로 바꿔 내보낸다. 시작 전·복원 뒤이거나 정차가 아니면,
     * 또는 차량이 지원하지 않아 바꿀 값이 없으면 아무것도 하지 않고 false.
     * 단계 효과(`…Step`)는 엔진이 절대값으로 바꿔서 보내고, 소리·복원은 이 함수로 보내지 않는다.
     */
    suspend fun apply(effect: Effect): Boolean = mutex.withLock {
        val session = session ?: return false
        if (!isParked()) return false
        val policy = session.policy

        when (effect) {
            is Effect.FanLevel -> {
                val speed = policy.fanSpeed(effect.level)
                speed?.let { port.setFanSpeed(it) }
                record { it.copy(fan = SafetyMonitor.FanControl(effect.level, speed, policy.fanSpeed(Effect.FanLevel.MAX))) }
                if (speed == null) return false
            }
            is Effect.FanDirection -> {
                port.setAirflow(effect.direction)
                record { it.copy(airflow = effect.direction) }
            }
            is Effect.TemperatureDelta -> {
                // 영역마다 시작 온도가 다를 수 있으므로 영역별 기준값에 더한다
                val zones = session.snapshot.temperaturesC.mapValues { (_, baseline) ->
                    baseline to policy.temperature(baseline, effect.deltaC)
                }
                val targets = zones.mapNotNull { (zone, pair) -> pair.second?.let { zone to it } }
                targets.forEach { (zone, celsius) -> port.setTemperature(zone, celsius) }
                record { it.copy(temperature = SafetyMonitor.TemperatureControl(effect.deltaC, zones)) }
                if (targets.isEmpty()) return false
            }
            is Effect.SeatHeat -> {
                val level = policy.seatHeat(effect.level)
                level?.let { session.capabilities.seatHeatZones.forEach { zone -> port.setSeatHeat(zone, it) } }
                record { it.copy(seatHeat = SafetyMonitor.SeatHeatControl(effect.level, level)) }
                if (level == null) return false
            }
            is Effect.Ambient -> {
                val brightness = policy.ambientBrightness(effect.brightness)
                if (brightness == null) {
                    record { it.copy(ambient = SafetyMonitor.AmbientControl(effect.colorRgb, effect.brightness, null)) }
                    return false
                }
                sendAmbient(session, AmbientValue(effect.colorRgb, brightness), force = true)
                record { it.copy(ambient = SafetyMonitor.AmbientControl(effect.colorRgb, effect.brightness, brightness)) }
            }
            is Effect.CabinLight -> {
                // 시작 상태를 읽은 경우만 바꾼다. 못 읽었으면 되돌릴 수 없다
                val ok = session.capabilities.cabinLight && session.snapshot.cabinLight != null
                if (ok) {
                    port.setCabinLight(if (effect.on) LampSwitch.ON else LampSwitch.OFF)
                    session.cabinLightTouched = true
                }
                record { it.copy(cabinLight = SafetyMonitor.LampControl(effect.on, ok)) }
                if (!ok) return false
            }
            is Effect.ReadingLight -> {
                val zones = session.capabilities.readingLightZones.filter { it in session.snapshot.readingLights }
                zones.forEach { zone ->
                    port.setReadingLight(zone, if (effect.on) LampSwitch.ON else LampSwitch.OFF)
                    session.readingLightsTouched += zone
                }
                record { it.copy(readingLight = SafetyMonitor.LampControl(effect.on, zones.isNotEmpty())) }
                if (zones.isEmpty()) return false
            }
            is Effect.SeatLift -> {
                val result = seatLift(session, effect)
                record { it.copy(seatLift = result) }
                return result.outcome == SeatLiftOutcome.DONE || result.outcome == SeatLiftOutcome.UNRESTORED
            }
            is Effect.FanLevelStep, is Effect.AmbientBrightnessStep, is Effect.Sound, is Effect.Restore ->
                throw IllegalArgumentException("SafetyGuard로 보내지 않는 효과: $effect")
        }
        true
    }

    /**
     * 지금 무드등에서 [target]으로 서서히 바뀌는 전환의 시작·끝 값 (`fadeSec`).
     * SDK에 전환 기능이 없어 엔진이 [applyAmbientFade]로 중간값을 나눠 보낸다.
     * 시작 값은 연출이 마지막으로 보낸 값, 없으면 시작 전 조명(켜져 있었을 때만), 그것도 없으면 가장 어두운 밝기다.
     * 연출 중이 아니거나 밝기를 지원하지 않으면 null.
     */
    suspend fun ambientFade(target: Effect.Ambient): AmbientFade? = mutex.withLock {
        val session = session ?: return null
        val range = session.capabilities.ambientBrightnessRange ?: return null
        val to = AmbientValue(target.colorRgb, session.policy.ambientBrightness(target.brightness) ?: return null)
        val from = session.ambientNow(range) ?: AmbientValue(target.colorRgb, range.first)
        AmbientFade(from, to, target)
    }

    /**
     * 시작 전 무드등으로 서서히 돌아가는 전환 (`restore`의 `fadeSec`). 시작 전에 꺼져 있었으면 가장 어둡게 낮춘다.
     * 실제 복원(스위치·자동색 포함)은 전환 뒤 [restore]가 한다. 연출이 조명을 바꾼 적이 없으면 null.
     */
    suspend fun ambientRestoreFade(): AmbientFade? = mutex.withLock {
        val session = session ?: return null
        val range = session.capabilities.ambientBrightnessRange ?: return null
        val from = session.ambientShown ?: return null
        val snapshot = session.snapshot
        val to = if (snapshot.ambientMode == AmbientMode.ON) {
            AmbientValue(snapshot.ambientColorRgb ?: from.colorRgb, (snapshot.ambientBrightness ?: range.first).coerceIn(range))
        } else {
            AmbientValue(from.colorRgb, range.first)
        }
        AmbientFade(from, to, requested = null)
    }

    /**
     * 전환 [fade]의 [progress](0~1) 지점 값을 보낸다. 직전에 보낸 값과 같은 항목은 보내지 않는다.
     * 연출 중이 아니거나 정차가 아니면 false.
     */
    suspend fun applyAmbientFade(fade: AmbientFade, progress: Float): Boolean = mutex.withLock {
        val session = session ?: return false
        if (!isParked()) return false
        val value = fade.at(progress)
        sendAmbient(session, value, force = false)
        fade.requested?.let { requested ->
            record { it.copy(ambient = SafetyMonitor.AmbientControl(requested.colorRgb, requested.brightness, value.brightness)) }
        }
        true
    }

    private suspend fun sendAmbient(session: Session, value: AmbientValue, force: Boolean) {
        if (!session.ambientReady) {
            // 주행 모드 자동색이 연출 색을 덮어쓰지 않게 끄고, 조명을 켠다
            port.setAmbientAutoByDriveMode(false)
            port.setAmbientMode(AmbientMode.ON)
            session.ambientReady = true
        }
        val last = session.ambientShown
        if (force || last?.colorRgb != value.colorRgb) port.setAmbientColor(value.colorRgb)
        if (force || last?.brightness != value.brightness) port.setAmbientBrightness(value.brightness)
        // 둘 다 보낸 뒤에만 기록한다. 중간에 실패하면 다음 값에서 다시 보낸다
        session.ambientShown = value
    }

    /** 지금 차에 보이는 무드등 값. 연출이 보낸 값, 없으면 켜져 있던 시작 전 조명 */
    private fun Session.ambientNow(range: IntRange): AmbientValue? = ambientShown ?: snapshot
        .takeIf { it.ambientMode == AmbientMode.ON }
        ?.let { s ->
            val color = s.ambientColorRgb ?: return@let null
            val brightness = s.ambientBrightness ?: return@let null
            AmbientValue(color, brightness.coerceIn(range))
        }

    /**
     * 시작 전 값으로 되돌리고 연출을 끝낸다. 호출한 코루틴이 취소돼도 끝까지 실행하고,
     * 한 항목이 실패해도 나머지를 계속한다. 값을 먼저 되돌리고 공조 전원은 마지막에 끈다 (02 문서 4장).
     * @return 되돌리지 못한 항목. 연출 중이 아니면 빈 목록
     */
    suspend fun restore(): List<String> = withContext(NonCancellable) {
        mutex.withLock {
            val ending = session ?: return@withLock emptyList()
            val snapshot = ending.snapshot
            session = null
            val failed = mutableListOf<String>()
            suspend fun step(name: String, block: suspend () -> Unit) {
                if (attempt { block() } == null) failed += name
            }

            snapshot.fanSpeed?.let { step("fanSpeed") { port.setFanSpeed(it) } }
            snapshot.airflow?.let { step("airflow") { port.setAirflow(it) } }
            snapshot.temperaturesC.forEach { (zone, celsius) ->
                step("temperature[${zone.raw}]") { port.setTemperature(zone, celsius) }
            }
            snapshot.ambientColorRgb?.let { step("ambientColor") { port.setAmbientColor(it) } }
            snapshot.ambientBrightness?.let { step("ambientBrightness") { port.setAmbientBrightness(it) } }
            snapshot.ambientMode?.let { step("ambientMode") { port.setAmbientMode(it) } }
            snapshot.ambientAutoByDriveMode?.let { step("ambientAuto") { port.setAmbientAutoByDriveMode(it) } }
            snapshot.seatHeatLevels.forEach { (zone, level) ->
                step("seatHeat[${zone.raw}]") { port.setSeatHeat(zone, level) }
            }
            // 실패한 등은 다시 정차하면 되돌리도록 남긴다 ([PendingLamps])
            val lamps = PendingLamps()
            snapshot.cabinLight?.takeIf { ending.cabinLightTouched }?.let { switch ->
                step("cabinLight") { port.setCabinLight(switch) }
                if ("cabinLight" in failed) lamps.cabinLight = switch
            }
            snapshot.readingLights.filterKeys { it in ending.readingLightsTouched }.forEach { (zone, switch) ->
                val name = "readingLight[${zone.raw}]"
                step(name) { port.setReadingLight(zone, switch) }
                if (name in failed) lamps.readingLights[zone] = switch
            }
            if (snapshot.hvacPowerOn == false) step("hvacPower") { port.setHvacPower(false) }
            // 시트는 연출 안에서 이미 되돌렸다. 되돌리지 못한 경우만 알린다 (위치를 모른 채 더 내리지는 않는다)
            ending.seatLiftUnrestored?.let { (zone, axes) -> axes.forEach { failed += "${it.callName()}[${zone.raw}]" } }
            _monitor.update { it.copy(active = false, restoreFailures = failed.toList()) }
            pendingLamps = lamps
            _pendingRestore.value = lamps.names()
            failed
        }
    }

    /**
     * 복원하지 못한 등([pendingRestore])을 다시 정차할 때마다 되돌린다. 앱이 살아 있는 동안 한 번 띄워 두며 끝나지 않는다.
     * 연출 중에는 건드리지 않는다 (그 연출의 [begin]이 이미 이어받았다).
     */
    suspend fun watchPendingRestore() {
        vehicleStates.state.collect { state ->
            if (_pendingRestore.value.isEmpty() || ParkedRule.evaluate(state) != ParkedState.Parked) return@collect
            mutex.withLock {
                if (session == null) withContext(NonCancellable) { retryPendingLamps() }
            }
        }
    }

    /** [pendingLamps]를 다시 보내고, 되돌린 항목은 남은 목록과 [SafetyMonitor.restoreFailures]에서 뺀다. [mutex] 안에서 부른다 */
    private suspend fun retryPendingLamps() {
        val lamps = pendingLamps
        if (lamps.names().isEmpty() || !isParked()) return
        lamps.cabinLight?.let { switch ->
            if (attempt { port.setCabinLight(switch) } != null) lamps.cabinLight = null
        }
        lamps.readingLights.toMap().forEach { (zone, switch) ->
            if (attempt { port.setReadingLight(zone, switch) } != null) lamps.readingLights.remove(zone)
        }
        val left = lamps.names()
        val restored = _pendingRestore.value.toSet() - left.toSet()
        _pendingRestore.value = left
        if (restored.isNotEmpty()) _monitor.update { m -> m.copy(restoreFailures = m.restoreFailures?.minus(restored)) }
    }

    /**
     * 앞좌석을 위로 살짝 올렸다 제자리로 되돌리기를 [Effect.SeatLift.repeat]번 되풀이한다 (기획서 5장 7번).
     * 방석 기울기를 지원하면 높이와 함께 방석 앞쪽도 올려(몸이 살짝 뒤로 기움) 함께 되돌린다. 두 축은 같은 규칙을 따른다:
     * 시트 이동은 STOP까지 계속 움직이므로 위치를 읽으며 멈추고, 아래 방향은 시작 위치로 돌아갈 때만 쓰며
     * 시작 위치보다 내리지 않는다. 두 축 모두 되돌린 것을 확인한 뒤에만 다시 올린다.
     *
     * 실행 조건: 부모가 허용, 연출 중 처음, 운전석이 아닌 앞좌석에 사람이 앉아 벨트를 맴, 높이 위치를 읽을 수 있음.
     * 기울기는 덤이라 위치를 못 읽거나 이미 맨 위면 높이만 움직인다.
     * 믿을 수 없는 위치(에뮬레이터 SDK의 늘 0)는 어댑터가 null로 돌려주므로, 여기서 읽힌 0은 진짜 맨 아래다 (02 문서 4장).
     *
     * 올리는 중에 정차가 깨지면 바로 멈추고 되돌린다. 올리고 되돌리는 한 번은 취소되지 않고,
     * 취소(그만하기·건너뛰기·중단)나 정차 해제는 다음 번을 시작하기 전에 본다.
     */
    private suspend fun seatLift(session: Session, effect: Effect.SeatLift): SafetyMonitor.SeatLiftControl {
        fun skipped(outcome: SeatLiftOutcome, start: Int? = null) =
            SafetyMonitor.SeatLiftControl(outcome, start?.let { SafetyMonitor.SeatAxisTrace(it) })
        if (!session.allowSeatMotion) return skipped(SeatLiftOutcome.DISABLED)
        if (session.seatLiftUsed) return skipped(SeatLiftOutcome.ALREADY_USED)
        val range = session.capabilities.seatHeightRange
        if (range == null || session.capabilities.seatLiftZones.isEmpty()) return skipped(SeatLiftOutcome.UNSUPPORTED)
        val zone = session.capabilities.seatLiftZones.firstOrNull { attempt { port.isSeatOccupiedAndBuckled(it) } == true }
            ?: return skipped(SeatLiftOutcome.NOT_OCCUPIED)
        val start = attempt { port.readSeatPosition(zone, SeatAxis.HEIGHT) } ?: return skipped(SeatLiftOutcome.POSITION_UNREADABLE)
        if (start !in range) return skipped(SeatLiftOutcome.POSITION_UNREADABLE, start)
        val target = liftTarget(start, range)
        if (target <= start) return skipped(SeatLiftOutcome.AT_TOP, start)
        val tiltStart = session.capabilities.seatTiltRange?.let { tiltRange ->
            attempt { port.readSeatPosition(zone, SeatAxis.TILT) }?.takeIf { it in tiltRange && liftTarget(it, tiltRange) > it }
        }
        val tiltTarget = tiltStart?.let { liftTarget(it, session.capabilities.seatTiltRange!!) }
        session.seatLiftUsed = true

        val times = effect.repeat.coerceIn(1, limits.maxSeatLiftRepeat)
        val upLimitMs = (min(effect.durationSec, limits.maxSeatLiftSec) * 1000).toLong()
        val caller = currentCoroutineContext()[Job]
        // 시연 HUD가 움직임을 따라가도록 움직이는 동안의 위치를 먼저 올려 둔다 (읽을 때마다 [showSeat]로 갱신)
        record {
            it.copy(
                seatLift = SafetyMonitor.SeatLiftControl(
                    SeatLiftOutcome.IN_PROGRESS,
                    height = SafetyMonitor.SeatAxisTrace(start, target, peak = start, now = start),
                    tilt = tiltStart?.let { s -> SafetyMonitor.SeatAxisTrace(s, tiltTarget, peak = s, now = s) },
                    repeat = times,
                ),
            )
        }
        return withContext(NonCancellable) {
            var peak = start
            var end: Int? = start
            var tiltPeak = tiltStart
            var tiltEnd = tiltStart
            var done = 0
            for (i in 0 until times) {
                if (i > 0) {
                    if (caller?.isActive == false || !isParked()) break
                    delay(SEAT_BOB_GAP_MS)
                    // 쉬는 사이에 바뀌었을 수 있다. 정차가 아닌데 UP을 보내면 안 된다
                    if (caller?.isActive == false || !isParked()) break
                }
                // 두 축을 함께 올리고 함께 되돌린다. bobOnce는 취소 말고는 예외를 내지 않아, 한 축의 실패가 다른 축의 STOP을 막지 않는다
                val (bob, tiltBob) = coroutineScope {
                    val height = async { bobOnce(zone, SeatAxis.HEIGHT, start, target, upLimitMs) }
                    val tilt = if (tiltStart != null && tiltTarget != null) {
                        async { bobOnce(zone, SeatAxis.TILT, tiltStart, tiltTarget, upLimitMs) }
                    } else {
                        null
                    }
                    height.await() to tilt?.await()
                }
                peak = max(peak, bob.peak)
                end = bob.end
                if (tiltBob != null) {
                    tiltPeak = max(tiltPeak ?: tiltBob.peak, tiltBob.peak)
                    tiltEnd = tiltBob.end
                }
                val unrestored = buildSet {
                    if (!bob.restored) add(SeatAxis.HEIGHT)
                    if (tiltBob?.restored == false) add(SeatAxis.TILT)
                }
                if (unrestored.isNotEmpty()) {
                    session.seatLiftUnrestored = zone to unrestored
                    break
                }
                done++
                val count = done
                record { m -> m.copy(seatLift = m.seatLift?.copy(done = count)) }
            }
            val outcome = if (session.seatLiftUnrestored != null) SeatLiftOutcome.UNRESTORED else SeatLiftOutcome.DONE
            val height = SafetyMonitor.SeatAxisTrace(start, target, peak, end, now = end)
            val tilt = tiltStart?.let { SafetyMonitor.SeatAxisTrace(it, tiltTarget, tiltPeak, tiltEnd, now = tiltEnd) }
            SafetyMonitor.SeatLiftControl(outcome, height, tilt, done, times)
        }
    }

    /** [start]에서 범위의 [SafetyLimits.maxSeatLiftRatio]만큼 위 (범위 끝을 넘지 않음) */
    private fun liftTarget(start: Int, range: IntRange): Int =
        min(range.last, start + ceil((range.last - range.first) * limits.maxSeatLiftRatio).toInt())

    private class Bob(val peak: Int, val end: Int?, val restored: Boolean)

    /**
     * [axis]를 [start]에서 [target]까지(시간 상한 [upLimitMs]) 올렸다가 [start]로 되돌린다.
     * 끝 위치가 [start] 이상, 한 번 읽는 사이 움직이는 양 이내일 때만 restored=true.
     * 명령이 실패해도 예외를 내지 않고 멈춘 위치로 판단한다 (함께 움직이는 다른 축을 끊지 않도록).
     *
     * STOP은 늦게 닿는다: 위치를 읽고 STOP을 보내는 사이에도 시트는 움직인다 (에뮬레이터는 100ms마다 5씩,
     * 2026-11-03 확인: 40에서 내려오다 40을 보고 STOP → 35에서 멈춤). 그래서
     * 1. 직전 읽기 사이 움직인 양을 한 걸음으로 보고, 한 걸음이면 닿을 때 미리 멈춘다 (목표를 넘지 않음)
     * 2. 미리 멈춰 한 걸음 이상 남았으면 짧게 밀어([nudge]) 마저 채운다. 한 칸만 가도록 밀므로 넘지 않는다
     * 내려올 때도 같다. 시작 위치보다 내려가지 않고, 시작 위치에 맞춘다.
     */
    private suspend fun bobOnce(zone: ZoneId, axis: SeatAxis, start: Int, target: Int, upLimitMs: Long): Bob {
        // upMs는 읽는 사이 기다린 시간(시트 속도를 어림하는 데 씀), upElapsedMs는 UP을 보내기 시작한 뒤 흐른 시간(상한에 씀).
        // 명령 응답이 늦으면 그동안에도 움직이므로 상한은 둘 중 큰 값으로 잰다
        var upMs = 0L
        var upElapsedMs = 0L
        var peak = start
        var stepUp = 0
        var upLast = start
        var sent = false
        val upStartedAt = clockMs()
        try {
            sent = moveSeat(zone, axis, SeatMove.UP) != null
            upElapsedMs = clockMs() - upStartedAt
            if (sent) {
                var last = start
                while (upElapsedMs < upLimitMs && isParked()) {
                    delay(SEAT_POLL_MS)
                    upMs += SEAT_POLL_MS
                    upElapsedMs = max(upMs, clockMs() - upStartedAt)
                    val position = readSeat(zone, axis) ?: break
                    peak = max(peak, position)
                    if (position > last) stepUp = max(stepUp, position - last)
                    last = position
                    upLast = position
                    if (position + stepUp >= target) break
                }
            }
        } finally {
            moveSeat(zone, axis, SeatMove.STOP)
        }

        var now = settledPosition(zone, axis) ?: peak
        peak = max(peak, now)
        // 올리라는 명령이 거부됐다. 그대로면 움직이지 않은 것이고, 움직였으면 아래에서 되돌린다
        if (!sent && now == start) return Bob(peak, now, restored = true)
        if (peak == start && now == start) {
            // 위치가 그대로다. 움직이지 않았거나 위치 읽기가 멈춘 것이라, 모른 채 내리지 않고 알린다
            return Bob(peak, now, restored = false)
        }
        // 한 걸음을 모르면(읽는 사이 움직인 적 없음) 밀었을 때 얼마나 갈지 몰라 밀지 않는다.
        // 느린 시트(실차)는 짧게 밀어도 움직이지 않고, STOP이 늦어도 거의 지나치지 않아 밀 필요가 없다
        if (stepUp > 0 && nudgeMoves(upLast - start, upMs)) {
            // 미는 시간도 올리는 시간(최대 1초)에 넣는다
            val (pushed, spentMs) = nudge(zone, axis, SeatMove.UP, now, upLimitMs - upElapsedMs, canMove = { it + stepUp <= target }, allowed = ::isParked)
            now = pushed
            upElapsedMs += spentMs
            peak = max(peak, now)
        }

        var stepDown = 0
        var downTravel = 0
        var downMs = 0L
        if (now > start) {
            // 올라간 시간보다 넉넉히 기다리되, 시간 상한으로 반드시 멈춘다
            val downLimitMs = upElapsedMs * 3 + SEAT_RETURN_MARGIN_MS
            val downStartedAt = clockMs()
            try {
                if (moveSeat(zone, axis, SeatMove.DOWN) != null) {
                    var last = now
                    while (max(downMs, clockMs() - downStartedAt) < downLimitMs) {
                        delay(SEAT_POLL_MS)
                        downMs += SEAT_POLL_MS
                        val position = readSeat(zone, axis) ?: break
                        if (position < last) stepDown = max(stepDown, last - position)
                        downTravel += last - position
                        last = position
                        if (position - stepDown <= start) break
                    }
                }
            } finally {
                moveSeat(zone, axis, SeatMove.STOP)
            }
        }
        var end = settledPosition(zone, axis)
        if (end != null && stepDown > 0 && nudgeMoves(downTravel, downMs)) {
            end = nudge(zone, axis, SeatMove.DOWN, end, Long.MAX_VALUE, canMove = { it - stepDown >= start }, allowed = { true }).first
        }
        // 시작 위치보다 낮으면 규칙 위반이라 성공으로 치지 않는다. 한 걸음 넘게 위면 되돌리지 못한 것
        val restored = end != null && end >= start && end <= start + max(stepDown, 1)
        return Bob(peak, end, restored)
    }

    /**
     * [move] 방향으로 밀다가 위치가 바뀌는 게 보이면(또는 [SEAT_NUDGE_MS]가 지나면) 멈추기를 [canMove]인 동안 되풀이한다
     * (최대 [SEAT_NUDGE_TRIES]번, 민 시간 합 [budgetMs] 이내).
     * 에뮬레이터 시트는 움직이기 시작하고 100ms마다 한 칸씩 가므로, 첫 칸을 보고 멈추면 STOP이 다음 칸(200ms) 전에 닿아 한 칸만 간다.
     * @return (멈춘 위치, 민 시간 합)
     */
    private suspend fun nudge(
        zone: ZoneId,
        axis: SeatAxis,
        move: SeatMove,
        from: Int,
        budgetMs: Long,
        canMove: (Int) -> Boolean,
        allowed: () -> Boolean,
    ): Pair<Int, Long> {
        var position = from
        var spentMs = 0L
        repeat(SEAT_NUDGE_TRIES) {
            if (!canMove(position) || !allowed() || spentMs + SEAT_NUDGE_MS > budgetMs) return position to spentMs
            val pushedAt = clockMs()
            try {
                if (moveSeat(zone, axis, move) == null) return position to spentMs
                // 움직인 게 보이면 바로 멈춘다. 매끄럽게 움직이는 시트는 조금만, 한 칸씩 가는 시트는 한 칸만 간다.
                // 명령 응답이 늦었으면 그동안 이미 움직였으므로 흐른 시간도 민 시간으로 친다
                var heldMs = 0L
                while (max(heldMs, clockMs() - pushedAt) < SEAT_NUDGE_MS) {
                    delay(SEAT_NUDGE_POLL_MS)
                    heldMs += SEAT_NUDGE_POLL_MS
                    val now = readSeat(zone, axis) ?: break
                    if (now != position) break
                }
                spentMs += max(heldMs, clockMs() - pushedAt)
            } finally {
                moveSeat(zone, axis, SeatMove.STOP)
            }
            position = settledPosition(zone, axis) ?: return position to spentMs
        }
        return position to spentMs
    }

    /** 읽으며 움직인 속도([travel]/[elapsedMs])로 [SEAT_NUDGE_MS] 안에 한 칸 이상 움직이는지. 느린 시트는 밀어도 그대로라 밀지 않는다 */
    private fun nudgeMoves(travel: Int, elapsedMs: Long): Boolean =
        elapsedMs > 0 && travel * SEAT_NUDGE_MS >= elapsedMs

    /** STOP이 늦게 닿아 조금 더 움직일 수 있으므로, 잠시 기다렸다가 멈춘 위치를 읽는다 */
    private suspend fun settledPosition(zone: ZoneId, axis: SeatAxis): Int? {
        delay(SEAT_SETTLE_MS)
        return readSeat(zone, axis)
    }

    /** 시트 이동 명령. 실패하면 null. 보낸 명령을 시연 HUD에 띄운다 */
    private suspend fun moveSeat(zone: ZoneId, axis: SeatAxis, move: SeatMove): Unit? =
        attempt { port.moveSeat(zone, axis, move) }?.also { showSeat(axis) { it.copy(move = move) } }

    /** 시트 위치. 읽지 못하면 null. 읽은 위치를 시연 HUD에 띄운다 */
    private suspend fun readSeat(zone: ZoneId, axis: SeatAxis): Int? =
        attempt { port.readSeatPosition(zone, axis) }?.also { position ->
            showSeat(axis) { it.copy(now = position, peak = max(it.peak ?: position, position)) }
        }

    /** 움직이는 중인 시트 연출의 [axis] 기록을 바꾼다. 두 축이 동시에 불러도 [record]가 원자적으로 합친다 */
    private fun showSeat(axis: SeatAxis, change: (SafetyMonitor.SeatAxisTrace) -> SafetyMonitor.SeatAxisTrace) = record { m ->
        val lift = m.seatLift?.takeIf { it.outcome == SeatLiftOutcome.IN_PROGRESS } ?: return@record m
        m.copy(
            seatLift = when (axis) {
                SeatAxis.HEIGHT -> lift.copy(height = lift.height?.let(change))
                SeatAxis.TILT -> lift.copy(tilt = lift.tilt?.let(change))
            },
        )
    }

    /** 복원 실패 목록에 쓰는 이름 (`seatHeight[4]`, `seatTilt[4]`) */
    private fun SeatAxis.callName() = when (this) {
        SeatAxis.HEIGHT -> "seatHeight"
        SeatAxis.TILT -> "seatTilt"
    }

    private fun record(transform: (SafetyMonitor) -> SafetyMonitor) = _monitor.update(transform)

    private fun isParked(): Boolean = ParkedRule.evaluate(vehicleStates.state.value) == ParkedState.Parked

    /** 실패하면 null. 취소는 삼키지 않는다 */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        /** 시트 위치를 읽는 간격. 에뮬레이터 시트는 100ms마다 움직인다 */
        const val SEAT_POLL_MS = 50L
        const val SEAT_RETURN_MARGIN_MS = 1_000L
        const val SEAT_SETTLE_MS = 300L
        /**
         * 마지막 한 걸음을 채울 때 최대로 미는 시간. 에뮬레이터 시트는 움직이기 시작하고 100ms마다 한 칸 가므로(박자는 움직일 때마다 새로 셈)
         * 100ms보다 길어야 움직인다 (2026-11-03 측정: 50ms로 밀면 6번 모두 움직이지 않음). 움직임이 보이면 그 전에 멈춘다.
         * 200ms를 넘으면 두 칸을 갈 수 있으므로, 명령이 닿는 시간 편차(측정 0~60ms)를 더해도 그 안에 들도록 130ms
         */
        const val SEAT_NUDGE_MS = 130L
        /** 밀면서 위치를 읽는 간격. 짧을수록 움직임을 빨리 보고 멈춘다 */
        const val SEAT_NUDGE_POLL_MS = 20L
        const val SEAT_NUDGE_TRIES = 3

        /** 되돌린 뒤 다시 올리기 전 잠깐 쉬어 한 번씩 들썩이는 느낌을 준다 */
        const val SEAT_BOB_GAP_MS = 150L
    }
}
