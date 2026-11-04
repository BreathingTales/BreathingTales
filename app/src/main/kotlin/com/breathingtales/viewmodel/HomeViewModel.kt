package com.breathingtales.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.breathingtales.domain.model.InvalidStoryException
import com.breathingtales.domain.model.ParentSettings
import com.breathingtales.domain.repository.SettingsRepository
import com.breathingtales.domain.repository.StoryRepository
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.safety.ParkedState
import com.breathingtales.domain.usecase.ObserveParkedStateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 홈 목록에 보여 줄 동화 요약 */
data class StoryCard(
    val id: String,
    val title: String,
    val ageRange: String,
    val durationSec: Int,
    /** `stories/<id>/<cover>` 에셋 경로. 없으면 null */
    val coverPath: String? = null,
)

sealed interface StoryList {
    data object Loading : StoryList
    data class Loaded(val cards: List<StoryCard>, val errors: List<String>) : StoryList
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val stories: StoryRepository,
    private val settingsRepository: SettingsRepository,
    parkedStates: ObserveParkedStateUseCase,
) : ViewModel() {

    val settings: StateFlow<ParentSettings> = settingsRepository.settings

    /** 처음 값을 받기 전에는 null (시작 버튼을 막는다) */
    val parked: StateFlow<ParkedState?> = parkedStates.beforeShow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _list = MutableStateFlow<StoryList>(StoryList.Loading)
    val list: StateFlow<StoryList> = _list.asStateFlow()

    init {
        viewModelScope.launch { _list.value = loadAll() }
    }

    fun setChildName(name: String) = settingsRepository.update { it.copy(childName = name.trim()) }

    fun setIntensity(intensity: Intensity) = settingsRepository.update { it.copy(intensity = intensity) }

    private suspend fun loadAll(): StoryList.Loaded {
        val cards = mutableListOf<StoryCard>()
        val errors = mutableListOf<String>()
        val ids = try {
            stories.storyIds()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StoryList.Loaded(emptyList(), listOf("동화 목록을 읽지 못했어요: ${e.message}"))
        }
        for (id in ids) {
            try {
                val story = stories.load(id)
                cards += StoryCard(story.id, story.title, story.ageRange, story.durationSec, story.cover?.let { "stories/${story.id}/$it" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidStoryException) {
                errors += "‘$id’ 동화 파일에 문제가 있어요 (${e.issues.size}건)"
            } catch (e: Exception) {
                errors += "‘$id’ 동화를 열지 못했어요: ${e.message}"
            }
        }
        return StoryList.Loaded(cards, errors)
    }
}
