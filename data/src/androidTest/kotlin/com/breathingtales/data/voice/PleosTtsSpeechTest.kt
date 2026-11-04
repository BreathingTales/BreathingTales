package com.breathingtales.data.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breathingtales.data.audio.Media3AudioPlayer
import com.breathingtales.domain.model.AudioClip
import com.breathingtales.domain.port.AudioFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pleos TTS를 에뮬레이터에서 확인한다. 실행: `./gradlew :data:connectedDebugAndroidTest` */
@RunWith(AndroidJUnit4::class)
class PleosTtsSpeechTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tts = PleosTtsSpeech(context)

    @Test
    fun speak_returns_after_speaking() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        tts.speak("하늘아, 바람을 불어 볼까?")
        val took = SystemClock.elapsedRealtime() - start
        // 합성 시작 약 0.6초 + 15자 약 1.5초 (02 문서: 30자 약 4.5초)
        assertTrue("읽는 데 ${took}ms", took in 1_000..6_000)
    }

    /** 06에서 넘긴 확인 항목: TTS가 우리 오디오 포커스를 가져가면 엔진이 내비 안내로 오해해 멈춘다 */
    @Test
    fun speaking_does_not_take_our_audio_focus() = runBlocking {
        val player = Media3AudioPlayer(context)
        assertTrue(player.start())
        val seen = mutableListOf<AudioFocus>()
        val watcher = launch(Dispatchers.Default) { player.focus.collect { synchronized(seen) { seen += it } } }
        val line = launch { player.playLine(AudioClip("audio-test", "tone_3s.wav")) }
        try {
            delay(300)
            tts.speak("하늘아, 바람을 불어 볼까?")
            delay(500)
            assertEquals(listOf(AudioFocus.HELD), synchronized(seen) { seen.distinct() })
        } finally {
            line.cancel()
            watcher.cancel()
            player.stop()
        }
    }

    @Test
    fun cancelling_stops_speaking() = runBlocking {
        val speaking = launch { tts.speak("아주 긴 문장을 읽다가 중간에 멈춥니다. 하나 둘 셋 넷 다섯 여섯 일곱 여덟 아홉 열") }
        delay(1_500)
        speaking.cancel()
        // 끊은 뒤 바로 다음 문장을 읽을 수 있어야 한다
        withTimeout(8_000) { tts.speak("다음 문장") }
    }
}
