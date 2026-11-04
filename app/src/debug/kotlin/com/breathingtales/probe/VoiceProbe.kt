package com.breathingtales.probe

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos TTS·STT 3.3.1과 마이크(숨 불기) 확인.
 * 서버 모드(SERVER, HYBRID의 서버 경로)는 My Project의 Client ID/Secret으로 registerApp이 필요하다.
 * 여기서는 등록 없이 동작하는 ON_DEVICE와, 등록 없이 HYBRID가 어떻게 동작하는지를 확인한다.
 */
class VoiceProbe(private val context: Context, private val log: ProbeLog, private val scope: CoroutineScope) {
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var ttsReady = false
    private var sttReady = false

    // 초기화 전에 누른 동작은 초기화 성공 후 이어서 실행한다
    private var afterTtsInit: (() -> Unit)? = null
    private var afterSttInit: (() -> Unit)? = null

    private val _micLevelDb = MutableStateFlow(-90f)
    val micLevelDb: StateFlow<Float> = _micLevelDb
    private var micJob: Job? = null

    // region TTS

    fun initTts(mode: TtsMode) {
        tts?.release()
        ttsReady = false
        log.add(TTS, "초기화 요청 ($mode)")
        val engine = TextToSpeech(context.applicationContext, mode)
        tts = engine
        // 주의: addEventListener는 initialize 성공 뒤에 불러야 한다. 먼저 부르면 SDK 내부
        // lateinit 필드가 비어 있어 UninitializedPropertyAccessException으로 앱이 죽는다(2026-10-29 확인).
        val listener = object : EventListener {
            override fun onStart() = log.add(TTS, "onStart")
            override fun onReady() = log.add(TTS, "onReady")
            override fun onDone() = log.add(TTS, "onDone")
            override fun onStop() = log.add(TTS, "onStop")
            override fun onError(errorType: TtsErrorType) = log.add(TTS, "❌ onError: $errorType")
            override fun onUpdatedRms(rms: Double) = Unit
        }
        sdkCall(TTS, "initialize") { engine.initialize(object : TtsInitListener {
            override fun onSuccess() {
                log.add(TTS, "초기화 성공 ($mode)")
                sdkCall(TTS, "addEventListener") { engine.addEventListener(listener) }
                sdkCall(TTS, "setLocale") { engine.setLocale(Locale.KOREA) } // "ko"만 주면 엔진이 "ko-"로 받아 unsupported locale
                ttsReady = true
                afterTtsInit?.invoke()
                afterTtsInit = null
            }

            override fun onFailure(errorType: TtsErrorType) = log.add(TTS, "❌ 초기화 실패 ($mode): $errorType")
        }) }
    }

    /** speakerType은 서비스에 앱별로 저장되어 남으므로, 말할 때마다 지정한 목소리를 먼저 적용한다. */
    fun speak(text: String, speakerType: String = "") {
        val engine = tts
        if (engine == null || !ttsReady) {
            afterTtsInit = { speak(text, speakerType) }
            if (engine == null) initTts(TtsMode.ON_DEVICE) else log.add(TTS, "초기화 완료를 기다리는 중")
            return
        }
        if (speakerType.isNotBlank()) sdkCall(TTS, "setSpeakerType") { engine.setSpeakerType(speakerType) }
        log.add(TTS, "speak(speaker=${speakerType.ifBlank { "저장된 값" }}): $text")
        sdkCall(TTS, "speak") { engine.speak(text) }
    }

    /** 문서에 허용 값이 없어 직접 넣어 보며 반응을 확인한다. */
    fun setSpeakerType(type: String) {
        val engine = tts ?: return log.add(TTS, "먼저 TTS를 초기화하세요")
        runCatching { engine.setSpeakerType(type) }
            .onSuccess { log.add(TTS, "setSpeakerType(\"$type\") 호출됨 → 다음 speak로 목소리 변화 확인") }
            .onFailure { log.add(TTS, "❌ setSpeakerType: ${it.message}") }
    }

    fun stopTts() = tts?.stop()

    // endregion

    // region STT

