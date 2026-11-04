package com.breathingtales.data.story

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.Breath
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.model.InvalidStoryException
import com.breathingtales.domain.model.TimedEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoryParserTest {

    private val sample: String =
        javaClass.getResource("/stories/sun-and-wind/story.json")!!.readText()

    private val allFilesExist: (String) -> Boolean = { true }

    @Test
    fun `기획서 8-3 예시가 도메인 모델로 변환된다`() {
        val story = StoryParser.parse("sun-and-wind", sample, allFilesExist)

        assertEquals("해님과 바람의 내기", story.title)
        assertEquals(listOf("opening", "wind-turn"), story.scenes.map { it.id })
        assertEquals(2, story.familyTalkCards.size)

        val wind = story.scenes[1]
        assertEquals("wind_01.mp3", wind.lines[0].audio)
        assertEquals(
            listOf(
                TimedEffect(0.0, Effect.Ambient(colorRgb = 0x4A90E2, brightness = 0.6f)),
                TimedEffect(0.0, Effect.FanDirection(Airflow.FACE)),
                TimedEffect(0.0, Effect.TemperatureDelta(-1.5f)),
                TimedEffect(2.0, Effect.FanLevel(1)),
                TimedEffect(2.0, Effect.Sound("sfx_wind.mp3", loop = true)),
            ),
            wind.effects,
        )

        val interaction = wind.interaction!!
        assertEquals(InteractionType.SHOUT_OR_BLOW, interaction.type)
        assertEquals(listOf("바람아 불어라", "바람아"), interaction.keywords)
        assertEquals(listOf(Effect.FanLevelStep(delta = 1, max = 3)), interaction.onSuccess)
        assertEquals(2, interaction.repeat)
    }

    @Test
    fun `tts 대사는 음원 없이 문장을 그대로 둔다`() {
        val line = StoryParser.parse("sun-and-wind", sample, allFilesExist).scenes[0].lines[0]
        assertTrue(line.isTts)
        assertTrue(line.text.contains("{childName}"))
    }

    @Test
    fun `실내등과 독서등 효과는 on·off 값으로 읽는다`() {
        val text = sample.replace(
            "{ \"at\": 0.0, \"type\": \"fanDirection\", \"value\": \"face\" }",
            "{ \"type\": \"cabinLight\", \"value\": \"off\" }, { \"at\": 1.0, \"type\": \"spotlight\", \"value\": \"on\" }",
        )
        assertTrue("샘플 문자열이 바뀌어 치환이 안 됨", text != sample)
        val effects = StoryParser.parse("sun-and-wind", text, allFilesExist).scenes[1].effects
        assertTrue(TimedEffect(0.0, Effect.CabinLight(false)) in effects)
        assertTrue(TimedEffect(1.0, Effect.Spotlight(true)) in effects)
    }

    @Test
    fun `숨 맞추기 참여는 박자와 들이쉬기·내쉬기 효과를 읽는다`() {
        val old = "\"type\": \"shoutOrBlow\","
        val text = sample.replace(
            old,
            "\"type\": \"breathe\", \"breath\": { \"inhaleSec\": 4, \"exhaleSec\": 3, " +
                "\"onInhale\": [{ \"type\": \"ambientBrightnessStep\", \"value\": 0.2, \"fadeSec\": 3.5 }], " +
                "\"onExhale\": [{ \"type\": \"fanLevel\", \"value\": 1 }] },",
        )
        assertTrue("샘플 문자열이 바뀌어 치환이 안 됨", text != sample)
        val interaction = StoryParser.parse("sun-and-wind", text, allFilesExist).scenes[1].interaction!!
        assertEquals(InteractionType.BREATHE, interaction.type)
        assertEquals(Breath(4f, 3f, listOf(Effect.AmbientBrightnessStep(0.2f, fadeSec = 3.5f)), listOf(Effect.FanLevel(1))), interaction.breath)
    }

    @Test
    fun `앱에 들어가는 실제 story json이 스키마·범위·파일 검사를 통과한다`() {
        // 테스트 작업 폴더는 data 모듈. 대사 음원·효과음 파일이 모두 있는지도 본다
        val dir = java.io.File("../app/src/main/assets/stories/sun-and-wind")
        val story = StoryParser.parse("sun-and-wind", dir.resolve("story.json").readText()) { dir.resolve(it).isFile }
        assertEquals(12, story.scenes.size)
        assertTrue(story.scenes.any { it.interaction?.type == InteractionType.BREATHE })
    }

    @Test
    fun `모르는 효과 type은 오류`() {
        val issues = issuesOf(sample.replace("\"fanDirection\"", "\"window\""))
        assertTrue(issues.single(), issues.single().contains("window"))
    }

    @Test
    fun `오타 난 키는 무시하지 않고 오류`() {
        val issues = issuesOf(sample.replace("\"brightness\": 0.6", "\"brightnes\": 0.6"))
        assertTrue(issues.single(), issues.single().contains("brightnes"))
    }

    @Test
    fun `범위 밖 값과 잘못된 색, 없는 음원을 한 번에 보고한다`() {
        val broken = sample
            .replace("\"value\": -1.5", "\"value\": -5")
            .replace("#4A90E2", "blue")
        val issues = issuesOf(broken) { it != "nar_05.mp3" }

        assertEquals(issues.toString(), 3, issues.size)
        assertTrue(issues.any { "temperatureDelta" in it || "범위 밖 (-5" in it })
        assertTrue(issues.any { "color" in it && "blue" in it })
        assertTrue(issues.any { "nar_05.mp3" in it })
    }

    @Test
    fun `audio와 tts를 둘 다 쓰거나 둘 다 빼면 오류`() {
        val both = issuesOf(sample.replace("\"tts\": true,", "\"tts\": true, \"audio\": \"a.mp3\","))
        val neither = issuesOf(sample.replace("\"tts\": true,", ""))
        assertTrue(both.single().contains("audio와 tts"))
        assertTrue(neither.single().contains("audio와 tts"))
    }

    @Test
    fun `id가 폴더 이름과 다르면 오류`() {
        val issues = issuesOf(sample, storyId = "other")
        assertFalse(issues.isEmpty())
        assertTrue(issues.first().contains("폴더 이름"))
    }

    private fun issuesOf(
        text: String,
        storyId: String = "sun-and-wind",
        fileExists: (String) -> Boolean = allFilesExist,
    ): List<String> {
        try {
            StoryParser.parse(storyId, text, fileExists)
        } catch (e: InvalidStoryException) {
            return e.issues
        }
        fail("InvalidStoryException이 나야 함")
        error("unreachable")
    }
}
