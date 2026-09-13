package io.github.pocketfly.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "pocketfly_settings")

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromKey(key: String): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Power/performance posture. Frame rate caps keep the phone cool during long
 * sessions; the neural timestep count per frame scales with the mode.
 */
enum class PerformanceMode(val key: String, val label: String, val frameHz: Int, val stepsPerFrame: Int) {
    ECO("eco", "Eco", 24, 4),
    BALANCED("balanced", "Balanced", 36, 6),
    MAX("max", "Max", 60, 8);

    companion object {
        fun fromKey(key: String): PerformanceMode =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/** User-adjustable neural dynamics (computational parameters, not physiology). */
data class DynamicsSettings(
    val decay: Float,
    val threshold: Float,
    val gain: Float,
    val noise: Float,
    val useBrainDefaults: Boolean,
)

class SettingsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val performance = stringPreferencesKey("performance_mode")
        val activeBrain = stringPreferencesKey("active_brain_id")
        val decay = floatPreferencesKey("dyn_decay")
        val threshold = floatPreferencesKey("dyn_threshold")
        val gain = floatPreferencesKey("dyn_gain")
        val noise = floatPreferencesKey("dyn_noise")
        val useDefaults = androidx.datastore.preferences.core.booleanPreferencesKey("dyn_use_defaults")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        ThemeMode.fromKey(it[Keys.theme] ?: "system")
    }

    val performanceMode: Flow<PerformanceMode> = context.dataStore.data.map {
        PerformanceMode.fromKey(it[Keys.performance] ?: "balanced")
    }

    val activeBrainId: Flow<String?> = context.dataStore.data.map { it[Keys.activeBrain] }

    val dynamics: Flow<DynamicsSettings> = context.dataStore.data.map {
        DynamicsSettings(
            decay = it[Keys.decay] ?: 0.82f,
            threshold = it[Keys.threshold] ?: 1.0f,
            gain = it[Keys.gain] ?: 1.0f,
            noise = it[Keys.noise] ?: 0.02f,
            useBrainDefaults = it[Keys.useDefaults] ?: true,
        )
    }

    fun setThemeMode(mode: ThemeMode) = scope.launch {
        context.dataStore.edit { it[Keys.theme] = mode.key }
    }

    fun setPerformanceMode(mode: PerformanceMode) = scope.launch {
        context.dataStore.edit { it[Keys.performance] = mode.key }
    }

    fun setActiveBrainId(id: String) = scope.launch {
        context.dataStore.edit { it[Keys.activeBrain] = id }
    }

    fun setDynamics(d: DynamicsSettings) = scope.launch {
        context.dataStore.edit {
            it[Keys.decay] = d.decay
            it[Keys.threshold] = d.threshold
            it[Keys.gain] = d.gain
            it[Keys.noise] = d.noise
            it[Keys.useDefaults] = d.useBrainDefaults
        }
    }
}
