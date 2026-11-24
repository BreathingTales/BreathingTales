package com.breathingtales.data.settings

import android.content.Context
import android.util.Log
import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.model.ZoneId
import com.breathingtales.domain.port.RestoreJournalPort
import com.breathingtales.domain.port.RestoreRecord
import com.breathingtales.domain.port.SeatAxis
import com.breathingtales.domain.port.SeatInMotion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 되돌릴 실내 상태를 SharedPreferences에 JSON 한 줄로 둔다.
 * 다음 차량 명령 전에 프로세스가 죽어도 남도록 `apply`가 아니라 `commit`으로 바로 쓴다 (연출마다 몇 번뿐이다).
 */
@Singleton
class PrefsRestoreJournal @Inject constructor(
    @ApplicationContext context: Context,
) : RestoreJournalPort {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun load(): RestoreRecord? = withContext(Dispatchers.IO) {
        val text = prefs.getString(KEY, null) ?: return@withContext null
        runCatching { json.decodeFromString<RecordDto>(text).toDomain() }
            .onFailure { Log.w(TAG, "복원 기록을 읽지 못함, 버림: ${it.message}") }
            .getOrNull()
    }

    override suspend fun save(record: RestoreRecord) = withContext(Dispatchers.IO) {
        commit(json.encodeToString(RecordDto.serializer(), RecordDto.from(record)))
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { commit(null) }

    private fun commit(text: String?) {
        if (!prefs.edit().putString(KEY, text).commit()) error("복원 기록 쓰기 실패")
    }

    @Serializable
    private data class RecordDto(
        val hvacPowerOn: Boolean? = null,
        val fanSpeed: Int? = null,
        val airflow: String? = null,
        val temperaturesC: Map<Int, Float> = emptyMap(),
        val ambientMode: String? = null,
        val ambientColorRgb: Int? = null,
        val ambientBrightness: Int? = null,
        val ambientAutoByDriveMode: Boolean? = null,
        val seatHeatLevels: Map<Int, Int> = emptyMap(),
        val cabinLight: String? = null,
        val readingLights: Map<Int, String> = emptyMap(),
        val seatZone: Int? = null,
        val seatStart: Map<String, Int> = emptyMap(),
    ) {
        fun toDomain() = RestoreRecord(
            cabin = CabinSnapshot(
                hvacPowerOn = hvacPowerOn,
                fanSpeed = fanSpeed,
                airflow = airflow?.let { Airflow.valueOf(it) },
                temperaturesC = temperaturesC.mapKeys { ZoneId(it.key) },
                ambientMode = ambientMode?.let { AmbientMode.valueOf(it) },
                ambientColorRgb = ambientColorRgb,
                ambientBrightness = ambientBrightness,
                ambientAutoByDriveMode = ambientAutoByDriveMode,
                seatHeatLevels = seatHeatLevels.mapKeys { ZoneId(it.key) },
                cabinLight = cabinLight?.let { LampSwitch.valueOf(it) },
                readingLights = readingLights.entries.associate { ZoneId(it.key) to LampSwitch.valueOf(it.value) },
            ),
            seatMoving = seatZone?.let { zone ->
                SeatInMotion(ZoneId(zone), seatStart.mapKeys { SeatAxis.valueOf(it.key) })
            },
        )

        companion object {
            fun from(r: RestoreRecord) = with(r.cabin) {
                RecordDto(
                    hvacPowerOn = hvacPowerOn,
                    fanSpeed = fanSpeed,
                    airflow = airflow?.name,
                    temperaturesC = temperaturesC.mapKeys { it.key.raw },
                    ambientMode = ambientMode?.name,
                    ambientColorRgb = ambientColorRgb,
                    ambientBrightness = ambientBrightness,
                    ambientAutoByDriveMode = ambientAutoByDriveMode,
                    seatHeatLevels = seatHeatLevels.mapKeys { it.key.raw },
                    cabinLight = cabinLight?.name,
                    readingLights = readingLights.entries.associate { it.key.raw to it.value.name },
                    seatZone = r.seatMoving?.zone?.raw,
                    seatStart = r.seatMoving?.start?.mapKeys { it.key.name }.orEmpty(),
                )
            }
        }
    }

    private companion object {
        const val TAG = "BTRestore"
        const val FILE = "restore_journal"
        const val KEY = "record"
        val json = Json { ignoreUnknownKeys = true }
    }
}
