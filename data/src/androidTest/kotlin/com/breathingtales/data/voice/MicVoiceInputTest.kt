package com.breathingtales.data.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.port.VoiceSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 참여 듣기를 녹음 파일로 확인한다. 마이크 대신 파일을 실시간 속도로 넣고, 그 뒤(STT, 숨 불기 판정, 거르기)는 실제 코드다.
 * 음원은 `androidTest/assets/stories/voice-test/`: Windows 한국어 음성(Heami)으로 만든 말소리(-30 dBFS),
 * 합성한 숨소리(-45 dBFS), 바탕 잡음(-75 dBFS). 크기는 버즈로 잰 값에 맞췄다 (02 문서 8장).
 * 실행: `./gradlew :data:connectedDebugAndroidTest` (에뮬레이터 필요, 마이크·사람 손 필요 없음)
 */
@RunWith(AndroidJUnit4::class)
class MicVoiceInputTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wind = listOf("바람아 불어라", "바람아")
    // 아이가 "반짝!"만 외치거나 STT가 "해님"과 "반짝"을 나눠 들을 수 있어 짧은 키워드도 둔다 (바람 장면의 "바람아"와 같음)
    private val sun = listOf("해님 반짝", "반짝")

    @Test
    fun shout_keyword_is_heard_once() {
        assertEquals(listOf(1), heard("shout_wind", wind))
    }

    @Test
    fun child_like_voice_is_heard() {
        assertEquals(listOf(1), heard("shout_wind_child", wind))
    }

    @Test
    fun short_keyword_is_heard() {
        assertEquals(listOf(1), heard("shout_baram", wind))
    }

    @Test
    fun keywords_belong_to_the_scene() {
        assertEquals(listOf(1), heard("shout_sun", sun))
        assertEquals(0, listen("shout_sun", InteractionType.SHOUT, wind).size)
    }

    @Test
    fun unrelated_words_and_silence_are_not_success() {
        assertEquals(0, listen("unrelated", InteractionType.SHOUT, wind).size)
        assertEquals(0, listen("silence", InteractionType.SHOUT_OR_BLOW, wind).size)
    }

    @Test
    fun long_blow_counts_short_puff_does_not() {
        assertEquals(listOf(VoiceSignal.Blow), listen("blow_long", InteractionType.BLOW, emptyList()))
        assertEquals(emptyList<VoiceSignal>(), listen("blow_short", InteractionType.BLOW, emptyList()))
    }

    /** 외치는 소리도 크고 길어 불기로 잡히지만 한 번만 센다 */
    @Test
    fun shout_or_blow_counts_one_shout_once() {
        assertEquals(1, listen("shout_wind", InteractionType.SHOUT_OR_BLOW, wind).size)
    }

    @Test
    fun shout_then_blow_counts_twice() {
        assertEquals(2, listen("shout_then_blow", InteractionType.SHOUT_OR_BLOW, wind).size)
    }

    /** 정해 둔 동작: 외치기·불기 함께일 때는 키워드가 아니어도 크고 긴 소리는 불기로 센다 (아이가 소리를 내면 참여로 본다) */
    @Test
    fun shout_or_blow_counts_any_long_loud_sound() {
        assertEquals(listOf(VoiceSignal.Blow), listen("unrelated", InteractionType.SHOUT_OR_BLOW, wind))
    }

    /**
     * 키워드를 알아들어야 하는 경우. 에뮬레이터 STT는 같은 소리에도 결과가 매번 달라(엔진 오류, "바람만 불어라" 등)
     * 두 번까지 듣고 한 번이라도 알아들으면 통과로 본다. 실제로도 아이가 다시 외치고, 대체 버튼이 있다.
     * @return 알아들은 시도의 성공 횟수 (실패한 시도는 빼고). 한 번도 못 알아들으면 빈 목록
     */
    private fun heard(file: String, keywords: List<String>): List<Int> {
        repeat(ATTEMPTS) { attempt ->
            val count = listen(file, InteractionType.SHOUT, keywords).size
            android.util.Log.i("BTVoiceTest", "$file 시도 ${attempt + 1}: ${if (count > 0) "알아들음" else "못 알아들음"}")
            if (count > 0) return listOf(count)
        }
        return emptyList()
    }

    /** 파일 길이 + STT 최종 결과를 기다릴 3초 동안 듣는다 */
    private fun listen(file: String, type: InteractionType, keywords: List<String>): List<VoiceSignal> = runBlocking {
        val pcm = context.assets.open("stories/voice-test/$file.wav").use { it.readBytes() }.let { it.copyOfRange(WAV_HEADER, it.size) }
        val input = MicVoiceInput(FileAudioInput(pcm), PleosStt(context))
        val signals = mutableListOf<VoiceSignal>()
        val start = SystemClock.elapsedRealtime()
        withTimeoutOrNull(pcm.size / BYTES_PER_MS + 3_000L) {
            input.listen(type, keywords).collect { signals += it }
        }
        android.util.Log.i("BTVoiceTest", "$file $type → $signals (${SystemClock.elapsedRealtime() - start}ms)")
        assertTrue(signals.size <= 2)
        signals
    }

    /** 녹음 파일을 마이크처럼 64ms씩 실시간 속도로 낸다 */
    private class FileAudioInput(private val pcm: ByteArray) : AudioInput {
        override fun frames(): Flow<AudioFrame> = flow {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + FRAME_BYTES, pcm.size)
                emit(AudioFrame.of(SystemClock.elapsedRealtime(), pcm.copyOfRange(offset, end)))
                offset = end
                delay(64)
            }
        }
    }

    private companion object {
        const val ATTEMPTS = 2
        const val WAV_HEADER = 44
        const val FRAME_BYTES = 2_048
        const val BYTES_PER_MS = 32
    }
}
