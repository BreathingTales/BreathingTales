package com.breathingtales.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.breathingtales.domain.repository.SettingsRepository
import com.breathingtales.domain.repository.StoryRepository
import com.breathingtales.ui.UiTexts
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TalkCards(val storyTitle: String, val cards: List<String>)

/** 가족 대화 카드 (기획서 7장 장면 5). `{childName}`은 아이 이름으로 바꿔 보여 준다 */
@HiltViewModel
class TalkCardsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    stories: StoryRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val storyId: String = checkNotNull(savedStateHandle[PlayViewModel.ARG_STORY_ID])

    private val _cards = MutableStateFlow<TalkCards?>(null)
    val cards: StateFlow<TalkCards?> = _cards.asStateFlow()

    init {
        viewModelScope.launch {
            val childName = settings.settings.value.childName
            _cards.value = try {
                val story = stories.load(storyId)
                TalkCards(story.title, story.familyTalkCards.map { UiTexts.fillChildName(it, childName) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TalkCards("", emptyList())
            }
        }
    }
}
