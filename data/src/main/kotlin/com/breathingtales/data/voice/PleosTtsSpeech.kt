package com.breathingtales.data.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import com.breathingtales.domain.port.SpeechPort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [SpeechPort]를 Pleos TTS(ON_DEVICE)로 구현한다. 02 문서 5장 규칙:
 * - 리스너는 `initialize` 성공 콜백 안에서 붙인다 (먼저 붙이면 SDK 내부 오류로 앱이 죽는다)
 * - 언어는 `Locale.KOREA`. `Locale.KOREAN`은 `unsupported locale: ko-`
 * - 목소리는 서비스에 앱별로 저장되어 남으므로 말할 때마다 [VOICE]로 지정한다
 *
 * 한 번에 한 문장만 읽는다. 처음 말할 때 초기화하고 앱이 살아 있는 동안 계속 쓴다.
 */
@Singleton
class PleosTtsSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechPort {

    private val speaking = Mutex()
    private var engine: TextToSpeech? = null

    // SDK 콜백 스레드가 정해져 있지 않으므로 잠가서 쓴다
    private val lock = Any()
    private var waiter: CancellableContinuation<Unit>? = null

    private val listener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onDone() = finish(null)
        override fun onStop() = Unit // 우리가 stop()을 부른 경우. 기다리던 쪽은 이미 취소됨
        override fun onError(errorType: ErrorType) = finish(SpeechException("TTS 실패: $errorType"))
        override fun onUpdatedRms(rms: Double) = Unit
    }

    override suspend fun speak(text: String) = speaking.withLock {
        val tts = withTimeout(INIT_TIMEOUT) { ready() }
        try {
            withTimeout(timeoutFor(text)) {
                suspendCancellableCoroutine<Unit> { cont ->
                    synchronized(lock) { waiter = cont }
                    cont.invokeOnCancellation {
                        synchronized(lock) { if (waiter === cont) waiter = null }
                        runCatching { tts.stop() }
                    }
                    try {
                        tts.setSpeakerType(VOICE)
                        tts.speak(text)
                    } catch (e: Exception) {
                        finish(SpeechException("TTS speak 예외: ${e.message}", e))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // 시간 초과는 취소가 아니라 실패로 알린다. 엔진은 그 문장만 건너뛴다
            throw SpeechException("TTS 응답 없음 (${text.length}자)", e)
        }
    }

    private fun finish(error: SpeechException?) {
        val cont = synchronized(lock) { waiter.also { waiter = null } } ?: return
        if (error == null) {
            cont.resume(Unit)
        } else {
            Log.w(TAG, error.message.orEmpty())
            cont.resumeWithException(error)
        }
    }

    private suspend fun ready(): TextToSpeech = engine ?: withContext(Dispatchers.Main) {
        engine ?: suspendCancellableCoroutine { cont ->
            val tts = TextToSpeech(context, Mode.ON_DEVICE)
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    tts.addEventListener(listener)
                    tts.setLocale(Locale.KOREA)
                    engine = tts
                    if (cont.isActive) cont.resume(tts)
                }

                override fun onFailure(errorType: ErrorType) {
                    runCatching { tts.release() }
                    if (cont.isActive) cont.resumeWithException(SpeechException("TTS 초기화 실패: $errorType"))
                }
            })
        }
    }

    private companion object {
        const val TAG = "BTVoice"

        /** 02 문서에서 동작을 확인한 목소리 */
        const val VOICE = "seoyoon"
        val INIT_TIMEOUT = 5.seconds

        /** 합성 시작 약 0.6초, 30자에 약 4.5초 (02 문서). 넉넉히 잡는다 */
        fun timeoutFor(text: String): Duration = 5.seconds + (text.length * 300).milliseconds
    }
}

class SpeechException(message: String, cause: Throwable? = null) : Exception(message, cause)
