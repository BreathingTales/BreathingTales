package com.breathingtales.domain.safety

import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.port.SeatAxis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시트 들썩임 (기획서 5장 7번). 조수석([PASSENGER])만 연출 영역이고, 올리는 폭 상한은 범위 0~100의 10%.
 * 기본 가짜 시트는 1초에 50씩 매끄럽게 움직이고 명령이 바로 닿는다.
 * [emulatorSeat]는 Pleos 에뮬레이터처럼 움직이기 시작하고 100ms마다 5씩 가고, 명령이 늦게 닿는다 (2026-11-03 측정).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeatLiftTest {

    private class Rig {
        val states = FakeVehicleStates()
        val port = RecordingPort(
            capabilities = EMULATOR_CAPABILITIES.copy(seatLiftZones = listOf(PASSENGER), seatHeightRange = 0..100),
            snapshot = CabinSnapshot(hvacPowerOn = true),
        ).apply {
            seatHeights[PASSENGER] = 40
            occupiedAndBuckled += PASSENGER
        }
        // 시트 시간 상한도 가짜 시트와 같은 가상 시계로 잰다
        val guard = SafetyGuard(port, states, SafetyLimits(), clockMs = { port.now() })

        fun seatCalls() = port.calls.filter { it.startsWith("seatHeight") }
    }

    private val rig = Rig()
    private val port = rig.port
    private val guard = rig.guard
    private val states = rig.states
    private val lift = Effect.SeatLift(durationSec = 0.6f)

    private fun TestScope.useVirtualClock(r: Rig = rig) {
        r.port.now = { currentTime }
    }

    private fun seatCalls() = rig.seatCalls()

    private fun Rig.emulatorSeat(lagMs: Long, stopExtraLagMs: Long = 0) {
        port.seatSpeedPerSec = 50
        port.seatTickMs = 100
        port.seatCommandLagMs = lagMs
        port.seatStopExtraLagMs = stopExtraLagMs
    }

    /** 첫 명령은 UP이고, 움직이라는 명령마다 바로 STOP이 따른다 (멈추지 않은 채 끝나지 않는다) */
    private fun assertEveryMoveStopped(calls: List<String> = seatCalls()) {
        assertEquals("seatHeight[4]=UP", calls.first())
        calls.forEachIndexed { i, call ->
            if (!call.endsWith("=STOP")) assertEquals("$calls", "seatHeight[4]=STOP", calls.getOrNull(i + 1))
        }
    }

    /** 실제로 지나간 위치(읽기 사이 포함)가 시작 위치 아래로, 폭 상한 위로 가지 않았다 */
    private fun TestScope.assertStayedWithin(range: IntRange, r: Rig = rig) {
        val seen = r.port.seatExtremes(PASSENGER, currentTime)
        assertTrue("실제 위치 $seen 가 $range 안", seen.first >= range.first && seen.last <= range.last)
    }

    @Test
    fun `부모가 허용하지 않으면 시트를 움직이지 않는다`() = runTest {
        useVirtualClock()
        guard.begin(Intensity.HIGH)
        assertFalse(guard.apply(lift))
        assertEquals(emptyList<String>(), seatCalls())
    }

    @Test
    fun `위로 폭 상한까지 올렸다가 시작 위치로 되돌리고, 연출마다 한 번만 한다`() = runTest {
        useVirtualClock()
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertTrue(guard.apply(lift))

        assertEveryMoveStopped()
        assertStayedWithin(40..50)
        val trace = guard.monitor.value.seatLift!!
        assertEquals(SeatLiftOutcome.DONE, trace.outcome)
        assertEquals(40, trace.start)
        assertTrue(trace.peak!! in 47..50)
        assertTrue(trace.end!! in 40..43)

        val calls = seatCalls().size
        assertFalse(guard.apply(lift))
        assertEquals(SeatLiftOutcome.ALREADY_USED, guard.monitor.value.seatLift?.outcome)
        assertEquals(calls, seatCalls().size)
        assertEquals(emptyList<String>(), guard.restore())
    }

    @Test
    fun `에뮬레이터 시트도 STOP 지연을 보정해 10%를 다 올리고 시작 위치에 정확히 돌아온다`() = runTest {
        // 명령이 닿는 지연과 STOP만 더 늦는 편차를 바꿔 가며 (측정: STOP은 위치가 바뀐 걸 본 뒤 약 60ms에 닿음).
        // 지연이 짧으면 미리 멈춘 뒤 밀어서 채우고, 길면 늦게 멈춰 닿는다
        for (lag in listOf(0L, 30L, 60L, 90L)) for (extra in listOf(0L, 30L, 60L)) {
            val r = Rig().apply { emulatorSeat(lag, extra) }
            useVirtualClock(r)
            val begunAt = currentTime
            r.port.now = { currentTime - begunAt } // 경우마다 박자 위상을 같게
            r.guard.begin(Intensity.HIGH, allowSeatMotion = true)
            assertTrue(r.guard.apply(lift.copy(repeat = 3)))

            val trace = r.guard.monitor.value.seatLift!!
            assertEquals("지연 $lag+$extra", SeatLiftOutcome.DONE, trace.outcome)
            assertEquals("지연 $lag+$extra", 3, trace.done)
            assertEquals("지연 $lag+$extra: 10% 다 올라감", 50, trace.peak)
            assertEquals("지연 $lag+$extra: 시작 위치에 정확히", 40, trace.end)
            assertEveryMoveStopped(r.seatCalls())
            val seen = r.port.seatExtremes(PASSENGER, currentTime - begunAt)
            assertTrue("지연 $lag+$extra: 실제 위치 $seen", seen.first >= 40 && seen.last <= 50)
            assertEquals(emptyList<String>(), r.guard.restore())
        }
    }

    @Test
    fun `시작 위치보다 낮게 멈추면 성공으로 치지 않고 알린다`() = runTest {
        useVirtualClock()
        // 명령이 아주 늦게 닿는 시트: 미리 멈춰도 시작 위치를 지나친다
        rig.emulatorSeat(350)
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        guard.apply(lift.copy(repeat = 3))

        val trace = guard.monitor.value.seatLift!!
        assertEquals(SeatLiftOutcome.UNRESTORED, trace.outcome)
        assertTrue(trace.end!! < 40)
        assertEquals(0, trace.done)
        assertEquals(listOf("seatHeight[4]"), guard.restore())
    }

    @Test
    fun `올리는 시간은 1초를 넘지 않고, 느린 시트는 밀지 않는다`() = runTest {
        useVirtualClock()
        port.seatSpeedPerSec = 5 // 실차처럼 느린 시트: 1초에 5
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        guard.apply(Effect.SeatLift(durationSec = 1f).copy(durationSec = 5f)) // 검증을 거치지 않은 값이 와도

        // 1초에 5 → 짧게 밀어도 움직이지 않으므로 밀지 않는다
        assertEquals(listOf("seatHeight[4]=UP", "seatHeight[4]=STOP", "seatHeight[4]=DOWN", "seatHeight[4]=STOP"), seatCalls())
        assertEquals(45, guard.monitor.value.seatLift?.peak)
        assertTrue(port.seatHeights.getValue(PASSENGER) in 40..41)
        assertStayedWithin(40..45)
    }

    /** [axis] 첫 UP을 보낸 때부터 그 다음 STOP을 보낸 때까지 (ms) */
    private fun firstUpToStopMs(name: String): Long {
        val times = port.seatCommandTimes.filter { it.second.startsWith(name) }
        val up = times.first { it.second.endsWith("=UP") }.first
        return times.first { it.first >= up && it.second.endsWith("=STOP") }.first - up
    }

    @Test
    fun `첫 명령 응답이 늦어도 올리는 시간은 명령을 보낸 때부터 1초를 넘지 않는다`() = runTest {
        useVirtualClock()
        // 아주 느린 시트: 폭 상한(10)에도, 한 걸음 앞서 멈추기에도 걸리지 않아 시간 상한으로만 멈춘다
        port.seatSpeedPerSec = 2
        port.seatFirstReplyLagMs = 900 // 응답을 기다리는 동안 이미 움직인다 (에뮬레이터 첫 명령)
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        guard.apply(Effect.SeatLift(durationSec = 1f))

        // 응답 뒤부터 1초를 세면 UP 뒤 1.9초에 STOP을 보낸다
        val upMs = firstUpToStopMs("seatHeight")
        assertTrue("UP → STOP $upMs ms", upMs <= 1_000L)
        assertEquals(SeatLiftOutcome.DONE, guard.monitor.value.seatLift?.outcome)
        assertStayedWithin(40..42)
    }

    @Test
    fun `첫 명령 응답이 늦어도 기울기도 같은 시간 상한을 지킨다`() = runTest {
        useVirtualClock()
        rig.withTilt(30)
        port.seatSpeedPerSec = 2
        port.seatFirstReplyLagMs = 900
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        guard.apply(Effect.SeatLift(durationSec = 1f))

        val upMs = firstUpToStopMs("seatHeight")
        val tiltUpMs = firstUpToStopMs("seatTilt")
        assertTrue("높이 UP → STOP $upMs ms", upMs <= 1_000L)
        assertTrue("기울기 UP → STOP $tiltUpMs ms", tiltUpMs <= 1_000L)
        assertEquals(SeatLiftOutcome.DONE, guard.monitor.value.seatLift?.outcome)
        val tiltSeen = port.seatExtremes(PASSENGER, currentTime, SeatAxis.TILT)
        assertTrue("기울기 $tiltSeen", tiltSeen.first >= 30 && tiltSeen.last <= 32)
    }

    @Test
    fun `앉아서 벨트를 맨 사람이 없으면 움직이지 않는다`() = runTest {
        useVirtualClock()
        port.occupiedAndBuckled.clear()
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertFalse(guard.apply(lift))
        assertEquals(emptyList<String>(), seatCalls())
    }

    @Test
    fun `위치를 읽지 못하는 차는 건너뛴다`() = runTest {
        useVirtualClock()
        port.seatUnreadable = true
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertFalse(guard.apply(lift))
        assertEquals(SeatLiftOutcome.POSITION_UNREADABLE, guard.monitor.value.seatLift?.outcome)
        assertEquals(emptyList<String>(), seatCalls())
    }

    @Test
    fun `맨 아래(0) 시트도 올렸다가 0 근처로 되돌린다`() = runTest {
        useVirtualClock()
        port.seatHeights[PASSENGER] = 0
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertTrue(guard.apply(lift))
        assertEveryMoveStopped()
        assertStayedWithin(0..10)
        val trace = guard.monitor.value.seatLift!!
        assertEquals(SeatLiftOutcome.DONE, trace.outcome)
        assertEquals(0, trace.start)
        assertTrue(trace.end!! in 0..3)
    }

    @Test
    fun `에뮬레이터 시트 맨 아래(0)도 0으로 정확히 되돌린다`() = runTest {
        useVirtualClock()
        rig.emulatorSeat(60)
        port.seatHeights[PASSENGER] = 0
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertTrue(guard.apply(lift))
        assertEquals(SafetyMonitor.SeatLiftControl(SeatLiftOutcome.DONE, 0, 10, 0, 1, 1), guard.monitor.value.seatLift)
        assertStayedWithin(0..10)
    }

    @Test
    fun `맨 위라 올릴 곳이 없으면 건너뛴다`() = runTest {
        useVirtualClock()
        port.seatHeights[PASSENGER] = 100
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertFalse(guard.apply(lift))
        assertEquals(SeatLiftOutcome.AT_TOP, guard.monitor.value.seatLift?.outcome)
        assertEquals(emptyList<String>(), seatCalls())
    }

    @Test
    fun `repeat번 되풀이하고, 매번 시작 위치로 돌아온 뒤 다시 올린다`() = runTest {
        useVirtualClock()
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertTrue(guard.apply(lift.copy(repeat = 3)))

        assertEveryMoveStopped()
        assertStayedWithin(40..50)
        val trace = guard.monitor.value.seatLift!!
        assertEquals(SeatLiftOutcome.DONE, trace.outcome)
        assertEquals(3, trace.done)
        assertEquals(3, trace.repeat)
        assertTrue(trace.end!! in 40..43)
        assertEquals(emptyList<String>(), guard.restore())
    }

    @Test
    fun `되풀이 횟수는 3번을 넘지 않는다`() = runTest {
        useVirtualClock()
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        guard.apply(lift.copy(repeat = 10)) // 검증을 거치지 않은 값이 와도
        assertEquals(3, guard.monitor.value.seatLift?.repeat)
        assertEquals(3, guard.monitor.value.seatLift?.done)
    }

    /** 같은 조건에서 한 번만 했을 때 (걸린 시간, 시트 명령). 가짜 시트·가상 시계라 매번 같다 */
    private fun TestScope.oneBob(): Pair<Long, List<String>> {
        val r = Rig()
        val begunAt = currentTime
        r.port.now = { currentTime - begunAt }
        var took = 0L
        val job = async {
            r.guard.begin(Intensity.HIGH, allowSeatMotion = true)
            r.guard.apply(lift)
            took = currentTime - begunAt
        }
        advanceTimeBy(10_000); runCurrent()
        assertTrue(job.isCompleted)
        return took to r.seatCalls()
    }

    @Test
    fun `되풀이 중에 정차가 깨지면 지금 번을 되돌리고 더 하지 않는다`() = runTest {
        val oneBob = oneBob().first
        val begunAt = currentTime
        port.now = { currentTime - begunAt }
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        val pulse = async { guard.apply(lift.copy(repeat = 3)) }
        advanceTimeBy(oneBob + 150 + 60); runCurrent() // 첫 번과 쉬는 시간(150ms)을 지나 두 번째를 올리는 중
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        pulse.await()

        assertEquals("seatHeight[4]=STOP", seatCalls().last())
        assertTrue(port.seatHeights.getValue(PASSENGER) in 40..43)
        // 올리던 두 번째도 되돌렸으므로 2번, 세 번째는 하지 않음
        assertEquals(SeatLiftOutcome.DONE, guard.monitor.value.seatLift?.outcome)
        assertEquals(2, guard.monitor.value.seatLift?.done)
    }

    @Test
    fun `쉬는 사이에 정차가 깨지면 다음 번을 올리지 않는다`() = runTest {
        val oneBob = oneBob().first
        val begunAt = currentTime
        port.now = { currentTime - begunAt }
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        val pulse = async { guard.apply(lift.copy(repeat = 3)) }
        advanceTimeBy(oneBob + 50); runCurrent() // 첫 번을 마치고 쉬는 중(150ms)
        val callsAfterFirst = seatCalls()
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        pulse.await()

        assertEquals(callsAfterFirst, seatCalls())
        assertEquals(SeatLiftOutcome.DONE, guard.monitor.value.seatLift?.outcome)
        assertEquals(1, guard.monitor.value.seatLift?.done)
        assertEquals(emptyList<String>(), guard.restore())
    }

    @Test
    fun `되풀이 중에 취소되면 지금 번을 되돌리고 더 하지 않는다`() = runTest {
        val (oneBob, oneBobCalls) = oneBob()
        val begunAt = currentTime
        port.now = { currentTime - begunAt }
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        val pulse = async { guard.apply(lift.copy(repeat = 3)) }
        advanceTimeBy(100); runCurrent() // 첫 번을 올리는 중
        pulse.cancel()
        runCurrent(); advanceTimeBy(oneBob + 5_000); runCurrent()

        // 첫 번은 끝까지(되돌리기까지) 하고 두 번째는 올리지 않는다: 한 번만 한 것과 같은 명령
        assertEquals(oneBobCalls, seatCalls())
        assertTrue(port.seatHeights.getValue(PASSENGER) in 40..43)
        assertEquals(emptyList<String>(), guard.restore())
    }

    @Test
    fun `올리는 중에 정차가 깨지면 바로 멈추고 되돌린다`() = runTest {
        useVirtualClock()
        port.seatSpeedPerSec = 20
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        val pulse = async { guard.apply(lift) }
        advanceTimeBy(120); runCurrent()
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        pulse.await()

        assertEveryMoveStopped()
        assertEquals("정차가 아니면 더 올리지 않음", 1, seatCalls().count { it.endsWith("=UP") })
        // 0.6초를 다 올렸다면 올리기 0.6 + 멈춤 확인 0.3 + 되돌리기 0.6 + 멈춤 확인 0.3 = 1.8초
        assertTrue("0.6초를 다 채우지 않았다: $currentTime", currentTime < 1_500L)
        assertTrue(port.seatHeights.getValue(PASSENGER) in 40..43)
    }

    // 방석 기울기: 지원하면 높이와 함께 올렸다가 함께 시작 위치로 되돌린다

    private fun Rig.withTilt(start: Int = 30) = apply {
        port.capabilities = port.capabilities.copy(seatTiltRange = 0..100)
        port.seatTilts[PASSENGER] = start
    }

    private fun Rig.tiltCalls() = port.calls.filter { it.startsWith("seatTilt") }

    /** 첫 명령은 UP이고, 움직이라는 명령마다 바로 STOP이 따른다 */
    private fun assertEveryTiltStopped(calls: List<String>) {
        assertEquals("seatTilt[4]=UP", calls.first())
        calls.forEachIndexed { i, call ->
            if (!call.endsWith("=STOP")) assertEquals("$calls", "seatTilt[4]=STOP", calls.getOrNull(i + 1))
        }
    }

    @Test
    fun `기울기를 지원하면 높이와 함께 올렸다가 둘 다 시작 위치로 되돌린다`() = runTest {
        useVirtualClock()
        rig.withTilt(30)
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertTrue(guard.apply(lift.copy(repeat = 3)))

        assertEveryMoveStopped()
        assertEveryTiltStopped(rig.tiltCalls())
        // 높이와 기울기의 첫 UP이 다른 명령 사이에 끼지 않고 나란히 나간다
        val firstUp = port.calls.indexOf("seatHeight[4]=UP")
        assertEquals("seatTilt[4]=UP", port.calls[firstUp + 1])
        assertStayedWithin(40..50)
        val tiltSeen = port.seatExtremes(PASSENGER, currentTime, SeatAxis.TILT)
        assertTrue("기울기 $tiltSeen 가 30..40 안", tiltSeen.first >= 30 && tiltSeen.last <= 40)
        val trace = guard.monitor.value.seatLift!!
        assertEquals(SeatLiftOutcome.DONE, trace.outcome)
        assertEquals(3, trace.done)
        val tilt = trace.tilt!!
        assertEquals(30, tilt.start)
        assertTrue(tilt.peak!! in 37..40)
        assertTrue(tilt.end!! in 30..33)
        assertEquals(emptyList<String>(), guard.restore())
    }

    @Test
    fun `에뮬레이터 시트도 기울기까지 10%를 올리고 시작 위치에 정확히 돌아온다`() = runTest {
        for (lag in listOf(0L, 60L)) for (extra in listOf(0L, 60L)) {
            val r = Rig().apply { emulatorSeat(lag, extra) }.withTilt(30)
            useVirtualClock(r)
            val begunAt = currentTime
            r.port.now = { currentTime - begunAt }
            r.guard.begin(Intensity.HIGH, allowSeatMotion = true)
            assertTrue(r.guard.apply(lift.copy(repeat = 3)))

            val trace = r.guard.monitor.value.seatLift!!
            assertEquals("지연 $lag+$extra", SeatLiftOutcome.DONE, trace.outcome)
            assertEquals("지연 $lag+$extra", SafetyMonitor.SeatPositions(30, 40, 30), trace.tilt)
            assertEquals("지연 $lag+$extra", 40, trace.end)
            assertEveryTiltStopped(r.tiltCalls())
            val seen = r.port.seatExtremes(PASSENGER, currentTime - begunAt, SeatAxis.TILT)
            assertTrue("지연 $lag+$extra: 실제 기울기 $seen", seen.first >= 30 && seen.last <= 40)
        }
    }

    @Test
    fun `기울기 위치를 읽지 못하거나 이미 맨 위면 높이만 움직인다`() = runTest {
        for (case in listOf("unreadable", "top")) {
            val r = Rig().withTilt(if (case == "top") 100 else 30)
            if (case == "unreadable") r.port.seatTiltUnreadable = true
            useVirtualClock(r)
            r.guard.begin(Intensity.HIGH, allowSeatMotion = true)
            assertTrue(case, r.guard.apply(lift))

            val trace = r.guard.monitor.value.seatLift!!
            assertEquals(case, SeatLiftOutcome.DONE, trace.outcome)
            assertEquals(case, null, trace.tilt)
            assertEquals(case, emptyList<String>(), r.tiltCalls())
            assertEveryMoveStopped(r.seatCalls())
        }
    }

    @Test
    fun `기울기를 되돌리지 못하면 높이는 되돌리고 기울기만 복원 실패로 알린다`() = runTest {
        useVirtualClock()
        rig.withTilt(30)
        port.failing += "seatTilt[4]=DOWN"
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        guard.apply(lift.copy(repeat = 3))

        val trace = guard.monitor.value.seatLift!!
        assertEquals(SeatLiftOutcome.UNRESTORED, trace.outcome)
        assertEquals("첫 번에서 멈추고 더 하지 않음", 0, trace.done)
        assertTrue(port.seatHeights.getValue(PASSENGER) in 40..43)
        assertEquals("seatTilt[4]=STOP", rig.tiltCalls().last())
        assertEquals(listOf("seatTilt[4]"), guard.restore())
    }

    @Test
    fun `기울기 명령이 거부되면 높이만 들썩이고 실패로 치지 않는다`() = runTest {
        useVirtualClock()
        rig.withTilt(30)
        port.failing += "seatTilt[4]=UP"
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        assertTrue(guard.apply(lift))

        val trace = guard.monitor.value.seatLift!!
        assertEquals(SeatLiftOutcome.DONE, trace.outcome)
        assertEquals(SafetyMonitor.SeatPositions(30, 30, 30), trace.tilt)
        assertTrue(trace.peak!! in 47..50)
        assertEquals(emptyList<String>(), guard.restore())
    }

    @Test
    fun `올리는 중에 정차가 깨지면 높이와 기울기 모두 바로 멈추고 되돌린다`() = runTest {
        useVirtualClock()
        rig.withTilt(30)
        port.seatSpeedPerSec = 20
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        val pulse = async { guard.apply(lift) }
        advanceTimeBy(120); runCurrent()
        states.state.value = PARKED.copy(gear = Gear.DRIVE)
        pulse.await()

        assertEveryMoveStopped()
        assertEveryTiltStopped(rig.tiltCalls())
        assertEquals(1, rig.tiltCalls().count { it.endsWith("=UP") })
        assertTrue("0.6초를 다 채우지 않았다: $currentTime", currentTime < 1_500L)
        assertTrue(port.seatHeights.getValue(PASSENGER) in 40..43)
        assertTrue(port.seatTilts.getValue(PASSENGER) in 30..33)
    }

    @Test
    fun `올렸는데 위치가 그대로면 모른 채 내리지 않고 복원 실패로 알린다`() = runTest {
        useVirtualClock()
        port.seatSpeedPerSec = 0 // 움직이지 않거나 위치 읽기가 멈춤
        guard.begin(Intensity.HIGH, allowSeatMotion = true)
        guard.apply(lift)

        assertEquals(listOf("seatHeight[4]=UP", "seatHeight[4]=STOP"), seatCalls())
        assertEquals(listOf("seatHeight[4]"), guard.restore())
    }
}
