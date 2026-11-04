package com.breathingtales.data.vehicle

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal const val TAG = "BTVehicle"

/**
 * Vehicle SDK 호출 실패. SDK의 VehicleException은 배포 AAR에서 난독화되어 타입으로 잡을 수 없으므로
 * 메시지를 담아 감싼다 (docs/dev/02_기술_확인.md 4장 구현 규칙 5).
 */
class VehicleCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val CALL_TIMEOUT_MS = 3_000L

/**
 * 콜백 한 쌍(onComplete/onFailed)을 suspend 호출로 바꾼다.
 * 서비스가 연결되지 않으면 콜백이 아예 오지 않으므로 시간 제한을 둔다. 시간 초과는 취소가 아닌
 * [VehicleCallException]으로 던져, SafetyGuard 복원이 한 항목 때문에 멈추지 않게 한다.
 * 실패는 여기서 로그를 남긴다. 위에서는 효과 하나가 실패해도 조용히 넘어가므로 (PlayStoryUseCase) 여기가 유일한 기록이다.
 */
internal suspend fun <T> pleosCall(
    what: String,
    call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): T {
    val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine<Result<T>> { cont ->
            val done = AtomicBoolean(false)
            val fail = { e: Exception ->
                if (done.compareAndSet(false, true)) {
                    cont.resume(Result.failure(VehicleCallException("$what 실패: ${e.message ?: e.javaClass.name}", e)))
                }
            }
            try {
                call({ value -> if (done.compareAndSet(false, true)) cont.resume(Result.success(value)) }, fail)
            } catch (e: Exception) {
                // SDK가 콜백 대신 바로 던지는 경우 (잘못된 영역 등)
                fail(e)
            }
        }
    } ?: Result.failure(VehicleCallException("$what: ${CALL_TIMEOUT_MS}ms 동안 응답 없음 (Vehicle 서비스 연결 확인)"))
    result.exceptionOrNull()?.let { Log.w(TAG, it.message ?: what) }
    return result.getOrThrow()
}

/** set 호출의 onComplete(area, value)를 받는다 */
internal suspend fun pleosSet(what: String, call: (onComplete: (Any?, Any?) -> Unit, onFailed: (Exception) -> Unit) -> Unit) =
    pleosCall<Unit>(what) { ok, ng -> call({ _, _ -> ok(Unit) }, ng) }

/** 실패하면 로그만 남기고 null. 취소는 삼키지 않는다 */
internal suspend fun <T> orNull(what: String, block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // VehicleCallException은 pleosCall이 이미 로그를 남겼다
        if (e is VehicleCallException) Log.i(TAG, "$what → 건너뜀") else Log.w(TAG, "$what → 건너뜀: ${e.message}")
        null
    }

/** `initialize()` 직후에는 서비스 연결 전이라 응답이 없을 수 있다. 몇 번 다시 시도한다 */
internal suspend fun <T> retrying(times: Int = 3, block: suspend () -> T): T {
    repeat(times - 1) {
        try {
            return block()
        } catch (e: VehicleCallException) {
            Log.w(TAG, "다시 시도: ${e.message}")
            delay(500)
        }
    }
    return block()
}
