package com.breathingtales.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 대본 재작성(10)에서 새로 쓸 효과의 지원 여부 확인: 시트 통풍, 실내등, 독서등, 스마트 글라스.
 * 각 기능마다 지원 범위 → 현재값 → 켜기 → 다시 읽기 → 원래 값으로 되돌리기 → 다시 읽기.
 *
 * `adb shell am start --user 10 -n com.breathingtales/com.breathingtales.probe.ProbeActivity --ez extrasCheck true`
 * 로 실행하면 [runAll]을 자동으로 돌린다. 결과는 logcat `BTProbe`의 `[Extras]`.
 */
class CabinExtrasProbe(context: Context, private val log: ProbeLog, private val scope: CoroutineScope) {
    private val vehicle = Vehicle(context.applicationContext)

    fun initialize() = vehicle.initialize()

    fun release() = vehicle.release()

    fun runAll() = scope.launch {
        delay(1_500) // 서비스 연결 대기
        log.add(E, "=== 추가 효과 확인 시작 ===")
        seatVentilation()
        cabinLight()
        readingLight()
        smartGlass()
        log.add(E, "=== 추가 효과 확인 끝 ===")
    }

    private suspend fun seatVentilation() {
        val seat = vehicle.getSeat()
        val ranges = await<Map<SeatArea, *>>("통풍 지원 범위") { ok, fail ->
            seat.checkSeatVentilationCapabilities({ c, m -> log.add(E, "통풍 접근: ${c.accessType}"); ok(m) }, fail)
        } ?: return
        if (ranges.isEmpty()) return log.add(E, "통풍 영역 없음 → 건너뜀")
        val area = ranges.keys.firstOrNull { it.label().contains("ROW_2") } ?: ranges.keys.first()
        val before = await<Int?>("통풍 현재 ${area.label()}") { ok, fail -> seat.getHvacSeatVentilationState(area, ok, fail) }
        await<Any>("통풍 1단 설정 ${area.label()}") { ok, fail -> seat.setHvacSeatVentilation(area, 1, { a, v -> ok("${a.label()}=$v") }, fail) }
        delay(1_000)
        await<Int?>("통풍 다시 읽기") { ok, fail -> seat.getHvacSeatVentilationState(area, ok, fail) }
        await<Any>("통풍 되돌리기 → ${before ?: 0}") { ok, fail ->
            seat.setHvacSeatVentilation(area, before ?: 0, { a, v -> ok("${a.label()}=$v") }, fail)
        }
        delay(1_000)
        await<Int?>("통풍 복원 확인") { ok, fail -> seat.getHvacSeatVentilationState(area, ok, fail) }
    }

    private suspend fun cabinLight() {
        val light = vehicle.getLight()
        await<Any>("실내등 스위치 지원") { ok, fail -> light.checkCabinLightSwitchCapabilities({ c, l -> ok("${c.accessType} $l") }, fail) } ?: return
        await<Any>("실내등 상태 지원") { ok, fail -> light.checkCabinLightStateCapabilities({ c, l -> ok("${c.accessType} $l") }, fail) }
        val before = await<LightSwitch?>("실내등 스위치 현재") { ok, fail -> light.getCabinLightSwitch(ok, fail) }
        await<Any?>("실내등 상태 현재") { ok, fail -> light.getCurrentCabinLightState(ok, fail) }
        await<Any?>("실내등 켜기") { ok, fail -> light.setCabinLightSwitch(LightSwitch.ON, ok, fail) }
        delay(1_000)
        await<Any?>("실내등 상태 다시 읽기") { ok, fail -> light.getCurrentCabinLightState(ok, fail) }
        await<Any?>("실내등 되돌리기 → ${before ?: LightSwitch.OFF}") { ok, fail -> light.setCabinLightSwitch(before ?: LightSwitch.OFF, ok, fail) }
        delay(1_000)
        await<Any?>("실내등 복원 확인") { ok, fail -> light.getCurrentCabinLightState(ok, fail) }
    }

    private suspend fun readingLight() {
        val light = vehicle.getLight()
        val areas = await<Map<SeatArea, *>>("독서등 스위치 지원") { ok, fail ->
            light.checkReadingLightSwitchCapabilities({ c, m -> log.add(E, "독서등 접근: ${c.accessType}"); ok(m) }, fail)
        } ?: return
        if (areas.isEmpty()) return log.add(E, "독서등 영역 없음 → 건너뜀")
        val area = areas.keys.firstOrNull { it.label().contains("ROW_2") } ?: areas.keys.first()
        val before = await<LightSwitch?>("독서등 스위치 현재 ${area.label()}") { ok, fail -> light.getReadingLightSwitch(area, ok, fail) }
        await<Any?>("독서등 켜기 ${area.label()}") { ok, fail -> light.setReadingLightSwitch(area, LightSwitch.ON, ok, fail) }
        delay(1_000)
        await<Any?>("독서등 상태 다시 읽기") { ok, fail -> light.getCurrentReadingLightState(area, ok, fail) }
        await<Any?>("독서등 되돌리기 → ${before ?: LightSwitch.OFF}") { ok, fail ->
            light.setReadingLightSwitch(area, before ?: LightSwitch.OFF, ok, fail)
        }
        delay(1_000)
        await<Any?>("독서등 복원 확인") { ok, fail -> light.getCurrentReadingLightState(area, ok, fail) }
    }

    private suspend fun smartGlass() {
        val window = vehicle.getWindow()
        await<Any>("스마트 글라스 켜기/끄기 지원") { ok, fail -> window.checkSmartGlassStateCapabilities({ ok(it.accessType) }, fail) }
        val levels = await<Map<WindowArea, *>>("스마트 글라스 단계 지원") { ok, fail ->
            window.checkSmartGlassLevelCapabilities({ c, m -> log.add(E, "스마트 글라스 접근: ${c.accessType}"); ok(m) }, fail)
        } ?: return
        if (levels.isEmpty()) return log.add(E, "스마트 글라스 영역 없음 → 건너뜀")
        val area = levels.keys.first()
        await<Int?>("스마트 글라스 현재 단계 ${area.label()}") { ok, fail -> window.getCurrentSmartGlassLevel(area, ok, fail) }
        // 단계 값의 의미(투명/불투명 방향)를 모르므로 실제로 바꾸지는 않는다. 지원하는 차가 나오면 그때 확인
    }

    /** 콜백 API 하나를 기다린다. 2초 안에 답이 없으면 null */
    private suspend fun <T> await(what: String, call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): T? {
        val result = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Result<T>> { cont ->
                call({ if (cont.isActive) cont.resume(Result.success(it)) }, { if (cont.isActive) cont.resume(Result.failure(it)) })
            }
        }
        when {
            result == null -> log.add(E, "⚠️ $what: 2초 동안 응답 없음")
            result.isFailure -> log.add(E, "❌ $what 실패: ${result.exceptionOrNull()?.message}")
            else -> log.add(E, "$what: ${result.getOrNull().describe()}")
        }
        return result?.getOrNull()
    }

    private fun Any?.describe(): String = when (this) {
        is SeatArea -> label()
        is WindowArea -> label()
        is Map<*, *> -> entries.joinToString(prefix = "{", postfix = "}") { "${it.key.describe()}=${it.value}" }
        is List<*> -> joinToString(prefix = "[", postfix = "]") { it.describe() }
        else -> toString()
    }

    private companion object {
        const val E = "Extras"
    }
}

private fun SeatArea.label() = values.joinToString("+") { it.name }

private fun WindowArea.label() = values.joinToString("+") { it.name }
