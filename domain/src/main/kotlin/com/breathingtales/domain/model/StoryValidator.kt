package com.breathingtales.domain.model

/** 동화 파일이 스키마를 어겼을 때. [issues]에 문제를 모두 담는다 */
class InvalidStoryException(
    val storyId: String,
    val issues: List<String>,
) : Exception("동화 '$storyId' 오류 ${issues.size}건:\n" + issues.joinToString("\n") { "- $it" })

/**
 * 로드 시점 스키마 검증 (03). 범위 밖 값과 없는 파일을 한 번에 모아서 보고한다.
 * 범위는 "동화가 요청할 수 있는 값"이고, 차량 허용 범위로 줄이는 일은 SafetyGuard가 따로 한다.
 */
object StoryValidator {

    /** @param fileExists 동화 폴더 기준 상대 경로의 파일이 있는지 */
    fun validate(story: Story, fileExists: (String) -> Boolean): List<String> = buildList {
        if (story.id.isBlank()) add("id가 비어 있음")
        if (story.title.isBlank()) add("title이 비어 있음")
        if (story.scenes.isEmpty()) add("장면이 없음")

        story.scenes.groupingBy { it.id }.eachCount()
            .filterValues { it > 1 }
            .forEach { (id, _) -> add("장면 id 중복: $id") }

        fun checkFile(where: String, path: String) {
            if (!fileExists(path)) add("$where: 파일 없음 '$path'")
        }

        story.cover?.let { checkFile("표지", it) }

        story.scenes.forEach { scene ->
            val s = "장면 ${scene.id}"
            if (scene.lines.isEmpty() && scene.interaction == null) add("$s: 대사도 참여도 없음")
            scene.image?.let { checkFile(s, it) }

            scene.lines.forEachIndexed { i, line ->
                val where = "$s 대사 ${i + 1}"
                if (line.text.isBlank()) add("$where: text가 비어 있음")
                line.audio?.let { checkFile(where, it) }
                if (line.pauseAfterSec !in 0f..Line.MAX_PAUSE_SEC) {
                    add("$where: pauseAfterSec 범위 밖 (${line.pauseAfterSec}, 허용 0~${Line.MAX_PAUSE_SEC})")
                }
            }

            scene.effects.forEachIndexed { i, timed ->
                val where = "$s 효과 ${i + 1}"
                if (timed.atSec < 0) add("$where: at은 0 이상이어야 함 (${timed.atSec})")
                addAll(effectIssues(where, timed.effect, ::checkFile))
            }

            scene.interaction?.let { interaction ->
                val where = "$s 참여"
                if (interaction.type.usesKeywords && interaction.keywords.none { it.isNotBlank() }) {
                    add("$where: ${interaction.type}에는 keywords가 필요함")
                }
                if (interaction.fallbackButton.isBlank()) add("$where: fallbackButton이 비어 있음")
                if (interaction.type.listens && interaction.timeoutSec <= 0) add("$where: timeoutSec은 1 이상이어야 함")
                if (interaction.repeat < 1) add("$where: repeat은 1 이상이어야 함")
                val breath = interaction.breath
                if (interaction.type == InteractionType.BREATHE) {
                    if (breath == null) {
                        add("$where: breathe에는 breath(inhaleSec, exhaleSec)가 필요함")
                    } else {
                        val secs = Breath.MIN_SEC..Breath.MAX_SEC
                        if (breath.inhaleSec !in secs) add("$where: inhaleSec 범위 밖 (${breath.inhaleSec}, 허용 ${Breath.MIN_SEC}~${Breath.MAX_SEC})")
                        if (breath.exhaleSec !in secs) add("$where: exhaleSec 범위 밖 (${breath.exhaleSec}, 허용 ${Breath.MIN_SEC}~${Breath.MAX_SEC})")
                        if (interaction.repeat > Breath.MAX_CYCLES) add("$where: 숨 맞추기 repeat은 ${Breath.MAX_CYCLES} 이하 (${interaction.repeat})")
                    }
                } else if (breath != null) {
                    add("$where: breath는 breathe 참여에만 씀")
                }
                breath?.onInhale?.forEachIndexed { i, effect -> addAll(effectIssues("$where onInhale ${i + 1}", effect, ::checkFile)) }
                breath?.onExhale?.forEachIndexed { i, effect -> addAll(effectIssues("$where onExhale ${i + 1}", effect, ::checkFile)) }
                interaction.onSuccess.forEachIndexed { i, effect ->
                    addAll(effectIssues("$where onSuccess ${i + 1}", effect, ::checkFile))
                }
            }
        }
    }

    fun requireValid(story: Story, fileExists: (String) -> Boolean) {
        val issues = validate(story, fileExists)
        if (issues.isNotEmpty()) throw InvalidStoryException(story.id, issues)
    }

    private fun effectIssues(
        where: String,
        effect: Effect,
        checkFile: (String, String) -> Unit,
    ): List<String> = buildList {
        fun range(name: String, value: Number, min: Number, max: Number) {
            if (value.toDouble() !in min.toDouble()..max.toDouble()) {
                add("$where: $name 범위 밖 ($value, 허용 $min~$max)")
            }
        }

        fun nonNegative(name: String, value: Float) {
            if (value < 0) add("$where: $name 은 0 이상이어야 함 ($value)")
        }

        when (effect) {
            is Effect.Ambient -> {
                range("color", effect.colorRgb, 0, 0xFFFFFF)
                range("brightness", effect.brightness, 0, 1)
                nonNegative("fadeSec", effect.fadeSec)
            }
            is Effect.AmbientBrightnessStep -> {
                range("value", effect.delta, -1, 1)
                range("max", effect.max, 0, 1)
                nonNegative("fadeSec", effect.fadeSec)
            }
            is Effect.FanLevel -> range("value", effect.level, 0, Effect.FanLevel.MAX)
            is Effect.FanLevelStep -> {
                range("value", effect.delta, -Effect.FanLevel.MAX, Effect.FanLevel.MAX)
                range("max", effect.max, 0, Effect.FanLevel.MAX)
            }
            is Effect.FanDirection, is Effect.CabinLight, is Effect.ReadingLight -> Unit
            is Effect.TemperatureDelta ->
                range("value", effect.deltaC, -Effect.TemperatureDelta.MAX_ABS, Effect.TemperatureDelta.MAX_ABS)
            is Effect.SeatHeat -> range("value", effect.level, 0, Effect.SeatHeat.MAX)
            is Effect.SeatLift -> {
                range("durationSec", effect.durationSec, Effect.SeatLift.MIN_SEC, Effect.SeatLift.MAX_SEC)
                range("repeat", effect.repeat, 1, Effect.SeatLift.MAX_REPEAT)
            }
            is Effect.Sound -> {
                range("volume", effect.volume, 0, 1)
                checkFile(where, effect.audio)
            }
            is Effect.Restore -> nonNegative("fadeSec", effect.fadeSec)
        }
    }
}
