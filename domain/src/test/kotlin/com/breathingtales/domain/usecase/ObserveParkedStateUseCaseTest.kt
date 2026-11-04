package com.breathingtales.domain.usecase

import app.cash.turbine.test
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.safety.FakeVehicleStates
import com.breathingtales.domain.safety.NotParkedReason.DOOR_OPEN
import com.breathingtales.domain.safety.NotParkedReason.NOT_IN_PARK
import com.breathingtales.domain.safety.NotParkedReason.PARKING_BRAKE_RELEASED
import com.breathingtales.domain.safety.PARKED
import com.breathingtales.domain.safety.ParkedState
import com.breathingtales.domain.safety.ParkedState.NotParked
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveParkedStateUseCaseTest {

    private val states = FakeVehicleStates()
    private val useCase = ObserveParkedStateUseCase(states)

    @Test
    fun `시작 화면은 조건이 깨지는 즉시 정차 아님으로 바뀐다`() = runTest {
        useCase.beforeShow().test {
            assertEquals(ParkedState.Parked, awaitItem())
            states.state.value = PARKED.copy(gear = Gear.DRIVE)
            assertEquals(NotParked(setOf(NOT_IN_PARK)), awaitItem())
            states.state.value = PARKED
            assertEquals(ParkedState.Parked, awaitItem())
        }
    }

    @Test
    fun `시작 화면에서는 주차 브레이크 해제를 보지 않는다`() = runTest {
        states.state.value = PARKED.copy(parkingBrakeOn = true)
        useCase.beforeShow().test {
            assertEquals(ParkedState.Parked, awaitItem())
            states.state.value = PARKED.copy(parkingBrakeOn = false)
            expectNoEvents()
        }
    }

    @Test
    fun `연출 중 주차 브레이크가 풀리면 다시 걸어도 정차 아님으로 남는다`() = runTest {
        states.state.value = PARKED.copy(parkingBrakeOn = true)
        useCase.duringShow().test {
            assertEquals(ParkedState.Parked, awaitItem())
            states.state.value = PARKED.copy(parkingBrakeOn = false)
            assertEquals(NotParked(setOf(PARKING_BRAKE_RELEASED)), awaitItem())
            states.state.value = PARKED.copy(parkingBrakeOn = true)
            expectNoEvents()
        }
    }

    @Test
    fun `브레이크 없이 시작한 연출은 나중에 걸었다 풀어야 중단된다`() = runTest {
        useCase.duringShow().test {
            assertEquals(ParkedState.Parked, awaitItem())
            states.state.value = PARKED.copy(parkingBrakeOn = true)
            expectNoEvents()
            states.state.value = PARKED.copy(parkingBrakeOn = false)
            assertEquals(NotParked(setOf(PARKING_BRAKE_RELEASED)), awaitItem())
        }
    }

    @Test
    fun `연출 중 도어가 열리면 즉시 정차 아님`() = runTest {
        useCase.duringShow().test {
            assertEquals(ParkedState.Parked, awaitItem())
            states.state.value = PARKED.copy(doorsClosed = false)
            assertEquals(NotParked(setOf(DOOR_OPEN)), awaitItem())
        }
    }
}
