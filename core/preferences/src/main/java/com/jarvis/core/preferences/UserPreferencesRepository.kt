package com.jarvis.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jarvis.core.common.LocalBenchmarkResult
import com.jarvis.core.common.ThinkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class UserPreferencesRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {


        private object Keys {
            /** "system" | "light" | "dark" — see ThemeMode. */
            val THEME = stringPreferencesKey("theme_mode")

            /** "off" | "on" | "auto" — see ThinkMode. */
            val THINK_MODE = stringPreferencesKey("think_mode")

            /** When true, every agent tool call requires confirmation regardless of tier. */
            val CAUTIOUS_MODE = booleanPreferencesKey("cautious_mode")

            /** ReAct step ceiling, clamped to 1..40 (AgentEngine.MAX_STEP_CAP). */
            val AGENT_STEP_CAP = intPreferencesKey("agent_step_cap")

            /** Whether the first-run onboarding flow has been completed. */
            val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

            /** "local" | "cloud" — see ChatMode. Default route for new conversations. */
            val CHAT_MODE = stringPreferencesKey("chat_mode")

            /** Whether the on-device (Local) route may use internet-backed tools (web fetch). */
            val LOCAL_INTERNET_ACCESS = booleanPreferencesKey("local_internet_access")

            /** Last successful update check (epoch millis) — throttles startup checks to daily. */
            val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")

            /** Whether assistant memory retention and context injection is active. */
            val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")

            /** "fast" | "balanced" | "reasoning" | "private" | "coding" | "vision" */
            val MODEL_PROFILE = stringPreferencesKey("model_profile")

            /** Whether expert shell tool execution is enabled. */
            val EXPERT_SHELL_ENABLED = booleanPreferencesKey("expert_shell_enabled")

            /** Whether Shizuku bridge integration is enabled. */
            val SHIZUKU_ENABLED = booleanPreferencesKey("shizuku_enabled")

            /** Local model inference temperature (0.0 to 1.5). Default 0.7. */
            val LOCAL_TEMPERATURE = floatPreferencesKey("local_temperature")

            /** Local model top-p sampling parameter (0.1 to 1.0). Default 0.9. */
            val LOCAL_TOP_P = floatPreferencesKey("local_top_p")

            /** Local model maximum context / output token ceiling. Default 1024. */
            val LOCAL_MAX_TOKENS = intPreferencesKey("local_max_tokens")

            /** Number of CPU threads allocated for local inference (1..8). Default 4. */
            val LOCAL_THREADS = intPreferencesKey("local_threads")

            /** Whether to keep the on-device model prewarmed in RAM for instantaneous response. */
            val LOCAL_PREWARM = booleanPreferencesKey("local_prewarm")

            /** Persisted JSON result of the latest on-device benchmark run. */
            val LOCAL_BENCHMARK_JSON = stringPreferencesKey("local_benchmark_json")

            /** Whether the agent should formulate and present an explicit step-by-step plan before acting. */
            val PLAN_FIRST_MODE = booleanPreferencesKey("plan_first_mode")
        }



        val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs -> ThemeMode.fromStorageName(prefs[Keys.THEME]) }

        suspend fun setThemeMode(mode: ThemeMode) {
            dataStore.edit { it[Keys.THEME] = mode.storageName }
        }



        /** Persisted as the enum name, lowercase; unknown values fall back to AUTO. */
        val thinkMode: Flow<ThinkMode> =
            dataStore.data.map { prefs ->
                runCatching { ThinkMode.valueOf((prefs[Keys.THINK_MODE] ?: "auto").uppercase()) }
                    .getOrDefault(ThinkMode.AUTO)
            }

        suspend fun setThinkMode(mode: ThinkMode) {
            dataStore.edit { it[Keys.THINK_MODE] = mode.name.lowercase() }
        }



        val cautiousModeEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.CAUTIOUS_MODE] ?: false }

        suspend fun setCautiousModeEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.CAUTIOUS_MODE] = enabled }
        }

        val planFirstMode: Flow<Boolean> = dataStore.data.map { it[Keys.PLAN_FIRST_MODE] ?: false }

        suspend fun setPlanFirstMode(enabled: Boolean) {
            dataStore.edit { it[Keys.PLAN_FIRST_MODE] = enabled }
        }




        val agentStepCap: Flow<Int> =
            dataStore.data.map { prefs -> (prefs[Keys.AGENT_STEP_CAP] ?: DEFAULT_STEP_CAP).coerceIn(1, MAX_STEP_CAP) }

        suspend fun setAgentStepCap(cap: Int) {
            dataStore.edit { it[Keys.AGENT_STEP_CAP] = cap.coerceIn(1, MAX_STEP_CAP) }
        }



        /** True once the user has completed (or skipped) the onboarding flow. */
        val onboardingCompleted: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[Keys.ONBOARDING_COMPLETED] ?: false }

        suspend fun setOnboardingCompleted(completed: Boolean) {
            dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
        }




        val chatMode: Flow<ChatMode> =
            dataStore.data.map { prefs ->
                runCatching { ChatMode.valueOf((prefs[Keys.CHAT_MODE] ?: "cloud").uppercase()) }
                    .getOrDefault(ChatMode.CLOUD)
            }

        suspend fun setChatMode(mode: ChatMode) {
            dataStore.edit { it[Keys.CHAT_MODE] = mode.name.lowercase() }
        }




        val localInternetAccess: Flow<Boolean> = dataStore.data.map { it[Keys.LOCAL_INTERNET_ACCESS] ?: true }

        suspend fun setLocalInternetAccess(enabled: Boolean) {
            dataStore.edit { it[Keys.LOCAL_INTERNET_ACCESS] = enabled }
        }

        val lastUpdateCheckMs: Flow<Long> = dataStore.data.map { it[Keys.LAST_UPDATE_CHECK] ?: 0L }

        suspend fun markUpdateChecked() {
            dataStore.edit { it[Keys.LAST_UPDATE_CHECK] = System.currentTimeMillis() }
        }

        val memoryEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.MEMORY_ENABLED] ?: true }

        suspend fun setMemoryEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.MEMORY_ENABLED] = enabled }
        }

        val modelProfile: Flow<String> = dataStore.data.map { it[Keys.MODEL_PROFILE] ?: "balanced" }

        suspend fun setModelProfile(profile: String) {
            dataStore.edit { it[Keys.MODEL_PROFILE] = profile.lowercase() }
        }

        val expertShellEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.EXPERT_SHELL_ENABLED] ?: false }

        suspend fun setExpertShellEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.EXPERT_SHELL_ENABLED] = enabled }
        }

        val shizukuEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.SHIZUKU_ENABLED] ?: true }

        suspend fun setShizukuEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.SHIZUKU_ENABLED] = enabled }
        }

        val localTemperature: Flow<Float> = dataStore.data.map { it[Keys.LOCAL_TEMPERATURE] ?: 0.7f }

        suspend fun setLocalTemperature(temperature: Float) {
            dataStore.edit { it[Keys.LOCAL_TEMPERATURE] = temperature.coerceIn(0.0f, 1.5f) }
        }

        val localTopP: Flow<Float> = dataStore.data.map { it[Keys.LOCAL_TOP_P] ?: 0.9f }

        suspend fun setLocalTopP(topP: Float) {
            dataStore.edit { it[Keys.LOCAL_TOP_P] = topP.coerceIn(0.1f, 1.0f) }
        }

        val localMaxTokens: Flow<Int> = dataStore.data.map { it[Keys.LOCAL_MAX_TOKENS] ?: 1024 }

        suspend fun setLocalMaxTokens(maxTokens: Int) {
            dataStore.edit { it[Keys.LOCAL_MAX_TOKENS] = maxTokens.coerceIn(256, 4096) }
        }

        val localThreads: Flow<Int> = dataStore.data.map { it[Keys.LOCAL_THREADS] ?: 4 }

        suspend fun setLocalThreads(threads: Int) {
            dataStore.edit { it[Keys.LOCAL_THREADS] = threads.coerceIn(1, 8) }
        }

        val localPrewarm: Flow<Boolean> = dataStore.data.map { it[Keys.LOCAL_PREWARM] ?: true }

        suspend fun setLocalPrewarm(enabled: Boolean) {
            dataStore.edit { it[Keys.LOCAL_PREWARM] = enabled }
        }

        val localBenchmarkResult: Flow<LocalBenchmarkResult?> =
            dataStore.data.map { prefs ->
                val json = prefs[Keys.LOCAL_BENCHMARK_JSON] ?: return@map null
                runCatching {
                    val obj = JSONObject(json)
                    LocalBenchmarkResult(
                        modelId = obj.optString("modelId", ""),
                        modelName = obj.optString("modelName", ""),
                        promptTokens = obj.optInt("promptTokens", 0),
                        completionTokens = obj.optInt("completionTokens", 0),
                        timeToFirstTokenMs = obj.optLong("timeToFirstTokenMs", 0L),
                        generationSpeedTps = obj.optDouble("generationSpeedTps", 0.0).toFloat(),
                        totalTimeMs = obj.optLong("totalTimeMs", 0L),
                        peakMemoryMb = obj.optLong("peakMemoryMb", 0L),
                        threadCount = obj.optInt("threadCount", 4),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    )
                }.getOrNull()
            }

        suspend fun setLocalBenchmarkResult(result: LocalBenchmarkResult) {
            val json = JSONObject().apply {
                put("modelId", result.modelId)
                put("modelName", result.modelName)
                put("promptTokens", result.promptTokens)
                put("completionTokens", result.completionTokens)
                put("timeToFirstTokenMs", result.timeToFirstTokenMs)
                put("generationSpeedTps", result.generationSpeedTps.toDouble())
                put("totalTimeMs", result.totalTimeMs)
                put("peakMemoryMb", result.peakMemoryMb)
                put("threadCount", result.threadCount)
                put("timestamp", result.timestamp)
            }.toString()
            dataStore.edit { it[Keys.LOCAL_BENCHMARK_JSON] = json }
        }

        suspend fun clearLocalBenchmarkResult() {
            dataStore.edit { it.remove(Keys.LOCAL_BENCHMARK_JSON) }
        }

        companion object {
            const val DEFAULT_STEP_CAP = 15
            const val MAX_STEP_CAP = 40
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


enum class ChatMode(val storageName: String) {
    LOCAL("local"),
    CLOUD("cloud");
}
