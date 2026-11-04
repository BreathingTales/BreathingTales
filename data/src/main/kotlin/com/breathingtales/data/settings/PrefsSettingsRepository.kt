package com.breathingtales.data.settings

import android.content.Context
import com.breathingtales.domain.model.BlowSensitivity
import com.breathingtales.domain.model.ParentSettings
import com.breathingtales.domain.repository.SettingsRepository
import com.breathingtales.domain.safety.Intensity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 부모 설정을 SharedPreferences에 둔다. 값이 몇 개뿐이라 DataStore는 쓰지 않는다 */
@Singleton
class PrefsSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    override val settings: StateFlow<ParentSettings> = _settings.asStateFlow()

    @Synchronized
    override fun update(transform: (ParentSettings) -> ParentSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_CHILD_NAME, next.childName)
            .putString(KEY_INTENSITY, next.intensity.name)
            .putString(KEY_BLOW, next.blowSensitivity.name)
            .putBoolean(KEY_SEAT_MOTION, next.seatMotion)
            .putBoolean(KEY_DEMO_HUD, next.demoHud)
            .apply()
        _settings.value = next
    }

    private fun read(): ParentSettings {
        val default = ParentSettings()
        return ParentSettings(
            childName = prefs.getString(KEY_CHILD_NAME, null) ?: default.childName,
            intensity = enumOrNull<Intensity>(prefs.getString(KEY_INTENSITY, null)) ?: default.intensity,
            blowSensitivity = enumOrNull<BlowSensitivity>(prefs.getString(KEY_BLOW, null)) ?: default.blowSensitivity,
            seatMotion = prefs.getBoolean(KEY_SEAT_MOTION, default.seatMotion),
            demoHud = prefs.getBoolean(KEY_DEMO_HUD, default.demoHud),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private companion object {
        const val FILE = "parent_settings"
        const val KEY_CHILD_NAME = "childName"
        const val KEY_INTENSITY = "intensity"
        const val KEY_BLOW = "blowSensitivity"
        const val KEY_SEAT_MOTION = "seatMotion"
        const val KEY_DEMO_HUD = "demoHud"
    }
}
