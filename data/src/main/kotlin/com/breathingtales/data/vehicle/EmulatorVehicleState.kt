package com.breathingtales.data.vehicle

import android.os.Build
import android.util.Log
import com.breathingtales.domain.port.SeatAxis
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Pleos 에뮬레이터 전용: 에뮬레이터는 SDK로 시트 높이·기울기를 늘 0으로, 안전벨트를 늘 false로, 착석을 반대로 읽는다
 * (2026-11-11·13 확인). 설정 온도도 에뮬레이터를 켠 뒤 한 번도 쓰지 않았으면 읽지 못한다(`PropertyNotAvailableException`, 2026-11-13).
 * 에뮬레이터 안에서 닿는 Virtual Console 서버(`127.0.0.1:8088/vehicle/state`)의 실제 값으로 대신 읽는다.
 *
 * [enabled]가 false면 아무것도 읽지 않는다. `VehicleModule`이 [isEmulator]일 때만 켠다 (debug·release 모두).
 * 심사가 Pleos 에뮬레이터에서 release APK로 이뤄지므로 release에서도 시트 들썩임을 확인할 수 있게 하기 위해서다.
 * 실차에서는 쓰지 않는다: [isEmulator]가 false라 SDK 값만 쓰이고, 에뮬레이터에서도 SDK가 0·false를 돌려주거나 읽기에 실패할 때만 끼어든다.
 */
class EmulatorVehicleState(val enabled: () -> Boolean) {

    /** 시트 위치 0~100 (높이는 `height`, 방석 기울기는 `tilt`. 등받이는 `recline`으로 따로 있다). 읽지 못하면 null */
    suspend fun position(seat: String, axis: SeatAxis): Int? {
        val key = when (axis) {
            SeatAxis.HEIGHT -> "height"
            SeatAxis.TILT -> "tilt"
        }
        return read { it.getJSONObject("seat").getJSONObject("positions").getJSONObject(key).getInt(seat) }
    }

    /** 영역(`driver`·`passenger`)의 설정 온도(℃). 읽지 못하면 null */
    suspend fun temperatureC(seat: String): Float? =
        read { it.getJSONObject("hvac").getJSONObject(seat).getDouble("temp_c").toFloat() }

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
