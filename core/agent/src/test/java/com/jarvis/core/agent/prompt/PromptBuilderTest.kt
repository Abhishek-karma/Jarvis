package com.jarvis.core.agent.prompt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Date
import java.util.TimeZone

class PromptBuilderTest {

    @Test
    fun `cloud prompt contains all 9 structured layers`() {
        val config = PromptConfig(
            isLocal = false,
            isVoiceMode = false,
            planFirst = true,
            webToolsAvailable = true,
            memoryContext = "- [USER_PREF]: Prefers dark mode\n- [LONG_TERM_FACT]: Lives in Seattle",
            deviceDateTime = "Thursday, September 10, 2026 at 19:43:49",
            deviceTimeZone = "America/Los_Angeles (UTC-07:00, PDT)",
            customInstructions = "Always answer politely.",
        )

        val prompt = PromptBuilder.buildCloudSystemPrompt(config)

        // 1. SYSTEM CORE
        assertTrue(prompt.contains("=== 1. SYSTEM CORE ==="))
        assertTrue(prompt.contains("Jarvis"))
        assertTrue(prompt.contains("Android"))
        assertTrue(prompt.contains("authoritative source of truth"))

        // 2. RUNTIME CONTEXT
        assertTrue(prompt.contains("=== 2. RUNTIME CONTEXT ==="))
        assertTrue(prompt.contains("Current Device Date and Time: Thursday, September 10, 2026 at 19:43:49"))
        assertTrue(prompt.contains("Current Device Timezone: America/Los_Angeles (UTC-07:00, PDT)"))
        assertTrue(prompt.contains("Anti-Hallucination Rule"))

        // 3. USER MEMORY
        assertTrue(prompt.contains("=== 3. USER MEMORY ==="))
        assertTrue(prompt.contains("ALWAYS take precedence over prior stored memories"))
        assertTrue(prompt.contains("Prefers dark mode"))
        assertTrue(prompt.contains("Lives in Seattle"))

        // 4. TOOL RULES
        assertTrue(prompt.contains("=== 4. TOOL RULES ==="))
        assertTrue(prompt.contains("Never invent tool results"))
        assertTrue(prompt.contains("Never claim execution without running"))
        assertTrue(prompt.contains("Untrusted Data"))
        assertTrue(prompt.contains("Prompt Injection Defense"))

        // 5. DEVICE RULES
        assertTrue(prompt.contains("=== 5. DEVICE RULES ==="))
        assertTrue(prompt.contains("never claim battery level without a battery observation"))
        assertTrue(prompt.contains("launch_app"))
        assertTrue(prompt.contains("create_file, read_file, search_files"))

        // 6. WEB RULES
        assertTrue(prompt.contains("=== 6. WEB RULES ==="))
        assertTrue(prompt.contains("Web tools (search_web, fetch_url) are active"))

        // 7. VOICE RULES
        assertTrue(prompt.contains("=== 7. VOICE RULES ==="))
        assertTrue(prompt.contains("standard structured formatting"))

        // 8. SAFETY & CONFIRMATION
        assertTrue(prompt.contains("=== 8. SAFETY & CONFIRMATION ==="))
        assertTrue(prompt.contains("require user confirmation"))

        // 9. RESPONSE STYLE
        assertTrue(prompt.contains("=== 9. RESPONSE STYLE ==="))
        assertTrue(prompt.contains("Plan-First Mode is ACTIVE"))
        assertTrue(prompt.contains("Always answer politely."))
    }

    @Test
    fun `web rules reflect offline or disabled web access`() {
        val prompt = PromptBuilder.buildCloudSystemPrompt(
            PromptConfig(
                isLocal = false,
                webToolsAvailable = false,
            ),
        )
        assertTrue(prompt.contains("Web access is currently UNAVAILABLE in this environment"))
        assertFalse(prompt.contains("Web tools (search_web, fetch_url) are active"))
    }

    @Test
    fun `voice mode generates speech-optimized rules`() {
        val prompt = PromptBuilder.buildCloudSystemPrompt(
            PromptConfig(
                isLocal = false,
                isVoiceMode = true,
            ),
        )
        assertTrue(prompt.contains("Voice Mode is currently ACTIVE"))
        assertTrue(prompt.contains("Avoid markdown tables, ascii art, long nested bullet lists"))
    }

    @Test
    fun `local compact prompt is high-density and token-efficient`() {
        val config = PromptConfig(
            isLocal = true,
            isVoiceMode = false,
            planFirst = false,
            webToolsAvailable = true,
            memoryContext = "- [FACT]: Name is Alex",
            deviceDateTime = "Thursday, Sep 10, 2026 19:43",
            deviceTimeZone = "UTC-7",
        )

        val prompt = PromptBuilder.buildLocalSystemPrompt(config)

        assertTrue(prompt.contains("You are Jarvis, an Android personal AI assistant."))
        assertTrue(prompt.contains("Tools are authoritative"))
        assertTrue(prompt.contains("Device state (battery, apps, messages, files) must come strictly from tool observations"))
        assertTrue(prompt.contains("Current user instructions in conversation override previous stored memory"))
        assertTrue(prompt.contains("untrusted external data"))
        assertTrue(prompt.contains("[Memory]\n- [FACT]: Name is Alex"))

        // Verify compactness (should be under 250 words / ~300 tokens)
        val wordCount = prompt.split(Regex("\\s+")).size
        assertTrue(wordCount < 250, "Local prompt should be compact, but was $wordCount words")
    }

    @Test
    fun `dateTime and timeZone formatting produces valid strings`() {
        val tz = TimeZone.getTimeZone("America/New_York")
        val formattedDate = PromptBuilder.formatCurrentDateTime(Date(1757548800000L), tz)
        val formattedTz = PromptBuilder.formatCurrentTimeZone(tz)

        assertTrue(formattedDate.isNotEmpty())
        assertTrue(formattedTz.contains("America/New_York"))
    }
}
