package com.breathingtales.data.story

import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.Breath
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Interaction
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.model.InvalidStoryException
import com.breathingtales.domain.model.Line
import com.breathingtales.domain.model.Scene
import com.breathingtales.domain.model.Story
import com.breathingtales.domain.model.StoryValidator
import com.breathingtales.domain.model.TimedEffect
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * story.json 문자열 → 도메인 [Story]. Android에 의존하지 않아 JVM 단위 테스트로 검증한다.
 * 형식 오류(모르는 효과 type, 모르는 키, 잘못된 색)와 [StoryValidator]의 범위·파일 검사를 함께 보고한다.
 */
internal object StoryParser {

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = false // 오타 난 키를 조용히 무시하지 않는다
    }

    /**
     * @param storyId 오류 메시지용 (폴더 이름)
     * @param fileExists 동화 폴더 기준 상대 경로의 파일이 있는지
     */
    fun parse(storyId: String, text: String, fileExists: (String) -> Boolean): Story {
        val dto = try {
            json.decodeFromString<StoryDto>(text)
        } catch (e: SerializationException) {
            throw InvalidStoryException(storyId, listOf("JSON 형식 오류: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            throw InvalidStoryException(storyId, listOf("JSON 형식 오류: ${e.message}"))
        }

        val issues = mutableListOf<String>()
        if (dto.id != storyId) issues += "id '${dto.id}'가 폴더 이름 '$storyId'와 다름"
        val story = dto.toDomain(issues)
        issues += StoryValidator.validate(story, fileExists)
        if (issues.isNotEmpty()) throw InvalidStoryException(storyId, issues)
        return story
    }

    private fun StoryDto.toDomain(issues: MutableList<String>) = Story(
        id = id,
        title = title,
        ageRange = ageRange,
        durationSec = durationSec,
        scenes = scenes.map { it.toDomain(issues) },
        familyTalkCards = familyTalkCards,
        cover = cover,
    )

    private fun SceneDto.toDomain(issues: MutableList<String>): Scene {
        val s = "장면 $id"
        return Scene(
            id = id,
            lines = lines.mapIndexed { i, line ->
                if ((line.audio != null) == line.tts) {
                    issues += "$s 대사 ${i + 1}: audio와 tts 중 하나만 지정해야 함"
                }
                Line(speaker = line.speaker, text = line.text, audio = line.audio, pauseAfterSec = line.pauseAfterSec)
            },
            effects = effects.mapIndexed { i, effect ->
                TimedEffect(effect.at, effect.toDomain("$s 효과 ${i + 1}", issues))
            },
            interaction = interaction?.let { dto ->
                Interaction(
                    type = when (dto.type) {
                        InteractionTypeDto.SHOUT -> InteractionType.SHOUT
                        InteractionTypeDto.BLOW -> InteractionType.BLOW
                        InteractionTypeDto.SHOUT_OR_BLOW -> InteractionType.SHOUT_OR_BLOW
                        InteractionTypeDto.BREATHE -> InteractionType.BREATHE
                    },
                    keywords = dto.keywords,
                    onSuccess = dto.onSuccess.mapIndexed { i, effect ->
                        effect.toDomain("$s 참여 onSuccess ${i + 1}", issues)
                    },
                    fallbackButton = dto.fallbackButton,
                    timeoutSec = dto.timeoutSec,
                    repeat = dto.repeat,
                    breath = dto.breath?.let { b ->
                        Breath(
                            inhaleSec = b.inhaleSec,
                            exhaleSec = b.exhaleSec,
                            onInhale = b.onInhale.mapIndexed { i, e -> e.toDomain("$s 참여 onInhale ${i + 1}", issues) },
                            onExhale = b.onExhale.mapIndexed { i, e -> e.toDomain("$s 참여 onExhale ${i + 1}", issues) },
                        )
                    },
                )
            },
            image = image,
        )
    }

    private fun EffectDto.toDomain(where: String, issues: MutableList<String>): Effect = when (this) {
        is EffectDto.Ambient -> Effect.Ambient(
            colorRgb = parseColor(color) ?: 0.also { issues += "$where: color는 #RRGGBB 형식이어야 함 ('$color')" },
            brightness = brightness,
            fadeSec = fadeSec,
        )
        is EffectDto.AmbientBrightnessStep -> Effect.AmbientBrightnessStep(value, max, fadeSec)
        is EffectDto.FanLevel -> Effect.FanLevel(value)
        is EffectDto.FanLevelStep -> Effect.FanLevelStep(value, max ?: Effect.FanLevel.MAX)
        is EffectDto.FanDirection -> Effect.FanDirection(
            when (value) {
                AirflowDto.FACE -> Airflow.FACE
                AirflowDto.FLOOR -> Airflow.FLOOR
            },
        )
        is EffectDto.TemperatureDelta -> Effect.TemperatureDelta(value)
        is EffectDto.SeatHeat -> Effect.SeatHeat(value)
        is EffectDto.SeatLift -> Effect.SeatLift(durationSec, repeat)
        is EffectDto.CabinLight -> Effect.CabinLight(value == OnOffDto.ON)
        is EffectDto.ReadingLight -> Effect.ReadingLight(value == OnOffDto.ON)
        is EffectDto.Sound -> Effect.Sound(audio, loop, volume)
        is EffectDto.Restore -> Effect.Restore(fadeSec)
    }

    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    private fun parseColor(text: String): Int? =
        if (colorPattern.matches(text)) text.substring(1).toInt(16) else null
}
