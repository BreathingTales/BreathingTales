package com.breathingtales.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.breathingtales.BuildConfig
import com.breathingtales.data.vehicle.EmulatorVehicleState
import com.breathingtales.domain.model.Story
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.repository.SettingsRepository
import com.breathingtales.domain.repository.StoryRepository
import com.breathingtales.domain.repository.VehicleStateRepository
import com.breathingtales.domain.safety.SafetyGuard
import com.breathingtales.domain.safety.SafetyMonitor
import com.breathingtales.domain.usecase.PlayStoryUseCase
import com.breathingtales.domain.usecase.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayScreenState {
    data object Loading : PlayScreenState

    /** 엔진이 재생 중. [playback]은 엔진 상태 그대로 */
    data class Running(val story: Story, val childName: String) : PlayScreenState

    /**
     * 엔진이 끝남 (완료 또는 중단). 화면이 다음으로 넘어가거나 안내를 띄운다.
     * [waitingForPark]는 되돌리지 못해 다시 정차하면 되돌릴 항목 (끝날 때의 [SafetyGuard.pendingRestore])
     */
    data class Ended(val story: Story?, val result: PlaybackState, val waitingForPark: Set<String> = emptySet()) : PlayScreenState

    data class Failed(val message: String) : PlayScreenState
}

/**
 * 재생 화면. 화면에 들어오면 바로 재생을 시작하고, 화면을 떠나면(ViewModel 정리) 재생을 취소한다.
 * 취소돼도 엔진이 소리를 끄고 실내를 복원한다 (07).
 */
@HiltViewModel
class PlayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stories: StoryRepository,
    private val settings: SettingsRepository,
    private val engine: PlayStoryUseCase,
    private val guard: SafetyGuard,
    vehicleStates: VehicleStateRepository,
    emulator: EmulatorVehicleState,
) : ViewModel() {

    private val storyId: String = checkNotNull(savedStateHandle[ARG_STORY_ID])

    private val _screen = MutableStateFlow<PlayScreenState>(PlayScreenState.Loading)
    val screen: StateFlow<PlayScreenState> = _screen.asStateFlow()

    /** 재생 중 상태 (대사, 참여, 일시정지). [PlayScreenState.Running]일 때만 의미가 있다 */
    val playback: StateFlow<PlaybackState> = engine.state

    /** 시연 HUD (11): debug 빌드에서 켜져 있을 때만 화면이 본다. release는 저장된 설정과 관계없이 늘 꺼짐 */
    val demoHud: Boolean = BuildConfig.DEBUG && settings.settings.value.demoHud
    val safetyMonitor: StateFlow<SafetyMonitor> = guard.monitor
    val vehicleState: StateFlow<VehicleState> = vehicleStates.state

    /** 되돌리지 못해 다시 정차하기를 기다리는 항목. 앱이 정차를 보고 되돌리면 빠진다 ([BreathingTalesApp][com.breathingtales.BreathingTalesApp]) */
    val pendingRestore: StateFlow<List<String>> = guard.pendingRestore

    /**
     * 시연 모드의 에뮬레이터에서만: Virtual Console AVD 상태를 1초마다 읽는다.
     * 꺼지면 차량 명령이 오류 없이 무시되므로 HUD에 바로 띄운다. 그 밖에는 null
     */
    val avd: StateFlow<AvdStatus?> =
        if (demoHud && EmulatorVehicleState.isEmulator) {
            flow {
                while (true) {
                    emit(
                        when (emulator.avdAvailable()) {
                            true -> AvdStatus.ON
                            false -> AvdStatus.OFF
                            null -> AvdStatus.UNREACHABLE
                        },
                    )
                    delay(AVD_POLL_MS)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        } else {
            MutableStateFlow(null)
        }

    init {
        viewModelScope.launch { run() }
    }

    fun pause() = engine.pause()

    fun resume() = engine.resume()

    fun stop() = engine.stop()

    fun pressFallback() = engine.pressFallback()

    fun skipScene() = engine.skipScene()

    private suspend fun run() {
        val story = try {
            stories.load(storyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _screen.value = PlayScreenState.Failed("동화를 열지 못했어요: ${e.message}")
            return
        }
        val parent = settings.settings.value
        // 상태를 Running으로 바꾸고 곧바로 play를 부르므로, 화면은 지난 재생의 엔진 상태를 보지 않는다
        _screen.value = PlayScreenState.Running(story, parent.childName)
        val result = try {
            engine.play(story, parent.toPlaySettings())
        } catch (e: IllegalStateException) {
            // 앞 재생이 아직 복원 중이면 시작하지 않는다
            _screen.value = PlayScreenState.Failed("앞 이야기를 정리하는 중이에요. 잠시 뒤에 다시 시작해 주세요")
            return
        }
        _screen.value = PlayScreenState.Ended(story, result, guard.pendingRestore.value.toSet())
    }

    companion object {
        const val ARG_STORY_ID = "storyId"
        private const val AVD_POLL_MS = 1_000L
    }
}

/** 에뮬레이터 Virtual Console의 AVD 상태 (시연 HUD) */
enum class AvdStatus { ON, OFF, UNREACHABLE }
