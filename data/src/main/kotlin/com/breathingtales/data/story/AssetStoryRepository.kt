package com.breathingtales.data.story

import android.content.Context
import com.breathingtales.domain.model.Story
import com.breathingtales.domain.repository.StoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** 앱에 내장된 `assets/stories/<id>/story.json`을 읽는다 (기획서 8-2: MVP는 서버 없이 내장) */
@Singleton
class AssetStoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : StoryRepository {

    override suspend fun storyIds(): List<String> = withContext(Dispatchers.IO) {
        context.assets.list(ROOT).orEmpty()
            .filter { id -> context.assets.list("$ROOT/$id").orEmpty().contains(STORY_FILE) }
            .sorted()
    }

    override suspend fun load(id: String): Story = withContext(Dispatchers.IO) {
        val dir = "$ROOT/$id"
        val text = try {
            context.assets.open("$dir/$STORY_FILE").bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            throw IllegalArgumentException("동화 '$id'가 없음 ($dir/$STORY_FILE)", e)
        }
        StoryParser.parse(id, text) { path -> assetExists("$dir/$path") }
    }

    private fun assetExists(path: String): Boolean {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return context.assets.list(parent).orEmpty().contains(name)
    }

    private companion object {
        const val ROOT = "stories"
        const val STORY_FILE = "story.json"
    }
}
