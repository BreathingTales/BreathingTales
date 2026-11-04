package com.breathingtales.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 일시정지 상태와, 멈춘 시간을 뺀 "진행 시간". 효과 시각(`at`)과 참여 제한 시간은 이 시간으로 재므로
 * 일시정지 동안에는 효과도 나오지 않고 참여 시간도 줄지 않는다.
 * 여러 코루틴(부모 버튼, 오디오 포커스 감시, 효과 큐)이 함께 쓰므로 바꾸는 함수는 동기화한다.
 */
internal class PauseGate(private val timeSource: TimeSource) {

    private val _reason = MutableStateFlow<PauseReason?>(null)
    val reason: StateFlow<PauseReason?> = _reason.asStateFlow()

    private var accumulated = Duration.ZERO
    private var runningSince: TimeMark? = timeSource.markNow()

    /** 이미 멈춰 있으면 이유를 바꾸지 않고 false */
    @Synchronized
    fun pause(reason: PauseReason): Boolean {
        if (_reason.value != null) return false
        accumulated += runningSince!!.elapsedNow()
        runningSince = null
        _reason.value = reason
        return true
    }

    @Synchronized
    fun resume() {
        if (_reason.value == null) return
        runningSince = timeSource.markNow()
        _reason.value = null
    }

    /** 시작부터 멈춘 시간을 뺀 진행 시간 */
    @Synchronized
    fun now(): Duration = accumulated + (runningSince?.elapsedNow() ?: Duration.ZERO)

    suspend fun awaitRunning() {
        _reason.first { it == null }
    }

    /** 진행 시간이 [target]에 이를 때까지 기다린다. 멈춘 동안은 시간이 흐르지 않는다 */
    suspend fun awaitTime(target: Duration) {
        while (true) {
            awaitRunning()
            val remaining = target - now()
            if (remaining <= Duration.ZERO) return
            // 남은 시간이 지나거나, 그 전에 멈추면 다시 계산한다
            withTimeoutOrNull(remaining) { _reason.first { it != null } }
        }
    }
}
