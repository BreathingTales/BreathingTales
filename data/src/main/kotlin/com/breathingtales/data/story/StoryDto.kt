package com.breathingtales.data.story

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** story.json 형식 그대로의 DTO. 스키마 설명은 docs/dev/03_도메인_모델.md */
@Serializable
internal data class StoryDto(
    val id: String,
    val title: String,
    val ageRange: String,
    val durationSec: Int,
    val scenes: List<SceneDto>,
    val familyTalkCards: List<String> = emptyList(),
    val cover: String? = null,
)

@Serializable
internal data class SceneDto(
    val id: String,
    val lines: List<LineDto> = emptyList(),
    val effects: List<EffectDto> = emptyList(),
    val interaction: InteractionDto? = null,
    val image: String? = null,
)

@Serializable
internal data class LineDto(
    val speaker: String,
    val text: String,
    val audio: String? = null,
    /** true면 Pleos TTS로 읽는다. audio와 둘 중 하나만 쓴다 */
    val tts: Boolean = false,
    val pauseAfterSec: Float = 0f,
)

@Serializable
internal data class InteractionDto(
    val type: InteractionTypeDto,
    val keywords: List<String> = emptyList(),
    val onSuccess: List<EffectDto> = emptyList(),
    val fallbackButton: String,
    /** 숨 맞추기(breathe)는 쓰지 않는다 */
    val timeoutSec: Int = 0,
    val repeat: Int = 1,
    val breath: BreathDto? = null,
)

@Serializable
internal data class BreathDto(
    val inhaleSec: Float,
    val exhaleSec: Float,
    val onInhale: List<EffectDto> = emptyList(),
    val onExhale: List<EffectDto> = emptyList(),
)

@Serializable
internal enum class InteractionTypeDto {
    @SerialName("shout") SHOUT,
    @SerialName("blow") BLOW,
    @SerialName("shoutOrBlow") SHOUT_OR_BLOW,
    @SerialName("breathe") BREATHE,
}

@Serializable
internal enum class OnOffDto {
    @SerialName("on") ON,
    @SerialName("off") OFF,
}

@Serializable
internal enum class AirflowDto {
    @SerialName("face") FACE,
    @SerialName("floor") FLOOR,
}

/**
 * `type`으로 구분하는 효과. 장면 effects에서는 [at](장면 시작 기준 초)을 쓰고, onSuccess에서는 쓰지 않는다.
 * 모르는 type은 파싱 오류가 된다.
 */
@Serializable
internal sealed class EffectDto {
    abstract val at: Double

    @Serializable
    @SerialName("ambient")
    data class Ambient(
        val color: String,
        val brightness: Float,
        val fadeSec: Float = 0f,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("ambientBrightnessStep")
    data class AmbientBrightnessStep(
        val value: Float,
        val max: Float = 1f,
        val fadeSec: Float = 0f,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("fanLevel")
    data class FanLevel(
        val value: Int,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("fanLevelStep")
    data class FanLevelStep(
        val value: Int,
        val max: Int? = null,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("fanDirection")
    data class FanDirection(
        val value: AirflowDto,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("temperatureDelta")
    data class TemperatureDelta(
        val value: Float,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("seatHeat")
    data class SeatHeat(
        val value: Int,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("seatLift")
    data class SeatLift(
        val durationSec: Float,
        val repeat: Int = 1,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("cabinLight")
    data class CabinLight(
        val value: OnOffDto,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("readingLight")
    data class ReadingLight(
        val value: OnOffDto,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("sound")
    data class Sound(
        val audio: String,
        val loop: Boolean = false,
        val volume: Float = 1f,
        override val at: Double = 0.0,
    ) : EffectDto()

    @Serializable
    @SerialName("restore")
    data class Restore(
        val fadeSec: Float = 0f,
        override val at: Double = 0.0,
    ) : EffectDto()
}
