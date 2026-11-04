package com.breathingtales.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRulesTest {

    private val wind = KeywordMatcher(listOf("바람아 불어라", "바람아"))
    private val sun = KeywordMatcher(listOf("해님 반짝"))

    @Test
    fun `띄어쓰기와 문장부호는 무시하고 문장 안 어디든 키워드가 있으면 성공`() {
        assertEquals("바람아불어라", wind.match("바람아, 불어라!"))
        assertEquals("바람아", wind.match("음 바람아"))
        assertEquals("해님반짝", sun.match("해님반짝"))
    }

    @Test
    fun `받침이 넘어가거나 자음이 하나 틀린 발음도 성공`() {
        assertEquals("바람아", wind.match("바라마"))
        assertEquals("해님반짝", sun.match("해님 반작"))
        assertEquals("바람아", wind.match("바랑아"))
        // 에뮬레이터 STT가 실제로 들은 결과 (08 문서)
        assertEquals("바람아불어라", wind.match("바람만 불어라"))
        assertEquals("해님반짝", sun.match("해님 단짠단짠"))
        assertEquals("해님반짝", sun.match("해님 반찬이"))
    }

    @Test
    fun `관계없는 말, 너무 많이 다른 말, 빈 결과는 실패`() {
        assertNull(wind.match("배고파"))
        assertNull(wind.match("사랑해"))
        assertNull(sun.match("해님 짝"))
        assertNull(wind.match(""))
        assertNull(wind.match("  ..."))
        assertNull(sun.match("해"))
    }

    /** 64ms마다 표본 (16kHz, 1024샘플 버퍼) */
    private fun BlowRule.feed(fromMs: Long, toMs: Long, db: Float): List<Long> =
        (fromMs until toMs step 64).filter { onLevel(it, db) }

    @Test
    fun `기준 이상이 0_5초 이어지면 한 번 성공하고 길게 불어도 한 번만 센다`() {
        val rule = BlowRule()
        assertEquals(emptyList<Long>(), rule.feed(0, 1_000, -77f))
        val hits = rule.feed(1_000, 4_000, -47f)
        assertEquals(1, hits.size)
        assertEquals(true, hits.single() in 1_500..1_700)
    }

    @Test
    fun `잠깐 쉬었다 다시 불면 또 센다`() {
        val rule = BlowRule()
        rule.feed(0, 1_000, -47f)
        rule.feed(1_000, 1_500, -77f)
        assertEquals(1, rule.feed(1_500, 2_500, -47f).size)
    }

    @Test
    fun `짧은 숨이나 한 프레임 끊김으로는 세지 않는다`() {
        val rule = BlowRule()
        assertEquals(0, rule.feed(0, 300, -47f).size)
        rule.feed(300, 1_000, -77f)
        // 불다가 한 프레임 조용해져도 평균 창 덕분에 한 번의 불기로 본다
        assertEquals(1, rule.feed(1_000, 1_700, -47f).size)
        rule.onLevel(1_700, -90f)
        assertEquals(0, rule.feed(1_764, 2_500, -47f).size)
    }

    /** [fromMs, toMs) 동안 64ms마다 같은 음량 */
    private fun SuccessFilter.sound(fromMs: Long, toMs: Long, db: Float) {
        for (t in fromMs until toMs step 64) onLevel(t, db)
    }

    @Test
    fun `STT 중간 결과가 여러 번 맞아도 한 발화는 한 번만 센다`() {
        val filter = SuccessFilter()
        filter.sound(0, 1_500, -30f)
        // 에뮬레이터 실제 순서: "바람" → "바람아 불어" → "바람아 불어라" → 최종
        assertEquals(true, filter.onSpeech(1_000, matched = true, final = false))
        assertEquals(false, filter.onSpeech(1_300, matched = true, final = false))
        assertEquals(false, filter.onSpeech(1_600, matched = true, final = false))
        assertEquals(false, filter.onSpeech(2_000, matched = true, final = true))
        // 쉬었다가 다시 외침
        filter.sound(1_500, 3_000, -77f)
        filter.sound(3_000, 4_500, -30f)
        assertEquals(true, filter.onSpeech(4_000, matched = true, final = false))
    }

    @Test
    fun `외치는 소리가 불기로 먼저 잡히면 늦게 온 STT 결과는 세지 않는다`() {
        val filter = SuccessFilter()
        filter.sound(0, 1_500, -30f)
        assertEquals(true, filter.onBlow()) // 외치기 0.5초째 음량으로 불기 성공
        filter.sound(1_500, 4_000, -77f)
        // 엔진 오류 복구로 2초 늦게 온 외치기 결과도 같은 덩어리
        assertEquals(false, filter.onSpeech(3_500, matched = true, final = false))
        // 다음 덩어리의 불기는 센다
        filter.sound(4_000, 5_500, -45f)
        assertEquals(true, filter.onBlow())
    }

    @Test
    fun `외친 뒤 따로 분 것은 두 번 센다`() {
        val filter = SuccessFilter()
        filter.sound(0, 1_500, -30f)
        assertEquals(true, filter.onSpeech(1_200, matched = true, final = false))
        filter.sound(1_500, 4_500, -77f)
        filter.sound(4_500, 6_000, -45f)
        assertEquals(true, filter.onBlow())
    }

    @Test
    fun `한 덩어리 안에서 숨 불기는 한 번만 센다`() {
        val filter = SuccessFilter()
        filter.sound(0, 3_000, -45f)
        assertEquals(true, filter.onBlow())
        assertEquals(false, filter.onBlow())
    }

    @Test
    fun `기준보다 작은 말소리로 온 STT 결과도 센다`() {
        val filter = SuccessFilter()
        filter.sound(0, 5_000, -70f)
        assertEquals(true, filter.onSpeech(5_000, matched = true, final = true))
    }

    @Test
    fun `민감도를 낮추면 약한 소리는 세지 않는다`() {
        val rule = BlowRule(thresholdDb = -45f)
        assertEquals(0, rule.feed(0, 2_000, -50f).size)
        assertEquals(1, rule.feed(2_000, 3_000, -40f).size)
    }
}
