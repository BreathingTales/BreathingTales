package com.breathingtales.data.vehicle

import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 시연 모드 전용 (11, debug 빌드에서만 켜짐): Pleos 에뮬레이터는 SDK로 시트 높이를 늘 0으로, 안전벨트를 늘 false로 읽는다
 * (02 문서 4장). 에뮬레이터 안에서 닿는 Virtual Console 서버(`127.0.0.1:8088/vehicle/state`)의 실제 값으로 대신 읽는다.
 *
 * [enabled]가 false면 아무것도 읽지 않는다. 릴리스 빌드에는 INTERNET 권한이 없어 켜져 있어도 읽기가 실패한다.
 * 실차에서는 쓰지 않는다: SDK 값이 그대로 쓰이고, 이 클래스는 SDK가 0·false를 돌려줄 때만 끼어든다.
 */
class EmulatorSeatState(val enabled: () -> Boolean) {

    /** 시트 높이 위치 0~100. 읽지 못하면 null */
    suspend fun height(seat: String): Int? = read { it.getJSONObject("seat").getJSONObject("positions").getJSONObject("height").getInt(seat) }

    /** 착석 여부. 에뮬레이터에는 안전벨트 상태가 없어 착석만 본다. 읽지 못하면 null */
    suspend fun occupied(seat: String): Boolean? = read { it.getJSONObject("seat").getJSONObject("occupied").getBoolean(seat) }

    /**
     * Virtual Console의 AVD가 켜져 있는지 (`availability.available`). 꺼져 있으면 차량 명령이 오류 없이 무시된다.
     * 서버에 닿지 않으면 null (adb forward가 없거나 Virtual Console이 닫힘)
     */
    suspend fun avdAvailable(): Boolean? = read { it.getJSONObject("availability").getBoolean("available") }

    private suspend fun <T> read(pick: (JSONObject) -> T): T? {
        if (!enabled()) return null
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(STATE_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                try {
                    pick(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Virtual Console 시트 상태 읽기 실패: ${e.message}")
                null
            }
        }
    }

    companion object {
        private const val STATE_URL = "http://127.0.0.1:8088/vehicle/state"

        /** Android 에뮬레이터(Pleos 에뮬레이터 포함)인지. 시트 높이 SDK 값을 믿을 수 있는지 가르는 데 쓴다 */
        val isEmulator: Boolean = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
        private const val TIMEOUT_MS = 1_000

        /** SDK 좌석 이름 → Virtual Console 키 */
        fun seatKey(areaNames: List<String>): String? = when (areaNames) {
            listOf("ROW_1_LEFT") -> "driver"
            listOf("ROW_1_RIGHT") -> "passenger"
            listOf("ROW_2_LEFT") -> "rear_left"
            listOf("ROW_2_RIGHT") -> "rear_right"
            else -> null
        }
    }
}
