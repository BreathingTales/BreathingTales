package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.port.VehicleEffectPort
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {

    /** 공조 전원이 켜진 상태에서 시작 */
    private val poweredOn = CabinSnapshot(
        hvacPowerOn = true,
        fanSpeed = 3,
        airflow = Airflow.FLOOR,
        temperaturesC = mapOf(DRIVER to 22f, PASSENGER to 23f),
        ambientMode = AmbientMode.AUTO,
        ambientColorRgb = 0xFFFFFF,
        ambientBrightness = 40,
        ambientAutoByDriveMode = true,
        seatHeatLevels = mapOf(REAR_LEFT to 0, REAR_RIGHT to 0),
    )

    private val states = FakeVehicleStates()
    private val port = RecordingPort(snapshot = poweredOn)
    private val guard = SafetyGuard(port, states)

    @Test
    fun `정차가 아니면 시작하지 않고 아무것도 바꾸지 않는다`() = runTest {
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        assertFalse(guard.begin(Intensity.HIGH))
        assertFalse(guard.apply(Effect.FanLevel(3)))
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun `범위 밖 요청은 클램프되어 나간다`() = runTest {
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.FanLevel(5))
        guard.apply(Effect.TemperatureDelta(2f))
        guard.apply(Effect.SeatHeat(3))
        assertEquals(
            listOf(
                "fanSpeed=6",
                "temperature[1]=24.0", "temperature[4]=25.0",
                "seatHeat[16]=1", "seatHeat[64]=1",
            ),
            port.calls,
        )
    }

    @Test
    fun `공조 전원이 꺼져 있으면 켠 직후 온도를 읽어 기준값으로 쓴다`() = runTest {
        port.snapshot = CabinSnapshot(hvacPowerOn = false)
        port.snapshotAfterPowerOn = CabinSnapshot(hvacPowerOn = true, temperaturesC = mapOf(DRIVER to 21f))
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.TemperatureDelta(-1f))
        assertEquals(listOf("hvacPower=true", "temperature[1]=20.0"), port.calls)
    }

    @Test
    fun `조명은 처음 한 번 자동색을 끄고 켠다`() = runTest {
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.Ambient(0x4A90E2, 0.6f))
        guard.apply(Effect.Ambient(0xFFD54F, 0.8f))
        assertEquals(
            listOf(
                "ambientAuto=false", "ambientMode=ON",
                "ambientColor=4A90E2", "ambientBrightness=60",
                "ambientColor=FFD54F", "ambientBrightness=80",
            ),
            port.calls,
        )
    }

    @Test
    fun `연출 중 정차가 깨지면 제어를 거부한다`() = runTest {
        guard.begin(Intensity.HIGH)
        states.state.value = PARKED.copy(doorsClosed = false)
        assertFalse(guard.apply(Effect.FanLevel(3)))
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun `복원은 저장된 값으로 나간다`() = runTest {
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.FanLevel(5))
        port.calls.clear()

        assertEquals(emptyList<String>(), guard.restore())
        assertEquals(
            listOf(
                "fanSpeed=3", "airflow=FLOOR",
                "temperature[1]=22.0", "temperature[4]=23.0",
                "ambientColor=FFFFFF", "ambientBrightness=40", "ambientMode=AUTO", "ambientAuto=true",
                "seatHeat[16]=0", "seatHeat[64]=0",
            ),
            port.calls,
        )
    }

    @Test
    fun `원래 전원이 꺼져 있었으면 켠 직후 읽은 공조 값으로 되돌리고 전원은 마지막에 끈다`() = runTest {
        port.snapshot = CabinSnapshot(hvacPowerOn = false, ambientBrightness = 40)
        port.snapshotAfterPowerOn = CabinSnapshot(
            hvacPowerOn = true,
            fanSpeed = 2,
            airflow = Airflow.FACE_AND_FLOOR,
            temperaturesC = mapOf(DRIVER to 21f),
            ambientBrightness = 99, // 처음 읽은 값이 남아야 함
        )
        guard.begin(Intensity.HIGH)
        port.calls.clear()

        guard.restore()
        assertEquals(
            listOf("fanSpeed=2", "airflow=FACE_AND_FLOOR", "temperature[1]=21.0", "ambientBrightness=40", "hvacPower=false"),
            port.calls,
        )
    }

    @Test
    fun `복원은 한 항목이 실패해도 나머지를 계속한다`() = runTest {
        port.snapshot = poweredOn.copy(hvacPowerOn = false)
        guard.begin(Intensity.HIGH)
        port.failing += "airflow"
        port.failing += "temperature[1]"
        port.calls.clear()

        assertEquals(listOf("airflow", "temperature[1]"), guard.restore())
        assertEquals("hvacPower=false", port.calls.last())
        assertEquals(11, port.calls.size)
    }

    @Test
    fun `호출한 코루틴이 취소돼도 복원을 끝까지 한다`() = runTest {
        guard.begin(Intensity.HIGH)
        val show = launch {
            try {
                awaitCancellation()
            } finally {
                guard.restore()
            }
        }
        testScheduler.runCurrent()
        show.cancel()
        show.join()
        assertEquals("seatHeat[64]=0", port.calls.last())
    }

    @Test
    fun `복원 뒤에는 제어하지 않고 다시 시작할 수 있다`() = runTest {
        guard.begin(Intensity.HIGH)
        guard.restore()
        port.calls.clear()
        assertFalse(guard.apply(Effect.FanLevel(3)))
        assertEquals(emptyList<String>(), guard.restore())
        assertEquals(emptyList<String>(), port.calls)
        assertTrue(guard.begin(Intensity.LOW))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `단계 효과는 엔진이 절대값으로 바꿔 보내야 한다`() = runTest {
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.FanLevelStep(1))
    }

    @Test
    fun `창문 관련 효과와 제어는 모델에 존재하지 않는다`() {
        val names = Effect::class.java.declaredClasses.map { it.simpleName } +
            VehicleEffectPort::class.java.methods.map { it.name }
        assertTrue(names.isNotEmpty())
        assertTrue(names.toString(), names.none { it.contains("window", ignoreCase = true) })
    }

    @Test
    fun `시연 HUD용으로 요청 값과 줄여서 보낸 값을 함께 기록한다`() = runTest {
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.FanLevel(5))
        guard.apply(Effect.TemperatureDelta(-2f))
        guard.apply(Effect.SeatHeat(3))
        guard.apply(Effect.Ambient(0x4A90E2, 0.6f))

        val m = guard.monitor.value
        assertTrue(m.active)
        // 풍량 1~8의 80% → 6
        assertEquals(SafetyMonitor.FanControl(5, 6, 6), m.fan)
        assertEquals(SafetyMonitor.TemperatureControl(-2f, mapOf(DRIVER to (22f to 20f), PASSENGER to (23f to 21f))), m.temperature)
        assertEquals(SafetyMonitor.SeatHeatControl(3, 1), m.seatHeat)
        assertEquals(SafetyMonitor.AmbientControl(0x4A90E2, 0.6f, 60), m.ambient)
        guard.apply(Effect.SeatLift(0.5f))
        assertEquals(SafetyMonitor.SeatLiftControl(SeatLiftOutcome.DISABLED), guard.monitor.value.seatLift)

        guard.restore()
        assertFalse(guard.monitor.value.active)
        assertEquals(emptyList<String>(), guard.monitor.value.restoreFailures)
    }

    private val lightCapabilities = EMULATOR_CAPABILITIES.copy(cabinLight = true, readingLightZones = listOf(REAR_LEFT, REAR_RIGHT))

    @Test
    fun `실내등과 독서등은 바꾼 것만 시작 상태로 되돌린다`() = runTest {
        port.capabilities = lightCapabilities
        port.snapshot = poweredOn.copy(
            cabinLight = LampSwitch.AUTO,
            readingLights = mapOf(REAR_LEFT to LampSwitch.OFF, REAR_RIGHT to LampSwitch.ON),
        )
        guard.begin(Intensity.HIGH)
        assertTrue(guard.apply(Effect.ReadingLight(true)))
        port.calls.clear()
        guard.restore()
        val lampCalls = port.calls.filter { it.startsWith("cabinLight") || it.startsWith("readingLight") }
        assertEquals(listOf("readingLight[16]=OFF", "readingLight[64]=ON"), lampCalls)
    }

    @Test
    fun `실내등을 끄고 나중에 시작 상태로 켠다`() = runTest {
        port.capabilities = lightCapabilities
        port.snapshot = poweredOn.copy(cabinLight = LampSwitch.ON)
        guard.begin(Intensity.HIGH)
        assertTrue(guard.apply(Effect.CabinLight(false)))
        assertEquals(SafetyMonitor.LampControl(requestedOn = false, sent = true), guard.monitor.value.cabinLight)
        guard.restore()
        assertEquals(listOf("cabinLight=OFF", "cabinLight=ON"), port.calls.filter { it.startsWith("cabinLight") })
    }

    @Test
    fun `시작 상태를 읽지 못한 조명은 되돌릴 수 없으므로 바꾸지 않는다`() = runTest {
        port.capabilities = lightCapabilities
        port.snapshot = poweredOn.copy(cabinLight = null, readingLights = mapOf(REAR_LEFT to LampSwitch.OFF))
        guard.begin(Intensity.HIGH)
        assertFalse(guard.apply(Effect.CabinLight(false)))
        assertTrue(guard.apply(Effect.ReadingLight(true)))
        assertEquals(listOf("readingLight[16]=ON"), port.calls.filter { it.startsWith("cabinLight") || it.startsWith("readingLight") })
    }

    @Test
    fun `조명을 지원하지 않는 차는 건너뛴다`() = runTest {
        port.snapshot = poweredOn.copy(cabinLight = LampSwitch.OFF, readingLights = mapOf(REAR_LEFT to LampSwitch.OFF))
        guard.begin(Intensity.HIGH)
        assertFalse(guard.apply(Effect.CabinLight(false)))
        assertFalse(guard.apply(Effect.ReadingLight(true)))
        assertEquals(SafetyMonitor.LampControl(requestedOn = true, sent = false), guard.monitor.value.readingLight)
        guard.restore()
        assertTrue(port.calls.none { it.startsWith("cabinLight") || it.startsWith("readingLight") })
    }

    // ---- 기어를 바꿔 중단했을 때 등 복원 (P가 아니면 차가 등 스위치를 거부) ----

    /** 실내등을 켠 연출을 기어 D에서 복원한다. 차는 등 스위치만 거부한다 */
    private suspend fun restoreInDriveWithLampRejected(): List<String> {
        port.capabilities = lightCapabilities
        port.snapshot = poweredOn.copy(cabinLight = LampSwitch.OFF, readingLights = mapOf(REAR_LEFT to LampSwitch.OFF))
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.CabinLight(true))
        guard.apply(Effect.ReadingLight(true))
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        port.failing += listOf("cabinLight", "readingLight")
        return guard.restore()
    }

    @Test
    fun `기어를 바꿔 등을 되돌리지 못하면 다시 정차할 때 되돌린다`() = runTest {
        backgroundScope.launch { guard.watchPendingRestore() }
        assertEquals(listOf("cabinLight", "readingLight[16]"), restoreInDriveWithLampRejected())
        assertEquals(listOf("cabinLight", "readingLight[16]"), guard.pendingRestore.value)

        port.failing.clear()
        port.calls.clear()
        states.state.value = PARKED
        runCurrent()

        assertEquals(listOf("cabinLight=OFF", "readingLight[16]=OFF"), port.calls)
        assertEquals(emptyList<String>(), guard.pendingRestore.value)
        assertEquals(emptyList<String>(), guard.monitor.value.restoreFailures)
    }

    @Test
    fun `정차가 아닌 동안에는 등을 다시 보내지 않는다`() = runTest {
        backgroundScope.launch { guard.watchPendingRestore() }
        restoreInDriveWithLampRejected()
        port.calls.clear()
        states.state.value = PARKED.copy(gear = Gear.DRIVE, speedMps = 5f)
        runCurrent()
        assertEquals(emptyList<String>(), port.calls)
        assertEquals(listOf("cabinLight", "readingLight[16]"), guard.pendingRestore.value)
    }

    @Test
    fun `되돌리기 전에 다음 연출을 시작하면 남은 등을 이어받아 그 연출이 끝날 때 되돌린다`() = runTest {
        restoreInDriveWithLampRejected()
        // 지난 연출이 켠 실내등이 그대로라 지금 읽으면 켜짐이다. 다시 정차했지만 차는 여전히 거부한다
        port.snapshot = port.snapshot.copy(cabinLight = LampSwitch.ON, readingLights = mapOf(REAR_LEFT to LampSwitch.ON))
        states.state.value = PARKED
        assertTrue(guard.begin(Intensity.HIGH))
        assertEquals(emptyList<String>(), guard.pendingRestore.value)

        port.failing.clear()
        port.calls.clear()
        assertEquals(emptyList<String>(), guard.restore())
        // 이번 연출이 등을 건드리지 않았어도, 켜짐이 아니라 처음 시작 상태(꺼짐)로 되돌린다
        assertEquals(listOf("cabinLight=OFF", "readingLight[16]=OFF"), port.calls.filter { it.contains("Light") })
    }

    // ---- 무드등 전환 (fadeSec) ----

    @Test
    fun `시작 전 무드등이 켜져 있지 않았으면 전환은 목표 색의 가장 어두운 밝기에서 출발한다`() = runTest {
        guard.begin(Intensity.HIGH) // poweredOn의 무드등은 AUTO
        val fade = guard.ambientFade(Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f))!!
        assertEquals(AmbientValue(0x0000FF, 0), fade.from)
        assertEquals(AmbientValue(0x0000FF, 80), fade.to)
        assertEquals(AmbientValue(0x0000FF, 40), fade.at(0.5f))
    }

    @Test
    fun `전환 중간값은 직전에 보낸 값과 같은 항목을 다시 보내지 않는다`() = runTest {
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.Ambient(0x0000FF, 0.4f))
        port.calls.clear()
        val fade = guard.ambientFade(Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f))!!
        assertEquals(AmbientValue(0x0000FF, 40), fade.from)

        assertTrue(guard.applyAmbientFade(fade, 0f))
        assertTrue(guard.applyAmbientFade(fade, 0.5f))
        assertEquals(listOf("ambientBrightness=60"), port.calls)
        // 시연 HUD에는 요청 값과 지금 보낸 밝기
        assertEquals(SafetyMonitor.AmbientControl(0x0000FF, 0.8f, 60), guard.monitor.value.ambient)
    }

    @Test
    fun `복원 전환은 시작 전에 꺼져 있던 무드등이면 가장 어둡게 낮추고, 조명을 바꾼 적이 없으면 없다`() = runTest {
        guard.begin(Intensity.HIGH)
        assertEquals(null, guard.ambientRestoreFade())
        guard.apply(Effect.Ambient(0x0000FF, 0.8f))
        val fade = guard.ambientRestoreFade()!!
        assertEquals(AmbientValue(0x0000FF, 80), fade.from)
        assertEquals(AmbientValue(0x0000FF, 0), fade.to)
    }

    @Test
    fun `연출이 끝났거나 정차가 아니면 전환 값을 보내지 않는다`() = runTest {
        guard.begin(Intensity.HIGH)
        val fade = guard.ambientFade(Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f))!!
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        assertFalse(guard.applyAmbientFade(fade, 0.5f))
        states.state.value = PARKED
        guard.restore()
        port.calls.clear()
        assertFalse(guard.applyAmbientFade(fade, 1f))
        assertEquals(null, guard.ambientFade(Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f)))
        assertEquals(emptyList<String>(), port.calls)
    }
}
