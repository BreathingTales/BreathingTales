package com.breathingtales.domain.model

/**
 * 연출 효과. 차량과 무관한 추상 값만 담고, 실제 차량 값으로 바꾸는 일은 SafetyGuard(04)와 어댑터(05)가 한다.
 * 값 범위는 [StoryValidator]가 로드 시점에 검사한다.
 */
sealed interface Effect {

    /** 앰비언트 조명. [colorRgb]는 0xRRGGBB, [brightness]는 0.0~1.0 */
    data class Ambient(
        val colorRgb: Int,
        val brightness: Float,
        val fadeSec: Float = 0f,
    ) : Effect

    /** 앰비언트 밝기를 [delta]만큼 올리거나 내린다. 결과는 [max]를 넘지 않고, [fadeSec] 동안 서서히 바뀐다 */
    data class AmbientBrightnessStep(
        val delta: Float,
        val max: Float = 1f,
        val fadeSec: Float = 0f,
    ) : Effect

    /** 바람 세기. 0(바람 없음)~[MAX] 단계. 어댑터가 허용 범위 안의 풍량으로 나눠 담는다 */
    data class FanLevel(val level: Int) : Effect {
        companion object {
            const val MAX = 5
        }
    }

    /** 바람 세기를 [delta] 단계 바꾼다. 결과는 [max] 단계를 넘지 않는다 */
    data class FanLevelStep(
        val delta: Int,
        val max: Int = FanLevel.MAX,
    ) : Effect

    data class FanDirection(val direction: Airflow) : Effect

    /** 시작 온도 기준 상대값(℃) */
    data class TemperatureDelta(val deltaC: Float) : Effect {
        companion object {
            const val MAX_ABS = 2f
        }
    }

    /** 뒷좌석 시트 열선 단계 (0~[MAX]). 허용 단계(1단)를 넘는 값은 SafetyGuard가 낮춘다 */
    data class SeatHeat(val level: Int) : Effect {
        companion object {
            const val MAX = 3
        }
    }

    /**
     * 앞좌석을 위로 살짝 올렸다가 제자리로 되돌린다 (기획서 5장 7번, 「아이가 바람에 붕 떠오르는」 장면).
     * [durationSec]은 한 번 올라가는 시간, [repeat]은 올렸다 되돌리기를 되풀이하는 횟수.
     * 부모가 켠 경우만, 조건을 모두 만족할 때 연출마다 한 번만 [SafetyGuard]가 실행한다
     */
    data class SeatLift(val durationSec: Float, val repeat: Int = 1) : Effect {
        companion object {
            const val MIN_SEC = 0.1f
            const val MAX_SEC = 1f
            const val MAX_REPEAT = 3
        }
    }

    /** 실내등. 이야기 시작에 꺼서 「극장 불이 꺼지는」 느낌을 준다 */
    data class CabinLight(val on: Boolean) : Effect

    /** 뒷좌석 독서등. 「구름 사이로 내려오는 햇살 한 줄기」 */
    data class Spotlight(val on: Boolean) : Effect

    /** 효과음·배경음 */
    data class Sound(
        val audio: String,
        val loop: Boolean = false,
        val volume: Float = 1f,
    ) : Effect

    /** 공조·조명·시트를 시작 전 상태로 되돌린다 */
    data class Restore(val fadeSec: Float = 0f) : Effect
}

/**
 * 풍향. 동화 효과(`fanDirection`)는 [FACE]·[FLOOR]만 쓰고,
 * 나머지는 시작 전 차량 풍향을 그대로 저장·복원하기 위해 둔다.
 */
enum class Airflow { FACE, FLOOR, FACE_AND_FLOOR, DEFROST, FACE_AND_DEFROST, DEFROST_AND_FLOOR, FACE_AND_FLOOR_AND_DEFROST }
