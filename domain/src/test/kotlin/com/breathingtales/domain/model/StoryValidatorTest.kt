package com.breathingtales.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryValidatorTest {

    private val windScene = Scene(
        id = "wind-turn",
        lines = listOf(Line("wind", "내가 먼저 할게!", audio = "wind_01.mp3")),
        effects = listOf(
            TimedEffect(0.0, Effect.Ambient(0x4A90E2, 0.6f)),
            TimedEffect(0.0, Effect.TemperatureDelta(-1.5f)),
            TimedEffect(2.0, Effect.FanLevel(1)),
        ),
        interaction = Interaction(
            type = InteractionType.SHOUT_OR_BLOW,
            keywords = listOf("바람아"),
            onSuccess = listOf(Effect.FanLevelStep(1, max = 3)),
            fallbackButton = "바람 불기",
            timeoutSec = 10,
        ),
    )

    private val story = Story(
        id = "sun-and-wind",
        title = "해님과 바람의 내기",
        ageRange = "4-7",
        durationSec = 330,
        scenes = listOf(windScene),
        familyTalkCards = emptyList(),
    )

    @Test
    fun `정상 동화는 문제가 없다`() {
        assertEquals(emptyList<String>(), StoryValidator.validate(story) { true })
    }

    @Test
    fun `온도는 상대값 ±2℃까지만 요청할 수 있다`() {
        val issues = validateWithEffect(Effect.TemperatureDelta(2.5f))
        assertTrue(issues.single(), issues.single().contains("범위 밖"))
        assertEquals(emptyList<String>(), validateWithEffect(Effect.TemperatureDelta(-2f)))
    }

    @Test
    fun `바람 세기와 밝기 범위를 검사한다`() {
        assertEquals(1, validateWithEffect(Effect.FanLevel(Effect.FanLevel.MAX + 1)).size)
        assertEquals(1, validateWithEffect(Effect.Ambient(0xFFFFFF, 1.5f)).size)
        assertEquals(1, validateWithEffect(Effect.SeatHeat(4)).size)
    }

    @Test
    fun `없는 파일을 모두 보고한다`() {
        val withSound = story.copy(
            scenes = listOf(windScene.copy(effects = windScene.effects + TimedEffect(0.0, Effect.Sound("sfx.mp3")))),
        )
        val issues = StoryValidator.validate(withSound) { false }
        assertEquals(issues.toString(), 2, issues.size)
        assertTrue(issues.any { "wind_01.mp3" in it })
        assertTrue(issues.any { "sfx.mp3" in it })
    }

    @Test
    fun `외치기 참여에는 키워드가 필요하고 숨 불기는 필요 없다`() {
        val shout = windScene.interaction!!.copy(type = InteractionType.SHOUT, keywords = emptyList())
        val blow = shout.copy(type = InteractionType.BLOW)
        assertEquals(1, validateWithInteraction(shout).size)
        assertEquals(emptyList<String>(), validateWithInteraction(blow))
    }

    @Test
    fun `참여 성공 효과도 범위를 검사한다`() {
        val interaction = windScene.interaction!!.copy(onSuccess = listOf(Effect.FanLevelStep(1, max = 9)))
        val issues = validateWithInteraction(interaction)
        assertTrue(issues.single(), issues.single().contains("onSuccess"))
    }

    @Test
    fun `장면 id 중복은 오류`() {
        val issues = StoryValidator.validate(story.copy(scenes = listOf(windScene, windScene))) { true }
        assertTrue(issues.single(), issues.single().contains("중복"))
    }

    @Test(expected = InvalidStoryException::class)
    fun `requireValid는 문제가 있으면 예외를 던진다`() {
        StoryValidator.requireValid(story.copy(scenes = emptyList())) { true }
    }

    private fun validateWithEffect(effect: Effect) =
        StoryValidator.validate(story.copy(scenes = listOf(windScene.copy(effects = listOf(TimedEffect(0.0, effect)))))) { true }

    private fun validateWithInteraction(interaction: Interaction) =
        StoryValidator.validate(story.copy(scenes = listOf(windScene.copy(interaction = interaction)))) { true }

    @Test
    fun `숨 맞추기는 박자가 필요하고 범위를 넘으면 오류, 듣지 않으므로 timeoutSec은 보지 않는다`() {
        val breathe = windScene.interaction!!.copy(type = InteractionType.BREATHE, keywords = emptyList(), timeoutSec = 0)
        fun issues(i: Interaction) = StoryValidator.validate(story.copy(scenes = listOf(windScene.copy(interaction = i)))) { true }

        assertEquals(1, issues(breathe).size)
        assertEquals(emptyList<String>(), issues(breathe.copy(breath = Breath(4f, 4f, onExhale = listOf(Effect.FanLevel(1))))))
        val bad = issues(breathe.copy(repeat = 6, breath = Breath(0.5f, 11f, onInhale = listOf(Effect.FanLevel(9)))))
        assertEquals(bad.toString(), 4, bad.size)
        assertEquals(1, issues(windScene.interaction!!.copy(breath = Breath(4f, 4f))).size)
    }
}
