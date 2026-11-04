package com.breathingtales.domain.usecase

import com.breathingtales.domain.model.AudioClip
import com.breathingtales.domain.model.BreathPhase
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Interaction
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.model.Line
import com.breathingtales.domain.model.Scene
import com.breathingtales.domain.model.Story
import com.breathingtales.domain.port.AudioFocus
import com.breathingtales.domain.port.AudioPlayerPort
import com.breathingtales.domain.port.SpeechPort
import com.breathingtales.domain.port.VoiceInputPort
import com.breathingtales.domain.safety.AmbientFade
import com.breathingtales.domain.safety.ParkedState
import com.breathingtales.domain.safety.SafetyGuard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 동화 엔진 (07, 기획서 8-1·8-2). 장면 순서대로 대사를 재생하고, 장면 시작 기준 시각에 효과를 내보내고,
 * 대사가 끝나면 아이 참여를 기다린다. 재생 규칙은 docs/dev/03 문서, 차량 제어는 [SafetyGuard]를 거친다.
 *
 * 사용: ViewModel이 [play]를 코루틴으로 띄우고 [state]를 본다. 버튼은 [pause]·[resume]·[stop]·[pressFallback]·[skipScene].
 * [play]가 어떻게 끝나든(완료, 정차 해제, 중단, 취소) 소리를 멈추고 실내를 복원한 뒤 돌아온다.
 */
