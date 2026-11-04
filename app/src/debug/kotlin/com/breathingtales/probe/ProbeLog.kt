package com.breathingtales.probe

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 확인 결과를 화면과 logcat(태그 `BTProbe`)에 함께 남긴다. SDK 콜백은 바인더 스레드에서 오므로 StateFlow로 모은다. */
class ProbeLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(section: String, message: String) {
        val line = "${LocalTime.now().format(TIME)} [$section] $message"
        Log.i(TAG, line)
        _lines.update { (listOf(line) + it).take(MAX_LINES) }
    }

    fun clear() = _lines.update { emptyList() }

    private companion object {
        const val TAG = "BTProbe"
        const val MAX_LINES = 300
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
