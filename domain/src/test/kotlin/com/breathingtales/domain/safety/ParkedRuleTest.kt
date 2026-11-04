package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.safety.NotParkedReason.DOOR_OPEN
import com.breathingtales.domain.safety.NotParkedReason.MOVING
import com.breathingtales.domain.safety.NotParkedReason.NOT_IN_PARK
import com.breathingtales.domain.safety.NotParkedReason.UX_RESTRICTED
import org.junit.Assert.assertEquals
import org.junit.Test

class ParkedRuleTest {

    private fun reasons(state: VehicleState) =
        (ParkedRule.evaluate(state) as? ParkedState.NotParked)?.reasons.orEmpty()

    @Test
    fun `시작 조건을 모두 채우면 정차`() {
        assertEquals(ParkedState.Parked, ParkedRule.evaluate(PARKED))
    }

    @Test
    fun `조건 하나라도 깨지면 정차 아님`() {
        assertEquals(setOf(UX_RESTRICTED), reasons(PARKED.copy(uxRestricted = true)))
        assertEquals(setOf(NOT_IN_PARK), reasons(PARKED.copy(gear = Gear.DRIVE)))
        assertEquals(setOf(NOT_IN_PARK), reasons(PARKED.copy(gear = Gear.NEUTRAL)))
        assertEquals(setOf(MOVING), reasons(PARKED.copy(speedMps = 0.5f)))
        assertEquals(setOf(MOVING), reasons(PARKED.copy(speedMps = -0.5f)))
        assertEquals(setOf(DOOR_OPEN), reasons(PARKED.copy(doorsClosed = false)))
    }

    @Test
    fun `아직 받지 못한 값은 조건 불충족`() {
        assertEquals(setOf(UX_RESTRICTED, NOT_IN_PARK, MOVING, DOOR_OPEN), reasons(VehicleState()))
    }

    @Test
    fun `속도 센서 잡음은 정차로 보되 NaN은 주행으로 본다`() {
        assertEquals(ParkedState.Parked, ParkedRule.evaluate(PARKED.copy(speedMps = 0.05f)))
        assertEquals(setOf(MOVING), reasons(PARKED.copy(speedMps = Float.NaN)))
    }

    @Test
    fun `주차 브레이크는 시작 조건이 아님`() {
        assertEquals(ParkedState.Parked, ParkedRule.evaluate(PARKED.copy(parkingBrakeOn = false)))
        assertEquals(ParkedState.Parked, ParkedRule.evaluate(PARKED.copy(parkingBrakeOn = null)))
    }
}
