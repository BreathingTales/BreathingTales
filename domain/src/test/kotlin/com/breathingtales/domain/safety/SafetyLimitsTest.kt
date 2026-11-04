package com.breathingtales.domain.safety

import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyLimitsTest {
    @Test
    fun `기본 허용 범위는 기획서 5장 기준을 따른다`() {
        val limits = SafetyLimits()
        assertTrue(limits.minTemperatureC < limits.maxTemperatureC)
        assertTrue(limits.maxFanRatio <= 0.8f)
        assertTrue(limits.maxSeatHeaterLevel <= 1)
    }
}
