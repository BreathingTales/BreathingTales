package com.breathingtales.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.SeatHeightPositionListener
import ai.pleos.playground.vehicle.listener.SeatTiltPositionListener
import android.content.Context
import android.os.SystemClock
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 시트 높이·기울기 이동 확인 (10, 기획서 5장 7번). SDK 시트 이동은 위치 지정이 아니라
 * FORWARD / BACKWARD / STOP 연속 이동이라, 방향·속도·위치 읽기·STOP 동작을 에뮬레이터에서 잰다.
 *
 * `adb shell am start --user 10 -n com.breathingtales/com.breathingtales.probe.ProbeActivity --ez seatCheck true`
 * 로 실행하면 [runAll]을 자동으로 돌린다. 결과는 logcat `BTProbe`의 `[SeatMove]`.
 */
class SeatMoveProbe(context: Context, private val log: ProbeLog, private val scope: CoroutineScope) {
    private val vehicle = Vehicle(context.applicationContext)
    private val seat get() = vehicle.getSeat()

    fun initialize() = vehicle.initialize()

    fun release() = vehicle.release()

    fun runAll() = scope.launch {
        delay(1_500) // 서비스 연결 대기
        log.add(S, "=== 시트 이동 확인 시작 ===")
        val heightAreas = await("높이 이동 영역") { ok, fail -> seat.checkMoveSeatHeightCapabilities({ c, l -> ok("${c.accessType}" to l) }, fail) }
        val tiltAreas = await("기울기 이동 영역") { ok, fail -> seat.checkMoveSeatTiltCapabilities({ c, l -> ok("${c.accessType}" to l) }, fail) }
        await("높이 범위") { ok, fail -> seat.checkSeatHeightCapabilities({ ok(it.describe()) }, fail) }
        await("높이 위치 범위") { ok, fail -> seat.checkSeatHeightPositionCapabilities({ c, m -> ok("${c.accessType} ${m.describe()}") }, fail) }
        await("기울기 위치 범위") { ok, fail -> seat.checkSeatTiltPositionCapabilities({ c, m -> ok("${c.accessType} ${m.describe()}") }, fail) }
        val occupancy = await("착석 감지 영역") { ok, fail -> seat.checkSeatOccupancyCapabilities({ ok(it) }, fail) }
        val buckle = await("안전벨트 영역") { ok, fail -> vehicle.getSafety().checkSeatBuckledCapabilities({ ok(it) }, fail) }

        occupancy.orEmpty().forEach { area ->
            await("착석 ${area.label()}") { ok, fail -> seat.getCurrentSeatOccupancy(area, { ok(it) }, fail) }
        }
        buckle.orEmpty().forEach { area ->
            await("벨트 ${area.label()}") { ok, fail -> seat.getCurrentSeatBeltBuckledState(area, { ok(it) }, fail) }
        }

        val heightArea = heightAreas?.second?.firstOrNull { it.label().contains("ROW_1_LEFT") } ?: heightAreas?.second?.firstOrNull()
        if (heightArea != null) {
            pulseAndReturn("높이", heightArea, ::readHeight, ::moveHeight, ::watchHeight)
        } else {
            log.add(S, "높이 이동 영역 없음 → 건너뜀")
        }
        val tiltArea = tiltAreas?.second?.firstOrNull { it.label().contains("ROW_1_LEFT") } ?: tiltAreas?.second?.firstOrNull()
        if (tiltArea != null) {
            pulseAndReturn("기울기", tiltArea, ::readTilt, ::moveTilt, ::watchTilt)
        }
        log.add(S, "=== 시트 이동 확인 끝 ===")
    }

    /**
     * STOP 없이 움직이면 어떻게 되는지 (`--ez seatNoStop true`). 운전석 높이를 FORWARD로 보내고 4초 동안 STOP을 보내지 않는다.
     * 이어서 BACKWARD로 4초. 위치는 SDK로 읽히지 않으므로 `/vehicle/state`의 seat.positions·moves로 본다.
     * 끝나면 STOP을 보낸다 (확인 뒤 안전장치)
     */
    fun runNoStop() = scope.launch {
        delay(1_500)
        log.add(S, "=== STOP 없이 이동 확인 시작 ===")
        val areas = await<List<SeatArea>>("높이 이동 영역") { ok, fail -> seat.checkMoveSeatHeightCapabilities({ _, l -> ok(l) }, fail) }
        val area = areas?.firstOrNull { it.label().contains("ROW_1_LEFT") } ?: return@launch
        try {
            for (dir in listOf(MoveControl.FORWARD, MoveControl.BACKWARD)) {
                moveHeight(area, dir)
                log.add(S, "STOP 없이 $dir 시작")
                delay(4_000)
                log.add(S, "STOP 없이 $dir 4초 끝 (SDK 위치 ${readHeight(area)})")
            }
        } finally {
            moveHeight(area, MoveControl.STOP)
            log.add(S, "=== STOP 없이 이동 확인 끝 ===")
        }
    }

