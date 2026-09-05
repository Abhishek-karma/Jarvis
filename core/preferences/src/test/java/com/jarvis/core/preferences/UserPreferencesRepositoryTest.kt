package com.jarvis.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.jarvis.core.common.ThinkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * JVM tests for [UserPreferencesRepository] backed by an in-memory [DataStore] fake, so the
 * repo's key mapping, defaults, and clamping are verified without Android or a real file.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class UserPreferencesRepositoryTest {
    private lateinit var store: FakeDataStore
    private lateinit var repo: UserPreferencesRepository

    @BeforeEach
    fun setUp() {
        store = FakeDataStore()
        repo = UserPreferencesRepository(store)
    }

    /** In-memory DataStore: updateData applies to a mutable snapshot, data re-emits on change. */
    private class FakeDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())

        override val data: StateFlow<Preferences> get() = flow

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(flow.value.toMutablePreferences())
            flow.value = next
            return next
        }

        /** Injects a raw key/value, bypassing the repo — for corruption-path tests. */
        fun corrupt(
            key: String,
            value: String,
        ) {
            val prefs = flow.value.toMutablePreferences()
            prefs[androidx.datastore.preferences.core.stringPreferencesKey(key)] = value
            flow.value = prefs
        }
    }

    @Test
    fun `defaults match the spec`() =
        runTest {
            assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
            assertEquals(ThinkMode.AUTO, repo.thinkMode.first())
            assertEquals(false, repo.cautiousModeEnabled.first())
            assertEquals(15, repo.agentStepCap.first())
            assertTrue(repo.memoryExtractionEnabled.first())
            assertEquals(false, repo.cloudSyncEnabled.first())
            assertEquals("jarvis", repo.wakeWord.first())
            assertEquals(1_500, repo.voiceSilenceTimeoutMs.first())
        }

    @Test
    fun `theme round-trips`() =
        runTest {
            repo.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, repo.themeMode.first())
            repo.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, repo.themeMode.first())
        }

    @Test
    fun `unknown stored theme falls back to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageName("neon"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageName(null))
    }

    @Test
    fun `unknown stored think mode falls back to AUTO`() =
        runTest {
            repo.setThinkMode(ThinkMode.ON)
            // Simulate a corrupted value arriving in the store.
            store.corrupt("think_mode", "turbo")
            assertEquals(ThinkMode.AUTO, repo.thinkMode.first())
        }

    @Test
    fun `step cap clamps out-of-range writes`() =
        runTest {
            repo.setAgentStepCap(99)
            assertEquals(40, repo.agentStepCap.first())
            repo.setAgentStepCap(0)
            assertEquals(1, repo.agentStepCap.first())
            repo.setAgentStepCap(20)
            assertEquals(20, repo.agentStepCap.first())
        }

    @Test
    fun `cautious mode round-trips`() =
        runTest {
            repo.setCautiousModeEnabled(true)
            assertEquals(true, repo.cautiousModeEnabled.first())
        }

    @Test
    fun `think mode round-trips`() =
        runTest {
            repo.setThinkMode(ThinkMode.ON)
            assertEquals(ThinkMode.ON, repo.thinkMode.first())
        }

    @Test
    fun `wake word normalizes on write`() =
        runTest {
            repo.setWakeWord("  Hey Jarvis  ")
            assertEquals("hey jarvis", repo.wakeWord.first())
        }

    @Test
    fun `voice silence timeout floors at 300ms`() =
        runTest {
            repo.setVoiceSilenceTimeoutMs(50)
            assertEquals(300, repo.voiceSilenceTimeoutMs.first())
            repo.setVoiceSilenceTimeoutMs(2_500)
            assertEquals(2_500, repo.voiceSilenceTimeoutMs.first())
        }

    @Test
    fun `memory extraction defaults on and round-trips`() =
        runTest {
            repo.setMemoryExtractionEnabled(false)
            assertEquals(false, repo.memoryExtractionEnabled.first())
        }
}
