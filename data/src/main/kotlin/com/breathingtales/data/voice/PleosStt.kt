package com.breathingtales.data.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * Pleos STT(ON_DEVICE). 규칙은 TTS와 같다: 리스너는 `initialize` 성공 뒤, 언어는 `Locale.KOREA` (02 문서 6장).
 *
 * **소리는 앱이 녹음해 `sendAudio`로 넘긴다.** STT 서비스가 마이크를 직접 열면 에뮬레이터에서 무음만 받아
 * 인식 결과가 나오지 않았다 (08 문서). 앱 마이크 하나를 숨 불기 감지와 함께 쓸 수 있는 장점도 있다.
 * STT는 `request()` 한 번에 한 발화만 인식하므로, 발화가 끝날 때마다 다시 요청해 계속 듣는다.
 */
@Singleton
class PleosStt @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event

        /** 한 발화가 끝남. 다음 발화는 새 인식이다 */
        data object Ended : Event
        data class Error(val type: String) : Event
    }

    private var engine: SpeechToText? = null

    /**
     * [audio](16kHz 모노 16비트 PCM)를 넘기며 인식한다. 수집을 멈추면 `stop()`.
     *
     * 엔진 오류 복구: 서비스는 `request()` 때 자기 마이크도 열어 엔진 입력에 우리 소리와 함께 넣는다(초당 두 배).
     * 말소리가 시작돼 엔진이 바쁠 때 입력이 넘쳐 `ON_DEVICE_ENGINE_ERROR`("not enough space")로 끝나는 일이 있다.
     * 그래서 최근 [HISTORY_CHUNKS]만큼 소리를 보관하고, 오류가 나면 바로 다시 요청해 보관한 소리부터 넘긴다.
     *
     * 발화 사이: 한 발화가 끝나고 다시 준비될 때까지 들어온 소리도 모아 두었다 넘긴다.
     * "해님... 반짝!"처럼 끊어 말하면 "반짝"이 이 틈에 들어와 잘리던 문제.
     */
    fun recognize(audio: Flow<ByteArray>): Flow<Event> = callbackFlow {
        val stt = withTimeout(5.seconds) { ready() }
        val restarts = Channel<Long>(Channel.CONFLATED)
        val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        val queued = AtomicInteger(0)
        val lock = Any()
        val history = ArrayDeque<ByteArray>()
        // 다시 요청해 준비될 때까지(발화 사이, 오류 뒤) 들어온 소리. 준비되면 먼저 넘긴다
        val missed = ArrayDeque<ByteArray>()
        // 인식 준비(onReady) 전이나 끝난 뒤에 넘긴 소리도 엔진 오류를 내므로 준비된 동안만 넘긴다
        var accepting = false
        var replayOnReady = false
        var errorsInRow = 0

        val listener = object : ResultListener {
            override fun onUpdated(stt: String, completed: Boolean) {
                errorsInRow = 0
                trySend(if (completed) Event.Final(stt) else Event.Partial(stt))
            }

            override fun onEndedRecognition() {
                synchronized(lock) { accepting = false }
                trySend(Event.Ended)
                restarts.trySend(RESTART_DELAY_MS)
            }

            override fun onError(errorType: ErrorType) {
                synchronized(lock) {
                    accepting = false
                    replayOnReady = true
                }
                errorsInRow++
                Log.w(TAG, "STT 오류: $errorType (연속 $errorsInRow)")
                trySend(Event.Error(errorType.name))
                // 엔진 오류는 바로 다시 시작한다. 계속 나면 너무 자주 부르지 않는다
                restarts.trySend(if (errorsInRow <= MAX_QUICK_RETRIES) RESTART_DELAY_MS else ERROR_RETRY_DELAY_MS)
            }

            override fun onReady() {
                synchronized(lock) {
                    // 오류 뒤에는 엔진이 잃었을 수 있는 최근 소리 전부, 발화 사이에는 놓친 소리만
                    val backlog = if (replayOnReady) history else missed
                    replayOnReady = false
                    backlog.forEach { if (outbox.trySend(it).isSuccess) queued.incrementAndGet() }
                    missed.clear()
                    accepting = true
                }
            }

            override fun onStartedRecognition() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        }
        stt.addListener(listener)
        launch {
            for (wait in restarts) {
                delay(wait)
                runCatching { stt.request() }.onFailure { Log.w(TAG, "STT request 예외: ${it.message}") }
            }
        }
        restarts.trySend(0)

        // 보내기: 밀린 소리(오류 뒤 다시 넘기는 것)는 실시간보다 조금 빠르게 넘겨 따라잡는다
        launch {
            for (chunk in outbox) {
                val backlog = queued.decrementAndGet() > 0
                if (synchronized(lock) { accepting }) runCatching { stt.sendAudio(chunk) }
                if (backlog) delay(CATCH_UP_INTERVAL_MS)
            }
        }

        // 엔진은 100ms(3200바이트) 단위로 읽는다. 다른 크기로 넘기면 엔진 오류로 멈춘다
        launch {
            val pending = ByteArrayOutputStream()
            audio.collect { pcm ->
                pending.write(pcm)
                if (pending.size() < CHUNK_BYTES) return@collect
                val bytes = pending.toByteArray()
                pending.reset()
                var offset = 0
                while (bytes.size - offset >= CHUNK_BYTES) {
                    val chunk = bytes.copyOfRange(offset, offset + CHUNK_BYTES)
                    offset += CHUNK_BYTES
                    synchronized(lock) {
                        history.addLast(chunk)
                        if (history.size > HISTORY_CHUNKS) history.removeFirst()
                        if (accepting) {
                            if (outbox.trySend(chunk).isSuccess) queued.incrementAndGet()
                        } else {
                            missed.addLast(chunk)
                            if (missed.size > HISTORY_CHUNKS) missed.removeFirst()
                        }
                    }
                }
                pending.write(bytes, offset, bytes.size - offset)
            }
        }
        awaitClose {
            outbox.close()
            runCatching { stt.stop() }
            runCatching { stt.removeListener(listener) }
        }
    }

    private suspend fun ready(): SpeechToText = engine ?: withContext(Dispatchers.Main) {
        engine ?: suspendCancellableCoroutine { cont ->
            val stt = SpeechToText(context, Mode.ON_DEVICE)
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    stt.setLocale(Locale.KOREA)
                    engine = stt
                    if (cont.isActive) cont.resume(stt)
                }

                override fun onFailure(errorType: ErrorType) {
                    runCatching { stt.release() }
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("STT 초기화 실패: $errorType"))
                }
            })
        }
    }

    private companion object {
        const val TAG = "BTVoice"
        const val RESTART_DELAY_MS = 100L
        const val ERROR_RETRY_DELAY_MS = 1_000L
        const val MAX_QUICK_RETRIES = 3

        /** 오류 뒤 다시 넘길 최근 소리: 2초 */
        const val HISTORY_CHUNKS = 20

        /** 밀린 소리를 넘기는 간격. 100ms 소리를 80ms마다 = 1.25배 빠르게 */
        const val CATCH_UP_INTERVAL_MS = 80L

        /** 100ms (16kHz 16비트 모노) */
        const val CHUNK_BYTES = 3_200
    }
}