    /** FORWARD로 1초 움직이고 STOP → 멈춘 뒤에도 움직이는지 → 반대 방향으로 시작 위치까지 되돌림 (최대 3초) */
    private suspend fun pulseAndReturn(
        name: String,
        area: SeatArea,
        read: suspend (SeatArea) -> Int?,
        move: suspend (SeatArea, MoveControl) -> Unit,
        watch: (SeatArea, (Int?) -> Unit) -> () -> Unit,
    ) {
        val label = "$name ${area.label()}"
        val start = read(area)
        log.add(S, "$label 시작 위치: $start")
        val t0 = SystemClock.elapsedRealtime()
        val unwatch = watch(area) { log.add(S, "$label 위치 이벤트 +${SystemClock.elapsedRealtime() - t0}ms: $it") }
        try {
            var afterStop: Int? = null
            var oneSecLater: Int? = null
            // 끝에 붙어 있으면 한쪽으로는 움직이지 않으므로 두 방향을 차례로 해 본다
            for (dir in listOf(MoveControl.FORWARD, MoveControl.BACKWARD)) {
                move(area, dir)
                delay(1_000)
                move(area, MoveControl.STOP)
                afterStop = read(area)
                delay(1_000)
                oneSecLater = read(area)
                log.add(S, "$label $dir 1초 → STOP 직후 $afterStop, 1초 뒤 $oneSecLater (STOP 뒤 계속 움직이면 두 값이 다름)")
                if (oneSecLater != start) break
            }

            if (start == null || oneSecLater == null) return
            val back = if (oneSecLater > start) MoveControl.BACKWARD else MoveControl.FORWARD
            log.add(S, "$label 되돌리기: $back 로 $start 까지")
            move(area, back)
            val reached = withTimeoutOrNull(3_000) {
                while (true) {
                    val pos = read(area) ?: break
                    if ((back == MoveControl.BACKWARD && pos <= start) || (back == MoveControl.FORWARD && pos >= start)) break
                    delay(50)
                }
                true
            } == true
            move(area, MoveControl.STOP)
            delay(500)
            log.add(S, "$label 되돌리기 ${if (reached) "도달" else "3초 시간 초과"} → 최종 위치 ${read(area)} (시작 $start)")
        } finally {
            move(area, MoveControl.STOP)
            unwatch()
        }
    }

    private suspend fun moveHeight(area: SeatArea, control: MoveControl) {
        await("높이 $control") { ok, fail -> seat.moveSeatHeight(area, control, { _, c -> ok(c) }, fail) }
    }

    private suspend fun moveTilt(area: SeatArea, control: MoveControl) {
        await("기울기 $control") { ok, fail -> seat.moveSeatTilt(area, control, { _, c -> ok(c) }, fail) }
    }

    private suspend fun readHeight(area: SeatArea): Int? =
        await("높이 위치", quiet = true) { ok, fail -> seat.getCurrentSeatHeightPosition(area, { ok(it) }, fail) }

    private suspend fun readTilt(area: SeatArea): Int? =
        await("기울기 위치", quiet = true) { ok, fail -> seat.getCurrentSeatTiltPosition(area, { ok(it) }, fail) }

    private fun watchHeight(area: SeatArea, onUpdate: (Int?) -> Unit): () -> Unit {
        val listener = object : SeatHeightPositionListener {
            override fun onSeatHeightPositionUpdated(pos: Int?) = onUpdate(pos)
            override fun onFailed(e: Exception) = log.add(S, "높이 구독 실패: ${e.message}")
        }
        seat.registerSeatHeightPosition(area, listener)
        return { seat.unregisterSeatHeightPosition(area, listener) }
    }

    private fun watchTilt(area: SeatArea, onUpdate: (Int?) -> Unit): () -> Unit {
        val listener = object : SeatTiltPositionListener {
            override fun onSeatTiltPositionUpdated(pos: Int?) = onUpdate(pos)
            override fun onFailed(e: Exception) = log.add(S, "기울기 구독 실패: ${e.message}")
        }
        seat.registerSeatTiltPosition(area, listener)
        return { seat.unregisterSeatTiltPosition(area, listener) }
    }

    /** 콜백 API 하나를 기다린다. 2초 안에 답이 없으면 null */
    private suspend fun <T> await(
        what: String,
        quiet: Boolean = false,
        call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): T? {
        val result = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Result<T>> { cont ->
                call({ if (cont.isActive) cont.resume(Result.success(it)) }, { if (cont.isActive) cont.resume(Result.failure(it)) })
            }
        }
        when {
            result == null -> log.add(S, "⚠️ $what: 2초 동안 응답 없음")
            result.isFailure -> log.add(S, "❌ $what 실패: ${result.exceptionOrNull()?.message}")
            !quiet -> log.add(S, "$what: ${result.getOrNull().describeValue()}")
        }
        return result?.getOrNull()
    }

    private fun Any?.describeValue(): String = when (this) {
        is SeatArea -> label()
        is List<*> -> joinToString(prefix = "[", postfix = "]") { it.describeValue() }
        is Pair<*, *> -> "${first.describeValue()} ${second.describeValue()}"
        else -> toString()
    }

    private fun <V> Map<SeatArea, V>.describe() = entries.joinToString { "${it.key.label()}=${it.value}" }.ifEmpty { "(비어 있음)" }

    private companion object {
        const val S = "SeatMove"
    }
}

private fun SeatArea.label() = values.joinToString("+") { it.name }
