package com.breathingtales.domain.repository

import com.breathingtales.domain.model.Story

interface StoryRepository {
    /** 설치된 동화 id 목록 */
    suspend fun storyIds(): List<String>

    /** @throws com.breathingtales.domain.model.InvalidStoryException 스키마를 어겼을 때 */
    suspend fun load(id: String): Story
}
