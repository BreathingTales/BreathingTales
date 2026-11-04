package com.breathingtales.data.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 앱이 직접 여는 마이크 (64ms 단위). 숨 불기 판정은 음량을, 외치기는 같은 소리를 STT `sendAudio`로 넘겨 쓴다.
 * Pleos STT가 마이크를 직접 열면 에뮬레이터에서 무음만 받으므로(08 문서) STT에도 이 소리를 넘긴다.
 * 마이크 권한(`RECORD_AUDIO`)은 09 화면이 받는다.
 */
@Singleton
class MicCapture @Inject constructor() : AudioInput {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun frames(): Flow<AudioFrame> = flow {
        val rate = AudioFrame.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * 2) * 2,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 안 됨 (마이크 권한·장치 확인)" }
        try {
            recorder.startRecording()
            val samples = ShortArray(FRAME_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(samples, 0, samples.size)
                check(read >= 0) { "AudioRecord read 오류 $read" }
                if (read == 0) continue
                val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) bytes.putShort(samples[i])
                emit(AudioFrame.of(SystemClock.elapsedRealtime(), bytes.array()))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** 64ms */
        const val FRAME_SAMPLES = 1_024
    }
}
