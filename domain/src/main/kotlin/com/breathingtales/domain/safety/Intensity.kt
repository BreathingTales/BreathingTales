package com.breathingtales.domain.safety

/**
 * 부모가 고르는 연출 강도 (기획서 5장 5번). 허용 범위의 위쪽 끝에 [scale]을 곱한다.
 * [HIGH]가 허용 범위 전체이고, 강도를 올려도 허용 범위를 넘지는 않는다.
 */
enum class Intensity(val scale: Float) {
    LOW(0.5f),
    MEDIUM(0.75f),
    HIGH(1f),
}
