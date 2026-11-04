package com.breathingtales.domain.model

/**
 * 정차 판단에 쓰는 차량 상태 (기획서 3-1, docs/dev/02_기술_확인.md 2장).
 * 아직 값을 받지 못한 항목은 null이며, 판단 규칙(04)은 null을 "조건 불충족"으로 본다.
 */
data class VehicleState(
    val uxRestricted: Boolean? = null,
    val gear: Gear? = null,
    val speedMps: Float? = null,
    val doorsClosed: Boolean? = null,
    val parkingBrakeOn: Boolean? = null,
)

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, OTHER }
