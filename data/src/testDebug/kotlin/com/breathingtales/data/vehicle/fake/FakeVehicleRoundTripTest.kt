package com.breathingtales.data.vehicle.fake

import com.breathingtales.data.vehicle.fake.FakeVehicleEffectPort.Companion.DRIVER
import com.breathingtales.data.vehicle.fake.FakeVehicleEffectPort.Companion.INITIAL_CABIN
import com.breathingtales.data.vehicle.fake.FakeVehicleEffectPort.Companion.PASSENGER
import com.breathingtales.data.vehicle.fake.FakeVehicleEffectPort.Companion.REAR_LEFT
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.safety.SafetyGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeVehicleRoundTripTest {

    private val port = FakeVehicleEffectPort()
    private val states = FakeVehicleStateRepository()
    private val guard = SafetyGuard(port, states)

    private suspend fun playWindScene() {
        guard.apply(Effect.Ambient(0x4A90E2, 0.6f))
        guard.apply(Effect.FanLevel(5))
        guard.apply(Effect.FanDirection(Airflow.FACE))
        guard.apply(Effect.TemperatureDelta(-2f))
        guard.apply(Effect.SeatHeat(3))
    }

    @Test
    fun `저장 → 변경 → 복원으로 원상 복구된다`() = runTest {
        assertTrue(guard.begin(Intensity.HIGH))
        playWindScene()

        val during = port.cabin.value
        assertEquals(6, during.fanSpeed)
        assertEquals(Airflow.FACE, during.airflow)
        assertEquals(mapOf(DRIVER to 20f, PASSENGER to 20.5f), during.temperaturesC)
        assertEquals(AmbientMode.ON, during.ambientMode)
        assertEquals(false, during.ambientAutoByDriveMode)
        assertEquals(1, during.seatHeatLevels[REAR_LEFT])

        assertEquals(emptyList<String>(), guard.restore())
        assertEquals(INITIAL_CABIN, port.cabin.value)
        assertEquals("hvacPower=false", port.log.value.last())
    }

    @Test
    fun `연출 중 도어가 열리면 더 이상 바꾸지 않고 복원은 그대로 된다`() = runTest {
        guard.begin(Intensity.MEDIUM)
        guard.apply(Effect.FanLevel(5))
        states.setDoorOpen(true)

        assertFalse(guard.apply(Effect.FanLevel(1)))
        assertEquals(4, port.cabin.value.fanSpeed)
        guard.restore()
        assertEquals(INITIAL_CABIN, port.cabin.value)
    }

    @Test
    fun `공조 전원이 켜져 있던 차는 전원을 그대로 둔다`() = runTest {
        val poweredOn = INITIAL_CABIN.copy(hvacPowerOn = true)
        port.setCabin(poweredOn)
        guard.begin(Intensity.HIGH)
        playWindScene()
        guard.restore()

        assertEquals(poweredOn, port.cabin.value)
        assertFalse(port.log.value.any { it.startsWith("hvacPower") })
    }

    @Test(expected = IllegalStateException::class)
    fun `Fake도 공조 전원이 꺼져 있으면 풍량을 바꿀 수 없다`() = runTest {
        port.setFanSpeed(3)
    }

    @Test
    fun `디버그 조작으로 정차가 깨진다`() = runTest {
        states.drive()
        assertFalse(guard.begin(Intensity.HIGH))
        states.park()
        assertTrue(guard.begin(Intensity.HIGH))
    }
}
