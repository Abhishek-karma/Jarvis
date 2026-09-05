package com.jarvis.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jarvis.core.common.ThinkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user preferences the v0.5 spec persists (`09-DATA-MODELS §3`). Stored as
 * Preferences DataStore key-value pairs — the spec's `UserPreferences` is a flat list of
 * named settings, so the typed-proto machinery would be overkill.
 *
 * The repo is pure JVM against a [DataStore] instance, so unit tests run without Robolectric;
 * the Hilt module wires the Android-file-backed DataStore in production.
 */
@Singleton
class UserPreferencesRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        // ---- keys ----

        private object Keys {
            /** "system" | "light" | "dark" — see ThemeMode. */
            val THEME = stringPreferencesKey("theme_mode")

            /** "off" | "on" | "auto" — see ThinkMode. */
            val THINK_MODE = stringPreferencesKey("think_mode")

            /** When true, every agent tool call requires confirmation regardless of tier. */
            val CAUTIOUS_MODE = booleanPreferencesKey("cautious_mode")

            /** ReAct step ceiling, clamped to 1..40 (AgentEngine.MAX_STEP_CAP). */
            val AGENT_STEP_CAP = intPreferencesKey("agent_step_cap")

            /** Whether on-device conversations are mined for long-term memories. */
            val MEMORY_EXTRACTION = booleanPreferencesKey("memory_extraction_enabled")

            /** Whether history syncs to the user's cloud backup. */
            val CLOUD_SYNC = booleanPreferencesKey("cloud_sync_enabled")

            /** Wake-word phrase for the voice pipeline ("jarvis"). */
            val WAKE_WORD = stringPreferencesKey("wake_word")

            /** Voice mode silence timeout in milliseconds before turn-taking. */
            val VOICE_SILENCE_TIMEOUT_MS = intPreferencesKey("voice_silence_timeout_ms")
        }

        // ---- theme ----

        val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs -> ThemeMode.fromStorageName(prefs[Keys.THEME]) }

        suspend fun setThemeMode(mode: ThemeMode) {
            dataStore.edit { it[Keys.THEME] = mode.storageName }
        }

        // ---- think mode ----

        /** Persisted as the enum name, lowercase; unknown values fall back to AUTO. */
        val thinkMode: Flow<ThinkMode> =
            dataStore.data.map { prefs ->
                runCatching { ThinkMode.valueOf((prefs[Keys.THINK_MODE] ?: "auto").uppercase()) }
                    .getOrDefault(ThinkMode.AUTO)
            }

        suspend fun setThinkMode(mode: ThinkMode) {
            dataStore.edit { it[Keys.THINK_MODE] = mode.name.lowercase() }
        }

        // ---- cautious mode ----

        val cautiousModeEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.CAUTIOUS_MODE] ?: false }

        suspend fun setCautiousModeEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.CAUTIOUS_MODE] = enabled }
        }

        // ---- agent step cap ----

        /**
         * The agent step cap, clamped on read to the engine's hard ceiling so a stale or
         * corrupted value can never crash AgentEngine's `require`.
         */
        val agentStepCap: Flow<Int> =
            dataStore.data.map { prefs -> (prefs[Keys.AGENT_STEP_CAP] ?: DEFAULT_STEP_CAP).coerceIn(1, MAX_STEP_CAP) }

        suspend fun setAgentStepCap(cap: Int) {
            dataStore.edit { it[Keys.AGENT_STEP_CAP] = cap.coerceIn(1, MAX_STEP_CAP) }
        }

        // ---- memory extraction ----

        val memoryExtractionEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.MEMORY_EXTRACTION] ?: true }

        suspend fun setMemoryExtractionEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.MEMORY_EXTRACTION] = enabled }
        }

        // ---- cloud sync ----

        val cloudSyncEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.CLOUD_SYNC] ?: false }

        suspend fun setCloudSyncEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.CLOUD_SYNC] = enabled }
        }

        // ---- wake word ----

        val wakeWord: Flow<String> = dataStore.data.map { it[Keys.WAKE_WORD] ?: DEFAULT_WAKE_WORD }

        suspend fun setWakeWord(phrase: String) {
            dataStore.edit { it[Keys.WAKE_WORD] = phrase.trim().lowercase() }
        }

        // ---- voice silence timeout ----

        /** Voice mode silence timeout in ms, floored at 300 so turn-taking can't thrash. */
        val voiceSilenceTimeoutMs: Flow<Int> = dataStore.data.map { it[Keys.VOICE_SILENCE_TIMEOUT_MS] ?: DEFAULT_VOICE_SILENCE_TIMEOUT_MS }

        suspend fun setVoiceSilenceTimeoutMs(timeoutMs: Int) {
            dataStore.edit { it[Keys.VOICE_SILENCE_TIMEOUT_MS] = timeoutMs.coerceAtLeast(MIN_VOICE_SILENCE_TIMEOUT_MS) }
        }

        companion object {
            const val DEFAULT_STEP_CAP = 15
            const val MAX_STEP_CAP = 40 // must match AgentEngine.MAX_STEP_CAP
            const val DEFAULT_WAKE_WORD = "jarvis"
            const val DEFAULT_VOICE_SILENCE_TIMEOUT_MS = 1_500
            const val MIN_VOICE_SILENCE_TIMEOUT_MS = 300
        }
    }

/** Appearance setting — System follows the OS dark-mode flag. */
enum class ThemeMode(val storageName: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorageName(name: String?): ThemeMode = entries.firstOrNull { it.storageName == name } ?: SYSTEM
    }
}
