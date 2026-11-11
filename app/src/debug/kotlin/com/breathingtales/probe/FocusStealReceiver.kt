package com.breathingtales.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 다른 앱이 오디오 포커스를 잠깐 가져가는 상황을 만든다 (debug 전용, 11 통합 테스트).
 * 화면을 가리지 않고 재생 화면을 그대로 보면서 확인하려고 broadcast로 둔다.
 *
 * ```
 * adb shell am broadcast --user 10 -n com.breathingtales/.probe.FocusStealReceiver --es kind nav --ei ms 5000
 * ```
 * - `kind`: `nav` 내비 안내(GAIN_TRANSIENT_MAY_DUCK), `call` 통화(GAIN_TRANSIENT), `media` 다른 미디어 앱(GAIN, 계속 가져감)
 * - `ms`: 잡고 있을 시간. broadcast 제한(10초) 안에서 최대 8000
 *
 * 포커스만 잡고 소리는 내지 않는다. 결과는 logcat `BTProbe` `[Focus]`.
 */
class FocusStealReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: "nav"
        val holdMs = intent.getIntExtra("ms", 5_000).coerceIn(500, 8_000).toLong()
        val (usage, gain) = when (kind) {
            "call" -> AudioAttributes.USAGE_VOICE_COMMUNICATION to AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            "media" -> AudioAttributes.USAGE_MEDIA to AudioManager.AUDIOFOCUS_GAIN
            else -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE to AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            .setOnAudioFocusChangeListener { Log.i(TAG, "[Focus] $kind 포커스 변화 $it") }
            .build()
        val result = audioManager.requestAudioFocus(request)
        Log.i(TAG, "[Focus] $kind 요청 결과 $result (1=허용), ${holdMs}ms 잡음")
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            audioManager.abandonAudioFocusRequest(request)
            Log.i(TAG, "[Focus] $kind 놓음")
            pending.finish()
        }, holdMs)
    }

    private companion object {
        const val TAG = "BTProbe"
    }
}
