package com.breathingtales.data.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breathingtales.domain.model.AudioClip
import com.breathingtales.domain.port.AudioFocus
import com.breathingtales.domain.port.AudioPlaybackException
import com.breathingtales.domain.port.LinePosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 에뮬레이터에서 실제 Media3·AudioManager로 확인한다. 음원은 `src/androidTest/assets/stories/audio-test/`의 440Hz 사인파.
 * 실행: `./gradlew :data:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class Media3AudioPlayerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val player = Media3AudioPlayer(context)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @After
    fun tearDown() = runBlocking { player.stop() }

    @Test
    fun line_plays_to_end_and_position_is_tracked() = runBlocking {
        assertTrue(player.start())
        val samples = mutableListOf<Pair<Long, LinePosition>>()
        val collector = launch(Dispatchers.Default) {
            player.linePosition.collect { p -> if (p != null) synchronized(samples) { samples += SystemClock.elapsedRealtime() to p } }
        }

        withTimeout(5_000) { player.playLine(TONE_2S) }
        val ended = SystemClock.elapsedRealtime()
        collector.cancel()
        assertEquals(null, player.linePosition.value)

        val moving = synchronized(samples) { samples.filter { it.second.positionMs > 0 } }
        val (t0, p0) = moving.first()
        val (t1, p1) = moving.last()
        val rate = (p1.positionMs - p0.positionMs).toDouble() / (t1 - t0)
        Log.i(TAG, "표본 ${moving.size}개, 재생 속도 ${"%.3f".format(rate)}, 끝 위치 ${p1.positionMs}, 끝난 뒤 ${ended - t1}ms")

        val duration = p1.durationMs!!
        assertTrue("길이 ${duration}ms", abs(duration - 2_000) <= 20)
        // 약 50ms마다 갱신 (ExoPlayer dynamic scheduling을 끄지 않으면 약 250ms)
        val maxGap = moving.zipWithNext { a, b -> b.first - a.first }.max()
        assertTrue("갱신 간격 최대 ${maxGap}ms", maxGap <= 120)
        assertTrue("위치가 뒤로 감", moving.zipWithNext { a, b -> b.second.positionMs >= a.second.positionMs }.all { it })
        // 위치는 실제로 나간 소리를 따른다. 에뮬레이터 오디오는 벽시계보다 약 7% 느려서 0.93 안팎이 나온다
        assertTrue("재생 속도 $rate", rate in 0.85..1.1)
        // 끝까지 위치가 갱신되고, 음원이 끝나면 바로 돌아온다
        assertTrue("끝 위치 ${p1.positionMs}", p1.positionMs >= duration - 150)
        assertTrue("끝난 뒤 ${ended - t1}ms 늦게 돌아옴", ended - t1 <= 150)
    }

    @Test
    fun focus_loss_pauses_and_waits_for_resume() = runBlocking {
        assertTrue(player.start())
        val line = async { player.playLine(TONE_3S) }
        positionAtLeast(500)

        // 내비 안내처럼 잠깐 포커스를 가져간다
        val navigation = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(navigation))
        try {
            withTimeout(2_000) { player.focus.first { it == AudioFocus.LOST_TRANSIENT } }
            val pausedAt = stablePosition()
            delay(700)
            assertTrue("포커스를 잃은 뒤에도 재생됨", abs(player.linePosition.value!!.positionMs - pausedAt) <= 30)
        } finally {
            audioManager.abandonAudioFocusRequest(navigation)
        }

        // 포커스가 돌아와도 스스로 재생하지 않는다
        withTimeout(2_000) { player.focus.first { it == AudioFocus.HELD } }
        val heldAt = player.linePosition.value!!.positionMs
        delay(700)
        assertTrue("포커스가 돌아오자 저절로 재생됨", abs(player.linePosition.value!!.positionMs - heldAt) <= 30)
        assertFalse(line.isCompleted)

        // 부모가 다시 듣기를 누르면 이어서 끝까지
        assertTrue(player.resume())
        withTimeout(5_000) { line.await() }
    }

    @Test
    fun parent_pause_and_resume() = runBlocking {
        assertTrue(player.start())
        val line = async { player.playLine(TONE_2S) }
        positionAtLeast(300)
        player.pause()
        val pausedAt = stablePosition()
        delay(700)
        assertTrue(abs(player.linePosition.value!!.positionMs - pausedAt) <= 30)
        assertTrue(player.resume())
        withTimeout(5_000) { line.await() }
    }

    @Test
    fun missing_file_fails_line() = runBlocking {
        assertTrue(player.start())
        try {
            withTimeout(5_000) { player.playLine(AudioClip("audio-test", "nope.mp3")) }
            fail("없는 파일인데 재생이 끝남")
        } catch (expected: AudioPlaybackException) {
        }
    }

    @Test
    fun cancelling_line_stops_it() = runBlocking<Unit> {
        assertTrue(player.start())
        val line = launch { player.playLine(TONE_3S) }
        positionAtLeast(300)
        line.cancel()
        withTimeout(1_000) { player.linePosition.first { it == null } }
    }

    private suspend fun positionAtLeast(ms: Long) {
        withTimeout(3_000) { player.linePosition.filterNotNull().first { it.positionMs >= ms } }
    }

    /** 멈춘 뒤 마지막 갱신이 들어올 때까지 잠깐 기다린 위치 */
    private suspend fun stablePosition(): Long {
        delay(200)
        return player.linePosition.value!!.positionMs
    }

    private companion object {
        const val TAG = "BTAudioTest"
        val TONE_2S = AudioClip("audio-test", "tone_2s.wav")
        val TONE_3S = AudioClip("audio-test", "tone_3s.wav")
    }
}
