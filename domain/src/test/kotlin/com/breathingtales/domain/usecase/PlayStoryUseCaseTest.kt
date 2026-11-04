package com.breathingtales.domain.usecase

import kotlinx.coroutines.launch
import com.breathingtales.domain.model.BreathPhase
import com.breathingtales.domain.model.Breath
import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.Interaction
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.model.Line
import com.breathingtales.domain.model.Scene
import com.breathingtales.domain.model.Story
import com.breathingtales.domain.model.TimedEffect
import com.breathingtales.domain.port.VoiceSignal
import com.breathingtales.domain.safety.DRIVER
import com.breathingtales.domain.safety.FakeVehicleStates
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.safety.NotParkedReason
import com.breathingtales.domain.safety.PARKED
import com.breathingtales.domain.safety.REAR_LEFT
import com.breathingtales.domain.safety.REAR_RIGHT
import com.breathingtales.domain.safety.RecordingPort
import com.breathingtales.domain.safety.SafetyGuard
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
 * 가상 시계로 엔진을 확인한다. 대사 음원은 [FakeAudio] 기본 길이, TTS는 문장마다 1초.
 *
 * 시간표 (강도 HIGH, 에뮬레이터 풍량 1~8):
 * - 0s    sky 시작: 대사 sky_01(2초), 효과 at 0 하늘색 조명 + 새소리, at 2.5 바람 1단(풍량 2), at 10 바람 5단(장면이 먼저 끝나 버림)
 * - 2s    TTS "하늘아, 하늘을 봐" (1초)
 * - 3s    wind 시작: 대사 wind_01(1초), at 0 얼굴 방향
 * - 4s    참여 대기 (외치기·불기 2번, 성공마다 바람 +1, 최대 3단, 10초)
 * - 이어서 end: 대사 없음, at 0 복원
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayStoryUseCaseTest {

    private val story = Story(
        id = "sun-and-wind",
        title = "해님과 바람의 내기",
        ageRange = "4-7",
        durationSec = 60,
        scenes = listOf(
            Scene(
                id = "sky",
                lines = listOf(
                    Line("narrator", "하늘 위에 두 친구가 살았어요", audio = "sky_01.mp3"),
                    Line("narrator", "{childName}, 하늘을 봐"),
                ),
                effects = listOf(
                    TimedEffect(10.0, Effect.FanLevel(5)),
                    TimedEffect(0.0, Effect.Ambient(0x87CEEB, 0.6f)),
                    TimedEffect(2.5, Effect.FanLevel(1)),
                    TimedEffect(0.0, Effect.Sound("birds.mp3", loop = true)),
                ),
            ),
            Scene(
                id = "wind",
                lines = listOf(Line("wind", "내가 먼저 할게! 후우우~!", audio = "wind_01.mp3")),
                effects = listOf(TimedEffect(0.0, Effect.FanDirection(Airflow.FACE))),
                interaction = Interaction(
                    type = InteractionType.SHOUT_OR_BLOW,
                    keywords = listOf("바람아"),
                    onSuccess = listOf(Effect.FanLevelStep(1, max = 3)),
                    fallbackButton = "바람 불기",
                    timeoutSec = 10,
                    repeat = 2,
                ),
            ),
            Scene(id = "end", lines = emptyList(), effects = listOf(TimedEffect(0.0, Effect.Restore()))),
        ),
        familyTalkCards = emptyList(),
    )

    private val settings = PlaySettings(Intensity.HIGH, childName = "하늘아")

    private val before = CabinSnapshot(
        hvacPowerOn = true,
        fanSpeed = 3,
        airflow = Airflow.FLOOR,
        temperaturesC = mapOf(DRIVER to 22f),
        ambientMode = AmbientMode.AUTO,
        ambientColorRgb = 0xFFFFFF,
        ambientBrightness = 40,
        ambientAutoByDriveMode = true,
        seatHeatLevels = mapOf(REAR_LEFT to 0, REAR_RIGHT to 0),
    )

    private val restoreCalls = listOf(
        "fanSpeed=3", "airflow=FLOOR", "temperature[1]=22.0",
        "ambientColor=FFFFFF", "ambientBrightness=40", "ambientMode=AUTO", "ambientAuto=true",
        "seatHeat[16]=0", "seatHeat[64]=0",
    )

    private class Rig(scope: TestScope, snapshot: CabinSnapshot) {
        val log = EventLog { scope.currentTime }
        val states = FakeVehicleStates()
        val port = RecordingPort(snapshot = snapshot)
        val audio = FakeAudio(log).apply { durations["sky_01.mp3"] = 2_000 }
        val speech = FakeSpeech(log)
        val voice = FakeVoice(log)
        val engine = PlayStoryUseCase(
            SafetyGuard(port, states),
            ObserveParkedStateUseCase(states),
            audio, speech, voice,
            scope.testScheduler.timeSource,
        )

        fun events(prefix: String) = log.events.filter { it.substringAfter(' ').startsWith(prefix) }
    }

    private fun TestScope.rig() = Rig(this, before)

    @Test
    fun `효과는 장면 시작 기준 시각에 적힌 순서대로 나가고 장면이 끝나면 남은 효과는 버린다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }

        runCurrent()
        assertEquals(listOf("ambientAuto=false", "ambientMode=ON", "ambientColor=87CEEB", "ambientBrightness=60"), rig.port.calls)
        assertEquals(listOf("0 sound birds.mp3 loop=true"), rig.events("sound"))

        advanceTimeBy(2_499); runCurrent()
        assertFalse(rig.port.calls.any { it.startsWith("fanSpeed") })
        advanceTimeBy(1); runCurrent()
        assertEquals("fanSpeed=2", rig.port.calls.last())

        // TTS는 아이 이름을 넣어 읽는다
        assertEquals(listOf("2000 tts 하늘아, 하늘을 봐"), rig.events("tts "))

        advanceTimeBy(500); runCurrent()
        assertEquals(PlaybackState.Playing(1, "wind", 0, story.scenes[1].lines[0]), rig.engine.state.value)
        assertEquals("airflow=FACE", rig.port.calls.last())

        play.cancel()
        // sky의 at 10 효과(바람 5단 = 풍량 6)는 장면이 3초에 끝나 나가지 않는다
        assertFalse(rig.port.calls.contains("fanSpeed=6"))
    }

    @Test
    fun `참여에 성공할 때마다 바람이 한 단계 세지고 repeat번이면 다음 장면으로`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(4_001); runCurrent()
        assertTrue(rig.engine.state.value is PlaybackState.WaitingInteraction)
        assertEquals(listOf("4000 mic-on"), rig.events("mic"))

        rig.voice.signals.emit(VoiceSignal.Shout("바람아 불어라")); runCurrent()
        assertEquals("fanSpeed=3", rig.port.calls.last())
        assertEquals(1, (rig.engine.state.value as PlaybackState.WaitingInteraction).successes)

        advanceTimeBy(1_000)
        rig.voice.signals.emit(VoiceSignal.Blow)
        val end = play.await()

        assertEquals(PlaybackState.Finished(emptyList()), end)
        assertEquals(5_001L, currentTime)
        // 두 번째 성공 → 풍량 4, 그다음 end 장면의 at 0 복원 (대사 없는 장면도 at 0 효과는 나간다)
        val afterSuccess = rig.port.calls.dropWhile { it != "fanSpeed=4" }
        assertEquals(listOf("fanSpeed=4") + restoreCalls, afterSuccess)
        assertEquals("audio.stop", rig.events("audio").last().substringAfter(' '))
        assertTrue(rig.events("mic").last().endsWith("mic-off"))
    }

    @Test
    fun `아이가 반응하지 않아도 timeoutSec이 지나면 이야기가 이어진다`() = runTest {
        val rig = rig()
        val end = rig.engine.play(story, settings)
        assertEquals(PlaybackState.Finished(emptyList()), end)
        assertEquals(4_000L + 10_000L, currentTime)
        // 성공 효과(풍량 3·4)는 없고 바로 복원 (복원의 fanSpeed=3은 시작 전 값)
        assertEquals(restoreCalls, rig.port.calls.takeLast(restoreCalls.size))
        assertEquals(listOf("fanSpeed=2"), rig.port.calls.dropLast(restoreCalls.size).filter { it.startsWith("fanSpeed") })
    }

    @Test
    fun `대체 버튼도 성공 한 번으로 센다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(4_001); runCurrent()
        rig.engine.pressFallback(); runCurrent()
        rig.engine.pressFallback()
        assertEquals(PlaybackState.Finished(emptyList()), play.await())
        assertTrue(rig.port.calls.containsAll(listOf("fanSpeed=3", "fanSpeed=4")))
    }

    @Test
    fun `듣기가 실패해도 이야기는 멈추지 않고 대체 버튼으로 참여한다`() = runTest {
        val rig = rig()
        rig.voice.failing = true
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(4_001); runCurrent()
        assertTrue(rig.engine.state.value is PlaybackState.WaitingInteraction)
        rig.engine.pressFallback(); runCurrent()
        rig.engine.pressFallback()
        assertEquals(PlaybackState.Finished(emptyList()), play.await())
        assertTrue(rig.port.calls.containsAll(listOf("fanSpeed=3", "fanSpeed=4")))
    }

    @Test
    fun `대사 중 차가 움직이면 즉시 중단하고 복원한다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()

        rig.states.state.value = PARKED.copy(gear = Gear.DRIVE, speedMps = 3f)
        val end = play.await()

        assertEquals(
            PlaybackState.Aborted(AbortReason.NotParked(setOf(NotParkedReason.NOT_IN_PARK, NotParkedReason.MOVING)), emptyList()),
            end,
        )
        assertEquals(1_000L, currentTime)
        assertEquals(restoreCalls, rig.port.calls.takeLast(restoreCalls.size))
        assertTrue(rig.events("audio.stop").isNotEmpty())
        assertTrue(rig.events("line-end sky_01").isEmpty())
    }

    @Test
    fun `참여를 기다리다 주차 브레이크가 풀리면 중단하고 복원한다`() = runTest {
        val rig = rig()
        rig.states.state.value = PARKED.copy(parkingBrakeOn = true)
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(5_000); runCurrent()

        rig.states.state.value = PARKED.copy(parkingBrakeOn = false)
        val end = play.await() as PlaybackState.Aborted
        assertEquals(AbortReason.NotParked(setOf(NotParkedReason.PARKING_BRAKE_RELEASED)), end.reason)
        assertEquals(restoreCalls, rig.port.calls.takeLast(restoreCalls.size))
        assertTrue(rig.events("mic").last().endsWith("mic-off"))
    }

    @Test
    fun `정차가 아니면 시작하지 않고 아무것도 바꾸지 않는다`() = runTest {
        val rig = rig()
        rig.states.state.value = PARKED.copy(doorsClosed = false)
        val end = rig.engine.play(story, settings)
        assertEquals(PlaybackState.Aborted(AbortReason.NotParked(setOf(NotParkedReason.DOOR_OPEN)), emptyList()), end)
        assertEquals(emptyList<String>(), rig.port.calls)
        assertTrue(rig.events("audio.start").isEmpty())
    }

    @Test
    fun `통화 중이라 오디오 포커스를 못 받으면 시작하지 않고 복원한다`() = runTest {
        val rig = Rig(this, before.copy(hvacPowerOn = false))
        rig.audio.grantFocus = false
        val end = rig.engine.play(story, settings)
        assertEquals(PlaybackState.Aborted(AbortReason.AudioFocusUnavailable, emptyList()), end)
        // begin에서 켠 공조 전원을 다시 끈다
        assertEquals("hvacPower=false", rig.port.calls.last())
    }

    @Test
    fun `포커스를 잃으면 멈추고 효과 시계도 멈춘다, 돌아와도 부모가 누를 때까지 기다린다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()

        rig.audio.loseFocus(); runCurrent()
        val paused = rig.engine.state.value as PlaybackState.Paused
        assertEquals(PauseReason.AUDIO_FOCUS, paused.reason)
        assertEquals("sky", paused.during.sceneId)

        // 내비 안내 5초. 그동안 at 2.5 효과는 나가지 않는다
        advanceTimeBy(5_000); runCurrent()
        rig.audio.regainFocus(); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertTrue(rig.engine.state.value is PlaybackState.Paused)
        assertFalse(rig.port.calls.any { it.startsWith("fanSpeed") })

        // 멈춘 9초를 빼고 2.5초 = 재개(9초) + 1.5초
        rig.engine.resume(); runCurrent()
        assertTrue(rig.engine.state.value is PlaybackState.Playing)
        advanceTimeBy(1_499); runCurrent()
        assertFalse(rig.port.calls.any { it.startsWith("fanSpeed") })
        advanceTimeBy(1); runCurrent()
        assertEquals("fanSpeed=2", rig.port.calls.last())
        play.cancel()
    }

    @Test
    fun `TTS 중에 멈추면 끊고, 재개하면 그 문장을 처음부터 다시 읽는다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(2_500); runCurrent()

        rig.engine.pause(); runCurrent()
        assertEquals(PauseReason.PARENT, (rig.engine.state.value as PlaybackState.Paused).reason)
        advanceTimeBy(10_000); runCurrent()
        rig.engine.resume(); runCurrent()
        advanceTimeBy(1_000); runCurrent()

        val tts = rig.log.events.filter { it.contains(" tts") }
        assertEquals(
            listOf("2000 tts 하늘아, 하늘을 봐", "2500 tts-cut", "12500 tts 하늘아, 하늘을 봐", "13500 tts-end"),
            tts,
        )
        play.cancel()
    }

    @Test
    fun `참여 제한 시간은 멈춘 동안 줄지 않고 마이크도 끈다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(6_000); runCurrent() // 참여 대기 2초째

        rig.engine.pause(); runCurrent()
        assertTrue(rig.events("mic").last().endsWith("mic-off"))
        advanceTimeBy(20_000); runCurrent()
        rig.engine.resume()
        val end = play.await()

        assertEquals(PlaybackState.Finished(emptyList()), end)
        assertEquals(14_000L + 20_000L, currentTime)
        assertEquals(listOf("4000 mic-on", "6000 mic-off", "26000 mic-on"), rig.events("mic").dropLast(1))
    }

    @Test
    fun `부모가 중단하면 복원하고 끝낸다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(3_500); runCurrent()
        rig.engine.stop()
        assertEquals(PlaybackState.Aborted(AbortReason.StoppedByParent, emptyList()), play.await())
        assertEquals(restoreCalls, rig.port.calls.takeLast(restoreCalls.size))
        assertEquals(rig.engine.state.value, PlaybackState.Aborted(AbortReason.StoppedByParent, emptyList()))
    }

    @Test
    fun `재생을 맡긴 코루틴이 취소돼도 복원까지 마친다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()
        play.cancel(); runCurrent()
        assertEquals(PlaybackState.Aborted(AbortReason.Cancelled, emptyList()), rig.engine.state.value)
        assertEquals(restoreCalls, rig.port.calls.takeLast(restoreCalls.size))
    }

    @Test
    fun `장면을 건너뛰면 대사와 효과음을 끊고 남은 차량 효과를 바로 적용한 뒤 다음 장면으로`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()
        rig.engine.skipScene(); runCurrent()

        assertEquals(PlaybackState.Playing(1, "wind", 0, story.scenes[1].lines[0]), rig.engine.state.value)
        assertEquals(1_000L, currentTime)
        // sky_01은 끝나지 않았고, 두 번째 대사(TTS)는 읽지 않았다
        assertEquals(emptyList<String>(), rig.events("line-end sky_01"))
        assertEquals(emptyList<String>(), rig.events("tts "))
        assertEquals(listOf("1000 sound-stop"), rig.events("sound-stop"))
        // at 2.5 바람 1단(풍량 2), at 10 바람 5단(풍량 6)을 적힌 시각 순서대로 바로 적용한 뒤 wind의 얼굴 방향
        assertEquals(listOf("fanSpeed=2", "fanSpeed=6", "airflow=FACE"), rig.port.calls.takeLast(3))
        play.cancel()
    }

    @Test
    fun `참여를 기다리다 건너뛰면 마이크를 끄고 성공 효과 없이 넘어간다, 마지막 장면을 지나면 끝난다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(4_001); runCurrent()
        assertTrue(rig.engine.state.value is PlaybackState.WaitingInteraction)
        rig.engine.skipScene()

        assertEquals(PlaybackState.Finished(emptyList()), play.await())
        assertEquals(4_001L, currentTime)
        assertTrue(rig.events("mic").last().endsWith("mic-off"))
        // 성공 효과(풍량 3·4)는 없다 (복원의 fanSpeed=3은 시작 전 값)
        assertFalse(rig.port.calls.dropLast(restoreCalls.size).any { it == "fanSpeed=3" || it == "fanSpeed=4" })
        assertEquals(restoreCalls, rig.port.calls.takeLast(restoreCalls.size))
    }

    @Test
    fun `건너뛰기를 여러 번 눌러도 지금 장면 하나만 넘어간다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()
        rig.engine.skipScene()
        rig.engine.skipScene()
        runCurrent()
        assertEquals(PlaybackState.Playing(1, "wind", 0, story.scenes[1].lines[0]), rig.engine.state.value)
        play.cancel()
    }

    @Test
    fun `멈춘 동안에는 건너뛰기를 무시한다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()
        rig.engine.pause(); runCurrent()
        rig.engine.skipScene(); runCurrent()
        val paused = rig.engine.state.value as PlaybackState.Paused
        assertEquals("sky", paused.during.sceneId)

        rig.engine.resume(); runCurrent()
        assertEquals("sky", (rig.engine.state.value as PlaybackState.Active).sceneId)
        play.cancel()
    }

    private val breatheStory = story.copy(
        scenes = listOf(
            Scene(
                id = "calm-breath",
                lines = emptyList(),
                effects = listOf(TimedEffect(0.0, Effect.Ambient(0xBFE3F5, 0.4f))),
                interaction = Interaction(
                    type = InteractionType.BREATHE,
                    keywords = emptyList(),
                    onSuccess = emptyList(),
                    fallbackButton = "그만하기",
                    timeoutSec = 0,
                    repeat = 2,
                    breath = Breath(
                        inhaleSec = 4f,
                        exhaleSec = 3f,
                        onInhale = listOf(Effect.AmbientBrightnessStep(0.2f), Effect.FanLevel(0)),
                        onExhale = listOf(Effect.AmbientBrightnessStep(-0.2f), Effect.FanLevel(1)),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `숨 맞추기는 듣지 않고 들이쉬기·내쉬기 박자대로 빛과 바람을 바꾼다`() = runTest {
        val rig = rig()
        val states = mutableListOf<PlaybackState>()
        val watcher = backgroundScope.launch { rig.engine.state.collect { states += it } }
        val play = async { rig.engine.play(breatheStory, settings) }

        runCurrent()
        assertEquals(listOf("ambientBrightness=40", "ambientBrightness=60"), rig.port.calls.filter { it.startsWith("ambientBrightness") })

        advanceTimeBy(3_999); runCurrent()
        assertFalse(rig.port.calls.any { it == "fanSpeed=2" })
        advanceTimeBy(1); runCurrent()
        // 내쉬기: 밝기는 원래대로, 바람 1단(풍량 2)
        assertEquals("ambientBrightness=40", rig.port.calls.last { it.startsWith("ambientBrightness") })
        assertEquals("fanSpeed=2", rig.port.calls.last { it.startsWith("fanSpeed") })

        // 4+3초씩 2번 = 14초에 끝난다
        advanceTimeBy(9_999); runCurrent()
        assertFalse(play.isCompleted)
        advanceTimeBy(1)
        assertEquals(PlaybackState.Finished(emptyList()), play.await())
        assertTrue("마이크를 켜지 않는다", rig.events("mic").isEmpty())
        val phases = states.filterIsInstance<PlaybackState.WaitingInteraction>().map { it.successes to it.breathPhase }
        assertEquals(
            // 숨 한 번을 센 상태(1, EXHALE)는 곧바로 다음 들이쉬기로 바뀌어 StateFlow에서 합쳐진다. 마지막 번은 남는다
            listOf(0 to BreathPhase.INHALE, 0 to BreathPhase.EXHALE, 1 to BreathPhase.INHALE, 1 to BreathPhase.EXHALE, 2 to BreathPhase.EXHALE),
            phases.distinct(),
        )
        watcher.cancel()
    }

    @Test
    fun `숨 맞추기 중 대체 버튼을 누르면 남은 숨쉬기를 그만두고 넘어간다`() = runTest {
        val rig = rig()
        val play = async { rig.engine.play(breatheStory, settings) }
        advanceTimeBy(5_000); runCurrent()
        rig.engine.pressFallback()
        assertEquals(PlaybackState.Finished(emptyList()), play.await())
        assertEquals(5_000L, currentTime)
    }

    // ---- 무드등 전환 (fadeSec) ----

    /** 무드등이 켜져 있던 차: 빨강·밝기 40에서 시작 */
    private val litBefore = before.copy(ambientMode = AmbientMode.ON, ambientColorRgb = 0xFF0000, ambientBrightness = 40)

    private fun fadeStory(vararg effects: TimedEffect) = Story(
        id = "fade",
        title = "노을",
        ageRange = "4-7",
        durationSec = 10,
        scenes = listOf(Scene(id = "dusk", lines = listOf(Line("narrator", "노을이 져요", audio = "dusk.mp3")), effects = effects.toList())),
        familyTalkCards = emptyList(),
    )

    private fun TestScope.fadeRig() = Rig(this, litBefore).apply { audio.durations["dusk.mp3"] = 6_000 }

    private fun Rig.ambientCalls() = port.calls.filter { it.startsWith("ambientColor") || it.startsWith("ambientBrightness") }

    @Test
    fun `fadeSec 동안 지금 조명에서 목표 색과 밝기로 서서히 바꾸고, 같은 시각의 다른 효과를 막지 않는다`() = runTest {
        val rig = fadeRig()
        val story = fadeStory(
            TimedEffect(0.0, Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f)),
            TimedEffect(0.0, Effect.Sound("dusk_wind.mp3")),
        )
        val play = async { rig.engine.play(story, settings) }

        runCurrent()
        // 시작 전 조명(빨강·40)에서 출발. 효과음은 전환을 기다리지 않는다
        assertEquals(listOf("ambientAuto=false", "ambientMode=ON", "ambientColor=FF0000", "ambientBrightness=40"), rig.port.calls)
        assertEquals(listOf("0 sound dusk_wind.mp3 loop=false"), rig.events("sound"))

        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("ambientColor=800080", "ambientBrightness=60"), rig.ambientCalls().takeLast(2))

        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("ambientColor=0000FF", "ambientBrightness=80"), rig.ambientCalls().takeLast(2))
        // 0.1초마다 한 번씩, 처음과 끝을 합쳐 21번 (색·밝기가 매번 바뀜)
        assertEquals(42, rig.ambientCalls().size)

        advanceTimeBy(2_000); runCurrent()
        assertEquals(42, rig.ambientCalls().size)
        play.cancel()
    }

    @Test
    fun `멈춘 동안에는 전환도 멈추고, 재개하면 남은 만큼 이어 간다`() = runTest {
        val rig = fadeRig()
        val play = async { rig.engine.play(fadeStory(TimedEffect(0.0, Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f))), settings) }
        advanceTimeBy(1_000); runCurrent()
        rig.audio.loseFocus(); runCurrent()
        val sent = rig.ambientCalls().size

        advanceTimeBy(5_000); runCurrent()
        assertEquals(sent, rig.ambientCalls().size)

        rig.audio.regainFocus(); rig.engine.resume(); runCurrent()
        advanceTimeBy(999); runCurrent()
        assertFalse(rig.ambientCalls().contains("ambientBrightness=80"))
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("ambientColor=0000FF", "ambientBrightness=80"), rig.ambientCalls().takeLast(2))
        play.cancel()
    }

    @Test
    fun `전환 중에 다음 조명 효과가 오면 이전 전환을 끊고 새 효과를 따른다`() = runTest {
        val rig = fadeRig()
        val story = fadeStory(
            TimedEffect(0.0, Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f)),
            TimedEffect(1.0, Effect.Ambient(0x00FF00, 0.2f)),
        )
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("ambientColor=00FF00", "ambientBrightness=20"), rig.ambientCalls().takeLast(2))
        val sent = rig.ambientCalls().size

        advanceTimeBy(2_000); runCurrent()
        assertEquals(sent, rig.ambientCalls().size)
        play.cancel()
    }

    @Test
    fun `밝기 단계 효과는 진행 중인 전환을 끊고 목표 밝기에 더한다`() = runTest {
        val rig = fadeRig()
        val story = fadeStory(
            TimedEffect(0.0, Effect.Ambient(0x0000FF, 0.5f, fadeSec = 2f)),
            TimedEffect(1.0, Effect.AmbientBrightnessStep(0.2f)),
        )
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()
        // 목표(파랑·0.5)에 0.2를 더해 파랑·0.7로 바로 맞춘다
        assertEquals(listOf("ambientColor=0000FF", "ambientBrightness=70"), rig.ambientCalls().takeLast(2))
        val sent = rig.ambientCalls().size
        advanceTimeBy(2_000); runCurrent()
        assertEquals(sent, rig.ambientCalls().size)
        play.cancel()
    }

    @Test
    fun `장면을 건너뛰면 남은 조명 효과는 서서히 바꾸지 않고 바로 적용한다`() = runTest {
        val rig = fadeRig()
        val story = fadeStory(TimedEffect(3.0, Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f)))
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(1_000); runCurrent()
        rig.engine.skipScene(); runCurrent()
        // 건너뛴 직후 바로 목표 값. 그 뒤 마지막 장면이 끝나 복원(빨강·40)이 이어진다
        val calls = rig.ambientCalls()
        assertEquals(listOf("ambientColor=0000FF", "ambientBrightness=80"), calls.take(2))
        assertFalse(calls.contains("ambientColor=800080"))
        play.cancel()
    }

    @Test
    fun `restore의 fadeSec 동안 시작 전 조명으로 서서히 돌린 뒤 되돌린다`() = runTest {
        val rig = fadeRig()
        val story = fadeStory(
            TimedEffect(0.0, Effect.Ambient(0x0000FF, 0.8f)),
            TimedEffect(3.0, Effect.Restore(fadeSec = 1f)),
        )
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(3_500); runCurrent()
        assertEquals(listOf("ambientColor=800080", "ambientBrightness=60"), rig.ambientCalls().takeLast(2))
        assertFalse(rig.port.calls.contains("fanSpeed=3"))

        advanceTimeBy(500); runCurrent()
        // 전환 끝(빨강·40) 뒤에 저장한 값으로 복원
        assertTrue(rig.port.calls.contains("ambientAuto=true"))
        assertTrue(rig.port.calls.contains("fanSpeed=3"))
        play.cancel()
    }

    @Test
    fun `전환 중 차가 움직이면 전환을 멈추고 바로 복원한다`() = runTest {
        val rig = fadeRig()
        val play = async { rig.engine.play(fadeStory(TimedEffect(0.0, Effect.Ambient(0x0000FF, 0.8f, fadeSec = 2f))), settings) }
        advanceTimeBy(1_000); runCurrent()
        rig.states.state.value = PARKED.copy(gear = Gear.DRIVE, speedMps = 3f)
        val end = play.await()

        assertTrue(end is PlaybackState.Aborted)
        val afterRestore = rig.port.calls.dropWhile { it != "fanSpeed=3" }
        assertTrue(afterRestore.isNotEmpty())
        // 복원 뒤에는 전환 값을 더 보내지 않는다
        assertEquals("ambientAuto=true", afterRestore.last { it.startsWith("ambient") })
    }

    @Test
    fun `밝기 단계에 fadeSec가 있으면 지금 밝기에서 목표 밝기에 더한 값으로 서서히 바꾼다`() = runTest {
        val rig = fadeRig()
        val story = fadeStory(
            TimedEffect(0.0, Effect.Ambient(0x0000FF, 0.4f)),
            TimedEffect(1.0, Effect.AmbientBrightnessStep(0.4f, max = 0.6f, fadeSec = 2f)),
        )
        val play = async { rig.engine.play(story, settings) }
        advanceTimeBy(2_000); runCurrent()
        // 40 → 60 (max 0.6) 의 절반. 색은 그대로라 밝기만 보낸다
        assertEquals("ambientBrightness=50", rig.ambientCalls().last())
        assertFalse(rig.ambientCalls().drop(2).any { it.startsWith("ambientColor") })
        advanceTimeBy(1_000); runCurrent()
        assertEquals("ambientBrightness=60", rig.ambientCalls().last())
        play.cancel()
    }
}
