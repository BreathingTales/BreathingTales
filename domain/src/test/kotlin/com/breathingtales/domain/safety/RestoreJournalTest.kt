package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.port.RestoreRecord
import com.breathingtales.domain.port.SeatAxis
import com.breathingtales.domain.port.SeatInMotion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 연출 중 앱 프로세스가 죽어도(강제 종료·재설치) 다음 실행에서 실내를 되돌린다 */
class RestoreJournalTest {

    private val start = CabinSnapshot(
        hvacPowerOn = false,
        ambientMode = AmbientMode.ON,
        ambientColorRgb = 0xFFFFFF,
        ambientBrightness = 70,
        cabinLight = LampSwitch.ON,
        readingLights = mapOf(REAR_LEFT to LampSwitch.OFF, REAR_RIGHT to LampSwitch.OFF),
    )
    private val powered = CabinSnapshot(hvacPowerOn = true, fanSpeed = 3, airflow = Airflow.FLOOR, temperaturesC = mapOf(DRIVER to 22f))

    private val states = FakeVehicleStates()
    private val journal = MemoryJournal()
    private val port = RecordingPort(
        capabilities = EMULATOR_CAPABILITIES.copy(cabinLight = true, readingLightZones = listOf(REAR_LEFT, REAR_RIGHT)),
        snapshot = start,
        snapshotAfterPowerOn = start.copy(hvacPowerOn = true, fanSpeed = 3, airflow = Airflow.FLOOR, temperaturesC = mapOf(DRIVER to 22f)),
    )
    private val guard = SafetyGuard(port, states, journal = journal)

    /** 같은 차·같은 기록으로 앱을 다시 켠다 */
    private fun relaunch() = SafetyGuard(port, states, journal = journal)

    /** 바람·온도·조명·실내등을 바꾼 연출 중에 앱이 죽는다 */
    private suspend fun dieMidStory() {
        guard.begin(Intensity.HIGH)
        guard.apply(Effect.FanLevel(5))
        guard.apply(Effect.TemperatureDelta(-1.5f))
        guard.apply(Effect.Ambient(0x4A90E2, 0.6f))
        guard.apply(Effect.CabinLight(false))
        // 죽은 뒤 차에 남은 값
        port.snapshot = powered.copy(ambientMode = AmbientMode.ON, ambientColorRgb = 0x4A90E2, ambientBrightness = 60, cabinLight = LampSwitch.OFF)
        port.snapshotAfterPowerOn = null
        port.calls.clear()
    }

    @Test
    fun `무엇이든 바꾸기 전에 시작 상태를 남기고, 끝까지 되돌리면 지운다`() = runTest {
        guard.begin(Intensity.HIGH)
        // 첫 기록은 공조 전원을 켜기 전 (켠 직후 죽어도 끌 수 있게)
        assertEquals(false, journal.history.first()!!.cabin.hvacPowerOn)
        assertEquals(22f, journal.record!!.cabin.temperaturesC[DRIVER])
        guard.apply(Effect.FanLevel(5))
        guard.restore()
        assertNull(journal.record)
    }

    @Test
    fun `등은 바꾼 것만 기록한다`() = runTest {
        guard.begin(Intensity.HIGH)
        assertNull(journal.record!!.cabin.cabinLight)
        assertEquals(emptyMap<Any, Any>(), journal.record!!.cabin.readingLights)
        guard.apply(Effect.CabinLight(false))
        assertEquals(LampSwitch.ON, journal.record!!.cabin.cabinLight)
        assertEquals(emptyMap<Any, Any>(), journal.record!!.cabin.readingLights)
    }

    @Test
    fun `연출 중 앱이 죽으면 다음 실행에서 시작 전 값으로 되돌리고 기록을 지운다`() = runTest {
        dieMidStory()
        val recovered = relaunch().recoverAfterRestart()

        assertTrue("cabinLight" in recovered)
        assertEquals(
            listOf(
                "fanSpeed=3", "airflow=FLOOR", "temperature[1]=22.0",
                "ambientColor=FFFFFF", "ambientBrightness=70", "ambientMode=ON", "cabinLight=ON", "hvacPower=false",
            ),
            port.calls,
        )
        assertNull(journal.record)
    }

    @Test
    fun `남긴 기록이 없으면 아무것도 보내지 않는다`() = runTest {
        assertEquals(emptyList<String>(), guard.recoverAfterRestart())
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun `주행 중에 다시 켜지면 등만 남겨 두었다가 정차하면 되돌린다`() = runTest {
        dieMidStory()
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        port.failing += "cabinLight"
        val next = relaunch()
        backgroundScope.launch { next.watchPendingRestore() }
        next.recoverAfterRestart()
        assertTrue("fanSpeed=3" in port.calls && "hvacPower=false" in port.calls)
        assertEquals(listOf("cabinLight"), next.pendingRestore.value)
        // 다시 켜져도 등은 남아 있다
        assertEquals(LampSwitch.ON, relaunch().let { journal.record!!.cabin.cabinLight })

        port.failing.clear()
        port.calls.clear()
        states.state.value = PARKED
        runCurrent()
        assertEquals(listOf("cabinLight=ON"), port.calls)
        assertEquals(emptyList<String>(), next.pendingRestore.value)
        assertNull(journal.record)
    }

    @Test
    fun `되돌리기 전에 다음 연출을 시작하면 남은 값을 시작 상태로 이어받는다`() = runTest {
        dieMidStory()
        port.failing += listOf("fanSpeed", "ambientColor", "cabinLight", "hvacPower") // 차량 연결 전이라 일부 실패
        val next = relaunch()
        next.begin(Intensity.HIGH)

        port.failing.clear()
        port.calls.clear()
        next.restore()
        // 지금 읽은 연출 값(풍량 3이 아닌 값, 파란 조명, 실내등 꺼짐)이 아니라 처음 시작 상태로
        assertTrue(port.calls.containsAll(listOf("fanSpeed=3", "ambientColor=FFFFFF", "cabinLight=ON", "hvacPower=false")))
        assertNull(journal.record)
    }

    @Test
    fun `들썩이던 중 죽었으면 다음 실행에서 시트부터 멈춘다`() = runTest {
        journal.record = RestoreRecord(
            CabinSnapshot(fanSpeed = 3),
            seatMoving = SeatInMotion(PASSENGER, mapOf(SeatAxis.HEIGHT to 40, SeatAxis.TILT to 20)),
        )
        guard.recoverAfterRestart()
        assertEquals(listOf("seatHeight[4]=STOP", "seatTilt[4]=STOP", "fanSpeed=3"), port.calls)
        assertNull(journal.record)
    }

    @Test
    fun `시트를 들썩이는 동안만 움직임을 기록한다`() = runTest {
        port.capabilities = port.capabilities.copy(seatLiftZones = listOf(PASSENGER), seatHeightRange = 0..100)
        port.seatHeights[PASSENGER] = 40
        port.occupiedAndBuckled += PASSENGER
        port.now = { testScheduler.currentTime }
        val seatGuard = SafetyGuard(port, states, SafetyLimits(), clockMs = { testScheduler.currentTime }, journal = journal)
        seatGuard.begin(Intensity.HIGH, allowSeatMotion = true)
        seatGuard.apply(Effect.SeatLift(durationSec = 0.6f))
        assertTrue(journal.history.any { it?.seatMoving == SeatInMotion(PASSENGER, mapOf(SeatAxis.HEIGHT to 40)) })
        assertNull(journal.record!!.seatMoving)
    }
}