    fun initStt(mode: SttMode) {
        stt?.release()
        sttReady = false
        log.add(STT, "초기화 요청 ($mode)")
        val engine = SpeechToText(context.applicationContext, mode)
        stt = engine
        // TTS와 같은 이유로 리스너는 initialize 성공 뒤에 붙인다.
        val listener = object : ResultListener {
            override fun onUpdated(stt: String, completed: Boolean) {
                val hit = KEYWORDS.filter { stt.replace(" ", "").contains(it) }
                log.add(STT, "${if (completed) "최종" else "중간"}: \"$stt\"${if (hit.isNotEmpty()) " ✅ 키워드 $hit" else ""}")
            }

            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onStartedRecognition() = log.add(STT, "인식 시작")
            override fun onEndedRecognition() = log.add(STT, "인식 종료")
            override fun onError(errorType: SttErrorType) = log.add(STT, "❌ onError: $errorType")
            override fun onReady() = log.add(STT, "onReady (말하세요)")
            override fun onUpdatedRms(rms: Double) = Unit
        }
        sdkCall(STT, "initialize") { engine.initialize(object : SttInitListener {
            override fun onSuccess() {
                log.add(STT, "초기화 성공 ($mode)")
                sdkCall(STT, "addListener") { engine.addListener(listener) }
                sdkCall(STT, "setLocale") { engine.setLocale(Locale.KOREA) } // "ko"만 주면 엔진이 "ko-"로 받아 unsupported locale
                sttReady = true
                afterSttInit?.invoke()
                afterSttInit = null
            }

            override fun onFailure(errorType: SttErrorType) = log.add(STT, "❌ 초기화 실패 ($mode): $errorType")
        }) }
    }

    fun startStt() {
        val engine = stt
        if (engine == null || !sttReady) {
            afterSttInit = { startStt() }
            if (engine == null) initStt(SttMode.ON_DEVICE) else log.add(STT, "초기화 완료를 기다리는 중")
            return
        }
        stopMic() // 마이크를 STT와 동시에 쓰지 않는다
        log.add(STT, "request() — 지금 말하세요")
        sdkCall(STT, "request") { engine.request() }
    }

    fun stopStt() = stt?.stop()

    // endregion

    // region 숨 불기 (AudioRecord 음량)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startMic() {
        if (micJob?.isActive == true) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        }.getOrElse { return log.add(MIC, "❌ AudioRecord 생성 실패: ${it.message}") }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return log.add(MIC, "❌ AudioRecord 초기화 안 됨 (에뮬레이터 마이크 설정 확인)")
        }
        log.add(MIC, "마이크 시작 (${SAMPLE_RATE}Hz, 버퍼 $minBuffer)")
        micJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(minBuffer / 2)
            recorder.startRecording()
            log.add(MIC, "녹음 상태: ${if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) "녹음 중" else "녹음 안 됨"}")
            // 1초마다 평균·최대 음량을 남긴다. 계속 -90(완전 무음)이면 에뮬레이터 마이크 입력이 꺼져 있는 것이다.
            var windowMax = -90f
            var windowSum = 0f
            var windowCount = 0
            var silentSeconds = 0
            var windowStart = System.currentTimeMillis()
            try {
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        log.add(MIC, "❌ read 오류 코드 $read")
                        break
                    }
                    if (read == 0) continue
                    var sum = 0.0
                    for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i]
                    val rms = sqrt(sum / read) / Short.MAX_VALUE
                    val db = if (rms > 0) (20 * log10(rms)).toFloat() else -90f
                    _micLevelDb.value = db
                    windowMax = maxOf(windowMax, db)
                    windowSum += db
                    windowCount++
                    if (System.currentTimeMillis() - windowStart >= 1_000) {
                        log.add(MIC, "1초 평균 %.1f / 최대 %.1f dBFS".format(windowSum / windowCount, windowMax))
                        silentSeconds = if (windowMax <= -89f) silentSeconds + 1 else 0
                        if (silentSeconds == 3) log.add(MIC, "⚠️ 3초째 완전 무음 → 에뮬레이터 마이크 입력(호스트 오디오) 설정을 켜 주세요")
                        windowMax = -90f
                        windowSum = 0f
                        windowCount = 0
                        windowStart = System.currentTimeMillis()
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                _micLevelDb.value = -90f
            }
        }
    }

    fun stopMic() {
        micJob?.cancel()
        micJob = null
    }

    // endregion

    /** 확인용 화면이 SDK 예외로 죽지 않도록 감싸고, 예외는 로그로 남긴다. */
    private inline fun sdkCall(section: String, what: String, block: () -> Unit) {
        runCatching(block).onFailure { log.add(section, "❌ $what 예외: ${it.javaClass.simpleName} ${it.message}") }
    }

    fun release() {
        stopMic()
        tts?.release()
        stt?.release()
    }

    private companion object {
        const val TTS = "TTS"
        const val STT = "STT"
        const val MIC = "Mic"
        const val SAMPLE_RATE = 16_000
        val KEYWORDS = listOf("바람아", "불어라", "해님", "반짝")
    }
}
