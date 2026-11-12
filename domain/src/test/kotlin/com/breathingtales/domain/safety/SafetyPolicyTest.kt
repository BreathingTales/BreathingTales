package com.breathingtales.domain.safety

import com.breathingtales.domain.model.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {

    private fun policy(intensity: Intensity = Intensity.HIGH) = SafetyPolicy(EMULATOR_CAPABILITIES, intensity)

    @Test
    fun `풍량은 어떤 단계와 강도에서도 허용 풍량(최대의 80퍼센트)을 넘지 않는다`() {
        Intensity.entries.forEach { intensity ->
            val speeds = (0..Effect.FanLevel.MAX).map { policy(intensity).fanSpeed(it)!! }
            assertTrue("$intensity $speeds", speeds.all { it in 1..6 })
            assertEquals("$intensity 단계가 오르면 줄지 않음", speeds.sorted(), speeds)
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), (0..5).map { policy().fanSpeed(it) })
    }

    @Test
    fun `강도는 풍량의 위쪽 끝을 최저 풍량과 허용 풍량 사이에 둔다`() {
        // 1~8: 허용 6. 약 1+5/3, 중 1+5/2, 강 6
        assertEquals(6, policy(Intensity.HIGH).fanSpeed(5))
        assertEquals(4, policy(Intensity.MEDIUM).fanSpeed(5))
        assertEquals(3, policy(Intensity.LOW).fanSpeed(5))
        assertEquals("0단계는 최저 풍량", 1, policy(Intensity.LOW).fanSpeed(0))
    }

    @Test
    fun `최저 풍량이 높은 차에서도 약 강도의 바람이 최저 풍량에 붙지 않는다`() {
        // 4~10: 허용 8. 약 4+4/3, 중 4+4/2, 강 8
        val caps = EMULATOR_CAPABILITIES.copy(fanSpeedRange = 4..10)
        assertEquals(5, SafetyPolicy(caps, Intensity.LOW).fanSpeed(5))
        assertEquals(6, SafetyPolicy(caps, Intensity.MEDIUM).fanSpeed(5))
        assertEquals(8, SafetyPolicy(caps, Intensity.HIGH).fanSpeed(5))
        assertEquals(4, SafetyPolicy(caps, Intensity.HIGH).fanSpeed(0))
    }

    @Test
    fun `범위 밖 바람 단계는 클램프된다`() {
        assertEquals(6, policy().fanSpeed(99))
        assertEquals(1, policy().fanSpeed(-3))
    }

    @Test
    fun `온도는 시작값 ±2℃ 안에서 움직인다`() {
        assertEquals(24f, policy().temperature(22f, 2f))
        assertEquals(20f, policy().temperature(22f, -2f))
        assertEquals(24f, policy().temperature(22f, 9f))
        assertEquals("강도 중은 ×0.75 후 0.5℃ 단위", 23.5f, policy(Intensity.MEDIUM).temperature(22f, 2f))
    }

    @Test
    fun `온도는 20~26℃를 넘지 않는다`() {
        assertEquals(26f, policy().temperature(25.5f, 2f))
        assertEquals(20f, policy().temperature(20.5f, -2f))
    }

    @Test
    fun `차량 단위에 맞추되 허용 범위 안쪽으로 맞춘다`() {
        // 22.3 + 2 = 24.3 → 0.5 단위로 올리면 24.5가 ±2℃를 넘으므로 24.0
        assertEquals(24f, policy().temperature(22.3f, 2f))
    }

    @Test
    fun `클램프 결과가 요청과 반대 방향이면 바꾸지 않는다`() {
        // 시작 27℃에서 따뜻하게 → 26℃로 내리는 셈이므로 그대로 둔다
        assertNull(policy().temperature(27f, 1f))
        assertEquals("시원하게는 허용", 25f, policy().temperature(27f, -2f))
        // 시작 30℃: 20~26℃와 ±2℃가 겹치지 않음
        assertNull(policy().temperature(30f, -2f))
    }

    @Test
    fun `온도 0 요청은 시작 온도로 되돌린다`() {
        assertEquals(22.3f, policy().temperature(22.3f, 0f))
    }

    @Test
    fun `시트 열선은 1단까지`() {
        assertEquals(1, policy().seatHeat(3))
        assertEquals(1, policy(Intensity.LOW).seatHeat(1))
        assertEquals(0, policy().seatHeat(0))
    }

    @Test
    fun `열선이 없는 차는 바꾸지 않는다`() {
        val noSeat = SafetyPolicy(EMULATOR_CAPABILITIES.copy(seatHeatZones = emptyList()), Intensity.HIGH)
        assertNull(noSeat.seatHeat(1))
    }

    @Test
    fun `밝기는 강도를 곱해 차량 범위로 바꾼다`() {
        assertEquals(60, policy().ambientBrightness(0.6f))
        assertEquals(30, policy(Intensity.LOW).ambientBrightness(0.6f))
        assertEquals(100, policy().ambientBrightness(1.5f))
    }
}
