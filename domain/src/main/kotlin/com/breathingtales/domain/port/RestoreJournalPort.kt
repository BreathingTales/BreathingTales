package com.breathingtales.domain.port

import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.ZoneId

/**
 * 되돌려야 할 실내 상태를 앱 밖(디스크)에 남기는 곳.
 * 연출 중 앱 프로세스가 죽으면(강제 종료·재설치) 메모리의 시작 상태가 사라져 실내가 연출 값으로 남는다.
 * SafetyGuard가 연출 중 이 기록을 갱신하고, 다음 실행에서 [com.breathingtales.domain.safety.SafetyGuard.recoverAfterRestart]가 되돌린다.
 */
interface RestoreJournalPort {
    /** 남은 기록. 없거나 읽지 못하면 null */
    suspend fun load(): RestoreRecord?

    /** 기록을 바꾼다. 돌아온 뒤에는 디스크에 남아 있어야 한다 (다음 차량 명령 전에 죽어도 남도록) */
    suspend fun save(record: RestoreRecord)

    suspend fun clear()

    /** 기록하지 않는다 (테스트·기록이 필요 없는 곳) */
    object None : RestoreJournalPort {
        override suspend fun load(): RestoreRecord? = null
        override suspend fun save(record: RestoreRecord) = Unit
        override suspend fun clear() = Unit
    }
}

/**
 * @param cabin 되돌릴 값. 실내등·독서등은 연출이 바꾼 것만 담는다 (바꾸지 않은 AUTO 등을 다시 쓰면 차량 동작이 바뀔 수 있다)
 * @param seatMoving 움직이는 중이던 시트. 죽은 사이 모터가 계속 움직일 수 있으므로 다음 실행에서 먼저 멈춘다
 */
data class RestoreRecord(
    val cabin: CabinSnapshot,
    val seatMoving: SeatInMotion? = null,
)

/** 들썩임 중인 시트 영역과 축별 시작 위치 */
data class SeatInMotion(
    val zone: ZoneId,
    val start: Map<SeatAxis, Int>,
)
