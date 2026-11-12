package com.breathingtales.domain.safety

/**
 * 부모가 고르는 연출 강도 (기획서 5장 5번). 강도를 올려도 허용 범위를 넘지는 않는다.
 *
 * @property scale 온도 변화·무드등 밝기의 요청 값에 곱하는 배율. [HIGH]가 요청 그대로다.
 * @property fanSpan 바람의 위쪽 끝을 차량 최저 풍량에서 허용 풍량(최대의 80%)까지 중 어디에 둘지.
 *   0이면 최저 풍량, 1이면 허용 풍량. 차량마다 풍량 범위가 달라도 최저·최대를 기준으로 나눈다.
 */
enum class Intensity(val scale: Float, val fanSpan: Float) {
    LOW(0.5f, 1f / 3),
    MEDIUM(0.75f, 0.5f),
    HIGH(1f, 1f),
}