@Singleton
class PlayStoryUseCase(
    private val guard: SafetyGuard,
    private val parkedStates: ObserveParkedStateUseCase,
    private val audio: AudioPlayerPort,
    private val speech: SpeechPort,
    private val voice: VoiceInputPort,
    private val timeSource: TimeSource,
) {
    @Inject
    constructor(
        guard: SafetyGuard,
        parkedStates: ObserveParkedStateUseCase,
        audio: AudioPlayerPort,
        speech: SpeechPort,
        voice: VoiceInputPort,
    ) : this(guard, parkedStates, audio, speech, voice, TimeSource.Monotonic)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data object Stop : Command
        data object Fallback : Command
        data object Skip : Command
    }

    /** 재생 중일 때만 있다 */
    @Volatile
    private var commands: Channel<Command>? = null

    fun pause() = send(Command.Pause)

    /** 부모 일시정지와 오디오 포커스로 멈춘 것 모두 다시 재생한다. 포커스를 받지 못하면(통화 중) 멈춘 채로 둔다 */
    fun resume() = send(Command.Resume)

    fun stop() = send(Command.Stop)

    /** 참여 대신 누르는 버튼 (`fallbackButton`). 참여를 기다릴 때만 성공 한 번으로 센다 */
    fun pressFallback() = send(Command.Fallback)

    /**
     * 지금 장면을 건너뛰고 다음 장면으로 간다. 마지막 장면이면 이야기를 마친다. 멈춘 동안에는 무시한다.
     * 건너뛴 장면의 대사·참여·효과음·시트 들썩임은 끊고, 아직 나가지 않은 차량 효과는 바로 적용해 그 장면이 끝났을 때의 실내로 맞춘다.
     */
    fun skipScene() = send(Command.Skip)

    private fun send(command: Command) {
        commands?.trySend(command)
    }

    /**
     * 동화를 처음부터 끝까지 재생한다. 끝나면 [PlaybackState.Finished] 또는 [PlaybackState.Aborted]를 돌려준다.
     * 호출한 코루틴이 취소되면 복원까지 마치고 취소를 다시 던진다.
     */
    suspend fun play(story: Story, settings: PlaySettings): PlaybackState {
        check(commands == null) { "이미 재생 중" }
        val channel = Channel<Command>(Channel.UNLIMITED)
        commands = channel
        _state.value = PlaybackState.Preparing
        try {
            return Session(story, settings, channel).run().also { _state.value = it }
        } finally {
            commands = null
            channel.close()
        }
    }

    /** 중단 신호. 형제 코루틴(재생)을 함께 끝내려고 취소가 아닌 예외로 던진다 */
    private class AbortSignal(val reason: AbortReason) : Exception()

    private inner class Session(
        private val story: Story,
        private val settings: PlaySettings,
        private val commands: Channel<Command>,
    ) {
        private val gate = PauseGate(timeSource)
        private val fallbacks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        /** 건너뛰기를 요청받은 장면 번호. 장면 번호로 맞춰 보므로, 장면이 바뀌는 사이에 누른 건너뛰기가 다음 장면까지 넘기지 않는다 */
        private val skipRequests = MutableStateFlow(-1)

        /** 화면에 보여 줄 진행 상태. 멈추면 [PlaybackState.Paused]로 감싼다 */
        @Volatile
        private var active: PlaybackState.Active? = null

        // 단계 효과의 현재 값 (효과 큐와 참여가 함께 쓰므로 실행은 [effectLock]으로 한 번에 하나씩)
        private val effectLock = Mutex()
        private var fanLevel = 0
        private var ambient: Effect.Ambient? = null

        /** 무드등 전환(`fadeSec`)을 돌리는 범위와 지금 돌고 있는 전환. 새 조명 효과가 오면 이전 전환을 끊는다 */
        private var fadeScope: CoroutineScope? = null
        private var fadeJob: Job? = null

        suspend fun run(): PlaybackState {
            try {
                if (!guard.begin(settings.intensity, settings.seatMotion)) {
                    val now = parkedStates.beforeShow().first()
                    return PlaybackState.Aborted(AbortReason.NotParked(reasonsOf(now)), emptyList())
                }
                if (!audio.start()) {
                    return PlaybackState.Aborted(AbortReason.AudioFocusUnavailable, guard.restore())
                }
                coroutineScope {
                    fadeScope = this
                    val watchers = launch {
                        launch { watchParked() }
                        launch { watchAudioFocus() }
                        launch { handleCommands() }
                    }
                    story.scenes.forEachIndexed { index, scene -> playScene(index, scene) }
                    watchers.cancel()
                    fadeJob?.cancel()
                }
                return PlaybackState.Finished(endShow())
            } catch (signal: AbortSignal) {
                return PlaybackState.Aborted(signal.reason, endShow())
            } catch (e: CancellationException) {
                // 시작 전에 취소됐어도 되돌릴 것이 없으면 둘 다 아무것도 하지 않는다
                _state.value = PlaybackState.Aborted(AbortReason.Cancelled, endShow())
                throw e
            }
        }

        /** 소리를 먼저 끄고 실내를 되돌린다. 취소 중에도 끝까지 */
        private suspend fun endShow(): List<String> = withContext(NonCancellable) {
            audio.stop()
            guard.restore()
        }

        private suspend fun watchParked() {
            parkedStates.duringShow().collect { parked ->
                if (parked is ParkedState.NotParked) throw AbortSignal(AbortReason.NotParked(parked.reasons))
            }
        }

        private suspend fun watchAudioFocus() {
            audio.focus.collect { focus ->
                // 소리는 어댑터가 이미 멈췄다. 효과 시계와 참여도 멈춘다
                if (focus == AudioFocus.LOST_TRANSIENT || focus == AudioFocus.LOST) {
                    if (gate.pause(PauseReason.AUDIO_FOCUS)) publish()
                }
            }
        }

        private suspend fun handleCommands() {
            for (command in commands) {
                when (command) {
                    Command.Pause -> if (gate.pause(PauseReason.PARENT)) {
                        audio.pause()
                        publish()
                    }
                    Command.Resume -> if (gate.reason.value != null && audio.resume()) {
                        gate.resume()
                        publish()
                    }
                    Command.Stop -> throw AbortSignal(AbortReason.StoppedByParent)
                    Command.Fallback -> if (gate.reason.value == null) fallbacks.tryEmit(Unit)
                    Command.Skip -> if (gate.reason.value == null) active?.let { skipRequests.value = it.sceneIndex }
                }
            }
        }

        private suspend fun playScene(index: Int, scene: Scene) = coroutineScope {
            val sceneStart = gate.now()
            // 효과는 대사와 별개로 장면 시작 기준 시각에 나간다. 같은 시각이면 적힌 순서대로
            val queue = scene.effects.sortedBy { it.atSec }
            var executed = 0
            val effects = launch {
                for (timed in queue) {
                    gate.awaitTime(sceneStart + timed.atSec.seconds)
                    // 장면이 끝나 큐를 멈출 때 효과를 반쯤 실행한 채로 끊지 않는다
                    withContext(NonCancellable) { execute(timed.effect) }
                    executed++
                }
            }
            val skipped = runUnlessSkipped(index) {
                scene.lines.forEachIndexed { lineIndex, line ->
                    show(PlaybackState.Playing(index, scene.id, lineIndex, line))
                    playLine(line)
                    // 흉내 낼 틈. 효과 시계라 멈춘 동안은 흐르지 않는다
                    if (line.pauseAfterSec > 0f) gate.awaitTime(gate.now() + line.pauseAfterSec.toDouble().seconds)
                }
                scene.interaction?.let {
                    if (it.type == InteractionType.BREATHE) guideBreathing(index, scene, it) else waitInteraction(index, scene, it)
                }
            }
            val sceneEnd = gate.now()
            effects.cancelAndJoin()
            val rest = queue.drop(executed)
            if (skipped) {
                // 건너뛴 장면의 효과음은 끊고, 남은 차량 효과는 바로 적용해 장면 끝의 실내로 맞춘다.
                // 시트 들썩임은 그 순간의 연출이라 효과음처럼 버린다
                attempt { audio.stopSounds() }
                // 조명은 서서히 바꾸지 않고 바로 장면 끝 색으로 맞춘다
                rest.map { it.effect }.filterNot { it is Effect.Sound || it is Effect.SeatLift }
                    .map {
                        when (it) {
                            is Effect.Ambient -> it.copy(fadeSec = 0f)
                            is Effect.AmbientBrightnessStep -> it.copy(fadeSec = 0f)
                            else -> it
                        }
                    }
                    .forEach { execute(it) }
            } else {
                // 장면이 끝난 시각까지 온 효과는 마저 실행하고(대사 없는 장면의 at=0 등), 그 뒤 효과는 버린다
                rest.takeWhile { sceneStart + it.atSec.seconds <= sceneEnd }.forEach { execute(it.effect) }
            }
        }

        /** 장면 [index]의 [block]을 실행하다 건너뛰기를 받으면 끊고 true */
        private suspend fun runUnlessSkipped(index: Int, block: suspend () -> Unit): Boolean = coroutineScope {
            val work = async { block() }
            val skip = async { skipRequests.first { it == index } }
            select {
                work.onAwait {
                    skip.cancel()
                    false
                }
                skip.onAwait {
                    work.cancel()
                    true
                }
            }
        }

        private suspend fun playLine(line: Line) {
            val audioPath = line.audio
            if (audioPath != null) {
                // 일시정지 중에도 어댑터가 멈춘 채 기다리므로 그대로 맡긴다
                attempt { audio.playLine(AudioClip(story.id, audioPath)) }
                return
            }
            // TTS는 일시정지가 없으므로, 멈추면 끊고 재개 뒤 그 문장을 처음부터 다시 읽는다
            val text = line.text.replace(CHILD_NAME, settings.childName)
            while (true) {
                gate.awaitRunning()
                if (runUnlessPaused { attempt { speech.speak(text) } }) return
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun waitInteraction(index: Int, scene: Scene, interaction: Interaction) = coroutineScope {
            var successes = 0
            show(PlaybackState.WaitingInteraction(index, scene.id, interaction, successes))
            val done = CompletableDeferred<Unit>()
            val deadline = gate.now() + interaction.timeoutSec.seconds
            val timer = launch {
                gate.awaitTime(deadline)
                done.complete(Unit)
            }
            // 멈춘 동안은 마이크를 끈다
            val heard = gate.reason.flatMapLatest { paused ->
                // 마이크 권한이 없거나 STT가 실패하면 듣기만 끄고 대체 버튼·제한 시간으로 이어 간다
                if (paused == null) voice.listen(interaction.type, interaction.keywords).catch { } else emptyFlow()
            }
            val listener = launch {
                merge(heard.map { }, fallbacks).collect {
                    if (done.isCompleted || gate.reason.value != null) return@collect
                    successes++
                    show(PlaybackState.WaitingInteraction(index, scene.id, interaction, successes))
                    interaction.onSuccess.forEach { execute(it) }
                    if (successes >= interaction.repeat) done.complete(Unit)
                }
            }
            done.await()
            timer.cancel()
            listener.cancel()
        }

        /**
         * 숨 맞추기: 듣지 않고 박자대로 들이쉬기·내쉬기를 [Interaction.repeat]번 이끈다.
         * 한 번 끝날 때마다 성공으로 세고 onSuccess를 실행한다. 대체 버튼은 남은 숨쉬기를 그만두고 넘어간다.
         * 박자는 효과 시계를 쓰므로 멈춘 동안은 흐르지 않는다.
         */
        private suspend fun guideBreathing(index: Int, scene: Scene, interaction: Interaction) = coroutineScope {
            val breath = interaction.breath ?: return@coroutineScope
            val done = CompletableDeferred<Unit>()
            val stopper = launch {
                fallbacks.collect { if (gate.reason.value == null) done.complete(Unit) }
            }
            val cycles = launch {
                var successes = 0
                repeat(interaction.repeat) {
                    show(PlaybackState.WaitingInteraction(index, scene.id, interaction, successes, BreathPhase.INHALE))
                    breath.onInhale.forEach { execute(it) }
                    gate.awaitTime(gate.now() + breath.inhaleSec.toDouble().seconds)
                    show(PlaybackState.WaitingInteraction(index, scene.id, interaction, successes, BreathPhase.EXHALE))
                    breath.onExhale.forEach { execute(it) }
                    gate.awaitTime(gate.now() + breath.exhaleSec.toDouble().seconds)
                    successes++
                    show(PlaybackState.WaitingInteraction(index, scene.id, interaction, successes, BreathPhase.EXHALE))
                    interaction.onSuccess.forEach { execute(it) }
                }
                done.complete(Unit)
            }
            done.await()
            cycles.cancel()
            stopper.cancel()
        }

        /** 효과 하나. 실패해도 이야기는 이어 간다 */
        private suspend fun execute(effect: Effect) = effectLock.withLock { attempt { executeLocked(effect) } }

        private suspend fun executeLocked(effect: Effect) {
            when (effect) {
                is Effect.Sound -> audio.playSound(AudioClip(story.id, effect.audio), effect.loop, effect.volume)
                is Effect.Restore -> restoreCabin(effect)
                is Effect.FanLevel -> {
                    fanLevel = effect.level
                    guard.apply(effect)
                }
                is Effect.FanLevelStep -> {
                    fanLevel = (fanLevel + effect.delta).coerceIn(0, effect.max)
                    guard.apply(Effect.FanLevel(fanLevel))
                }
                is Effect.Ambient -> {
                    ambient = effect
                    startAmbient(effect)
                }
                is Effect.AmbientBrightnessStep -> {
                    // 색을 정한 적이 없으면 밝기만 바꿀 수 없다
                    val last = ambient ?: return
                    val next = last.copy(brightness = (last.brightness + effect.delta).coerceIn(0f, effect.max), fadeSec = effect.fadeSec)
                    ambient = next
                    startAmbient(next)
                }
                is Effect.FanDirection, is Effect.TemperatureDelta, is Effect.SeatHeat, is Effect.SeatLift,
                is Effect.CabinLight, is Effect.Spotlight -> guard.apply(effect)
            }
        }

        /**
         * 무드등을 [effect]로 바꾼다. `fadeSec`가 있으면 효과 시계에 맞춰 [FADE_FRAME]마다 중간값을 보내며 서서히 바꾼다.
         * 전환은 따로 돌아 같은 시각의 다른 효과를 막지 않고, 멈춘 동안은 멈추며, 다음 조명 효과가 오면 그 자리에서 이어받는다.
         */
        private suspend fun startAmbient(effect: Effect.Ambient) {
            fadeJob?.cancelAndJoin()
            fadeJob = null
            val scope = fadeScope
            val fade = if (effect.fadeSec > 0f && scope != null) guard.ambientFade(effect) else null
            if (scope == null || fade == null) {
                guard.apply(effect)
                return
            }
            fadeJob = scope.launch { runFade(fade, effect.fadeSec) }
        }

        /** 동화의 `restore`. `fadeSec`가 있으면 무드등을 시작 전 조명으로 서서히 돌린 뒤 되돌린다 */
        private suspend fun restoreCabin(effect: Effect.Restore) {
            fadeJob?.cancelAndJoin()
            fadeJob = null
            val scope = fadeScope
            val fade = if (effect.fadeSec > 0f && scope != null) guard.ambientRestoreFade() else null
            if (scope == null || fade == null) {
                guard.restore()
                return
            }
            fadeJob = scope.launch {
                runFade(fade, effect.fadeSec)
                guard.restore()
            }
        }

        private suspend fun runFade(fade: AmbientFade, fadeSec: Float) {
            val start = gate.now()
            val total = fadeSec.toDouble().seconds
            while (true) {
                val progress = ((gate.now() - start) / total).toFloat().coerceIn(0f, 1f)
                // 한 번 실패해도 다음 값에서 다시 보낸다. 전환은 연출 범위의 자식이라 예외가 새면 이야기가 끝난다
                val sent = try {
                    guard.applyAmbientFade(fade, progress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    true
                }
                if (!sent) return
                if (progress >= 1f) return
                gate.awaitTime(minOf(gate.now() + FADE_FRAME, start + total))
            }
        }

        /** [block]을 실행하다 멈추면 끊고 false */
        private suspend fun runUnlessPaused(block: suspend () -> Unit): Boolean = coroutineScope {
            val work = async { block() }
            val paused = async { gate.reason.first { it != null } }
            select {
                work.onAwait {
                    paused.cancel()
                    true
                }
                paused.onAwait {
                    work.cancel()
                    false
                }
            }
        }

        private fun show(state: PlaybackState.Active) {
            active = state
            publish()
        }

        private fun publish() {
            val current = active ?: return
            val reason = gate.reason.value
            _state.value = if (reason == null) current else PlaybackState.Paused(reason, current)
        }
    }

    private fun reasonsOf(state: ParkedState) = (state as? ParkedState.NotParked)?.reasons.orEmpty()

    /** 실패하면 넘어간다. 취소는 삼키지 않는다 */
    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 대사 하나, 효과 하나가 실패해도 이야기는 이어 간다 (로그는 어댑터가 남긴다)
        }
    }

    private companion object {
        const val CHILD_NAME = "{childName}"

        /** 무드등 전환 중간값을 보내는 간격. 한 번에 색·밝기 두 번까지 차량 호출을 한다 */
        val FADE_FRAME = 100.milliseconds
    }
}
