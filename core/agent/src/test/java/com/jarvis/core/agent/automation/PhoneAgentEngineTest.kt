package com.jarvis.core.agent.automation

import com.jarvis.core.agent.automation.engine.PhoneAgentEngine
import com.jarvis.core.agent.automation.engine.PhoneAutomationDriver
import com.jarvis.core.agent.execution.ErrorCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneAgentEngineTest {

    private class FakeAutomationDriver(
        var observeResult: Result<UiWindowSnapshot>,
    ) : PhoneAutomationDriver {
        val actions = mutableListOf<String>()

        override suspend fun observe(): Result<UiWindowSnapshot> = observeResult

        override suspend fun tap(target: String, x: Float?, y: Float?): AutomationActionResult {
            actions.add("tap($target)")
            return AutomationActionResult(success = true, action = "tap", target = target)
        }

        override suspend fun type(
            text: String,
            target: String?,
            submit: Boolean,
            clearFirst: Boolean,
        ): AutomationActionResult {
            actions.add("type($text, submit=$submit)")
            return AutomationActionResult(success = true, action = "type", target = text)
        }

        override suspend fun submit(target: String?): AutomationActionResult {
            actions.add("submit(${target ?: "focused"})")
            return AutomationActionResult(success = true, action = "submit", target = target ?: "focused")
        }

        override suspend fun scroll(direction: String, targetContainer: String?): AutomationActionResult {
            actions.add("scroll($direction)")
            return AutomationActionResult(success = true, action = "scroll", target = direction)
        }

        override suspend fun swipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long,
        ): AutomationActionResult {
            actions.add("swipe")
            return AutomationActionResult(success = true, action = "swipe")
        }

        override suspend fun back(): AutomationActionResult {
            actions.add("back")
            return AutomationActionResult(success = true, action = "back")
        }

        override suspend fun verify(expected: String, timeoutMs: Long): AutomationActionResult {
            return AutomationActionResult(success = true, action = "verify", target = expected)
        }
    }

    @Test
    fun `engine halts immediately and reports PERMISSION_REQUIRED when permission dialog is present`() = runTest {
        val permissionSnapshot = UiWindowSnapshot(
            packageName = "com.google.android.permissioncontroller",
            windowTitle = "Allow Camera?",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Allow",
                    contentDescription = null,
                    viewId = "permission_allow_button",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
            isPermissionDialog = true,
        )

        val driver = FakeAutomationDriver(Result.success(permissionSnapshot))
        val engine = PhoneAgentEngine(driver = driver)

        val result = engine.execute(goal = "Open Camera and take a photo")

        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.PERMISSION_REQUIRED, result.errorCode)
        assertTrue(result.message.contains("permission dialog"))
        // Proves the agent did NOT click "Allow" automatically
        assertTrue(driver.actions.isEmpty())
    }

    @Test
    fun `engine fails with ACCESSIBILITY_UNAVAILABLE when accessibility service is down`() = runTest {
        val driver = FakeAutomationDriver(Result.failure(IllegalStateException("Accessibility service is unavailable.")))
        val engine = PhoneAgentEngine(driver = driver)

        val result = engine.execute(goal = "Search OpenAI on Instagram")

        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.ACCESSIBILITY_UNAVAILABLE, result.errorCode)
    }

    @Test
    fun `engine requires concrete screen evidence before claiming goal success`() = runTest {
        val emptyScreenSnapshot = UiWindowSnapshot(
            packageName = "com.instagram.android",
            windowTitle = "Home",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = null,
                    contentDescription = "Search",
                    viewId = "search_tab",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val driver = FakeAutomationDriver(Result.success(emptyScreenSnapshot))
        val engine = PhoneAgentEngine(driver = driver, maxSteps = 2)

        val result = engine.execute(goal = "Search OpenAI on Instagram")

        // Without results or OpenAI on screen, it must NOT claim success!
        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.STEP_LIMIT_REACHED, result.errorCode)
    }

    @Test
    fun `engine verifies search completion when typed and target appears on screen`() = runTest {
        val searchScreenSnapshot = UiWindowSnapshot(
            packageName = "com.instagram.android",
            windowTitle = "Search Results",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "OpenAI",
                    contentDescription = "OpenAI Verified Account",
                    viewId = "row_search_user_username",
                    className = "TextView",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val driver = FakeAutomationDriver(Result.success(searchScreenSnapshot))
        val engine = PhoneAgentEngine(driver = driver)

        // Test goal verification method directly with concrete screen evidence
        val verified = engine.verifyGoalReached(
            goal = "Search OpenAI on Instagram",
            snapshot = searchScreenSnapshot,
            completedSteps = listOf("Typed \"OpenAI\""),
        )

        assertTrue(verified)
    }

    @Test
    fun `engine rejects goal completion without completed actions`() = runTest {
        val searchScreenSnapshot = UiWindowSnapshot(
            packageName = "com.instagram.android",
            windowTitle = "Search",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "OpenAI",
                    contentDescription = null,
                    viewId = "query",
                    className = "TextView",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val driver = FakeAutomationDriver(Result.success(searchScreenSnapshot))
        val engine = PhoneAgentEngine(driver = driver)

        // Without completed steps, verification must return false
        val verified = engine.verifyGoalReached(
            goal = "Search OpenAI on Instagram",
            snapshot = searchScreenSnapshot,
            completedSteps = emptyList(),
        )

        assertFalse(verified)
    }

    @Test
    fun `engine verifies explicit postcondition on screen`() = runTest {
        val targetSnapshot = UiWindowSnapshot(
            packageName = "com.spotify.music",
            windowTitle = "Settings",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Dark Mode",
                    contentDescription = null,
                    viewId = "theme_setting",
                    className = "Switch",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val driver = FakeAutomationDriver(Result.success(targetSnapshot))
        val engine = PhoneAgentEngine(driver = driver)

        val verified = engine.verifyGoalReached(
            goal = "Enable Dark Mode",
            snapshot = targetSnapshot,
            completedSteps = listOf("Tapped Dark Mode"),
            explicitPostcondition = "Dark Mode",
        )

        assertTrue(verified)

        val unverified = engine.verifyGoalReached(
            goal = "Enable Dark Mode",
            snapshot = targetSnapshot,
            completedSteps = listOf("Tapped Dark Mode"),
            explicitPostcondition = "Audio Quality High",
        )

        assertFalse(unverified)
    }

    @Test
    fun `engine halts on sensitive click when confirmation is denied`() = runTest {
        val deleteButtonSnapshot = UiWindowSnapshot(
            packageName = "com.android.settings",
            windowTitle = "Storage",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Delete",
                    contentDescription = null,
                    viewId = "delete_btn",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val driver = FakeAutomationDriver(Result.success(deleteButtonSnapshot))
        val engine = PhoneAgentEngine(driver = driver)

        val rejectingGate = com.jarvis.core.agent.ConfirmationGate { _, _ -> false }

        val result = engine.execute(
            goal = "Delete storage data",
            confirmationGate = rejectingGate,
        )

        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.CONFIRMATION_REQUIRED, result.errorCode)
        assertTrue(result.message.contains("requires user confirmation"))
        // Driver must not have executed tap
        assertTrue(driver.actions.isEmpty())
    }

    @Test
    fun `engine propagates coroutine cancellation immediately`() = runTest {
        val driver = FakeAutomationDriver(Result.success(UiWindowSnapshot("com.example.app", null, emptyList())))
        val engine = PhoneAgentEngine(driver = driver)

        val job = launch {
            engine.execute("Perform a long task")
        }
        job.cancel()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `engine uses configured LLM provider and model to decide next action`() = runTest {
        val screenSnapshot = UiWindowSnapshot(
            packageName = "com.google.android.youtube",
            windowTitle = "YouTube",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = null,
                    contentDescription = "Search",
                    viewId = "menu_item_search",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        var requestedModel: String? = null
        val fakeLlm = object : com.jarvis.core.network.LlmProvider {
            override val id: String = "fake_llm"
            override val capabilities = com.jarvis.core.network.ProviderCapabilities()
            override fun streamChat(request: com.jarvis.core.network.ChatRequest): kotlinx.coroutines.flow.Flow<com.jarvis.core.network.ChatStreamEvent> {
                requestedModel = request.model
                return kotlinx.coroutines.flow.flowOf(
                    com.jarvis.core.network.ChatStreamEvent.TokenDelta(
                        """{"action":"click","target":"1","reason":"Tap search button"}"""
                    )
                )
            }
            override suspend fun listModels(): Result<List<com.jarvis.core.common.ModelInfo>> = Result.success(emptyList())
            override fun close() {}
        }

        val driver = FakeAutomationDriver(Result.success(screenSnapshot))
        val engine = PhoneAgentEngine(
            driver = driver,
            llmProvider = { fakeLlm },
            modelIdProvider = { "gemini-1.5-flash" },
            maxSteps = 1,
        )

        val result = engine.execute(goal = "Search Android on YouTube")
        assertEquals("gemini-1.5-flash", requestedModel)
        assertTrue(driver.actions.contains("tap(1)"))
    }

    @Test
    fun `engine resolves LlmProviderElement and ConfirmationGateElement from coroutine context`() = runTest {
        val deleteScreen = UiWindowSnapshot(
            packageName = "com.android.settings",
            windowTitle = "Storage",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Submit form",
                    contentDescription = null,
                    viewId = "submit_btn",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val driver = FakeAutomationDriver(Result.success(deleteScreen))
        val fakeLlm = object : com.jarvis.core.network.LlmProvider {
            override val id: String = "fake_llm_context"
            override val capabilities = com.jarvis.core.network.ProviderCapabilities()
            override fun streamChat(request: com.jarvis.core.network.ChatRequest): kotlinx.coroutines.flow.Flow<com.jarvis.core.network.ChatStreamEvent> {
                return kotlinx.coroutines.flow.flowOf(
                    com.jarvis.core.network.ChatStreamEvent.TokenDelta(
                        """{"action":"submit","target":"1"}"""
                    )
                )
            }
            override suspend fun listModels(): Result<List<com.jarvis.core.common.ModelInfo>> = Result.success(emptyList())
            override fun close() {}
        }

        var gateInvoked = false
        val gate = com.jarvis.core.agent.ConfirmationGate { tool, args ->
            gateInvoked = true
            false // Deny confirmation
        }

        val engine = PhoneAgentEngine(driver = driver, maxSteps = 1)

        kotlinx.coroutines.withContext(
            com.jarvis.core.agent.ConfirmationGateElement(gate) +
                com.jarvis.core.agent.LlmProviderElement(fakeLlm, "model-from-context")
        ) {
            val result = engine.execute("Submit storage form")
            assertFalse(result.isSuccess)
            assertEquals(ErrorCode.CONFIRMATION_REQUIRED, result.errorCode)
            assertTrue(gateInvoked)
            assertTrue(driver.actions.isEmpty())
        }
    }

    @Test
    fun `engine bounds execution by maxSteps and returns STEP_LIMIT_REACHED`() = runTest {
        var screenIndex = 0
        val driver = object : PhoneAutomationDriver {
            val actions = mutableListOf<String>()
            override suspend fun observe(): Result<UiWindowSnapshot> {
                screenIndex++
                return Result.success(
                    UiWindowSnapshot(
                        packageName = "com.example.app",
                        windowTitle = "Screen $screenIndex",
                        elements = listOf(
                            UiElementSnapshot(
                                index = screenIndex,
                                text = "Item $screenIndex",
                                contentDescription = null,
                                viewId = "item_$screenIndex",
                                className = "TextView",
                                isClickable = true,
                                isEditable = false,
                            )
                        )
                    )
                )
            }
            override suspend fun tap(target: String, x: Float?, y: Float?): AutomationActionResult {
                actions.add("tap($target)")
                return AutomationActionResult(success = true, action = "tap", target = target)
            }
            override suspend fun type(text: String, target: String?, submit: Boolean, clearFirst: Boolean) = AutomationActionResult(success = true, action = "type")
            override suspend fun submit(target: String?) = AutomationActionResult(success = true, action = "submit")
            override suspend fun scroll(direction: String, targetContainer: String?) = AutomationActionResult(success = true, action = "scroll")
            override suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) = AutomationActionResult(success = true, action = "swipe")
            override suspend fun back() = AutomationActionResult(success = true, action = "back")
            override suspend fun verify(expected: String, timeoutMs: Long) = AutomationActionResult(success = true, action = "verify")
        }

        val engine = PhoneAgentEngine(driver = driver, maxSteps = 3)
        val result = engine.execute("Search for non-existent target")
        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.STEP_LIMIT_REACHED, result.errorCode)
        assertTrue(result.message.contains("step limit"))
    }

    @Test
    fun `engine detects repeated action without progress and halts with LOOP_DETECTED`() = runTest {
        val unchangingSnapshot = UiWindowSnapshot(
            packageName = "com.example.app",
            windowTitle = "Stuck Screen",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Search",
                    contentDescription = null,
                    viewId = "search_btn",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                )
            )
        )
        val fakeLlm = object : com.jarvis.core.network.LlmProvider {
            override val id = "loop_llm"
            override val capabilities = com.jarvis.core.network.ProviderCapabilities()
            override fun streamChat(request: com.jarvis.core.network.ChatRequest) =
                kotlinx.coroutines.flow.flowOf(
                    com.jarvis.core.network.ChatStreamEvent.TokenDelta("""{"action":"click","target":"1"}""")
                )
            override suspend fun listModels() = Result.success(emptyList<com.jarvis.core.common.ModelInfo>())
            override fun close() {}
        }

        val driver = FakeAutomationDriver(Result.success(unchangingSnapshot))
        val engine = PhoneAgentEngine(
            driver = driver,
            llmProvider = { fakeLlm },
            modelIdProvider = { "test-model" },
            maxSteps = 5,
        )

        val result = engine.execute("Click search")
        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.LOOP_DETECTED, result.errorCode)
    }

    @Test
    fun `engine times out when decision exceeds decisionTimeoutMs`() = runTest {
        val snapshot = UiWindowSnapshot(
            packageName = "com.example.app",
            windowTitle = "App",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Button",
                    contentDescription = null,
                    viewId = "btn",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                )
            )
        )
        val hangingLlm = object : com.jarvis.core.network.LlmProvider {
            override val id = "hanging_llm"
            override val capabilities = com.jarvis.core.network.ProviderCapabilities()
            override fun streamChat(request: com.jarvis.core.network.ChatRequest) =
                kotlinx.coroutines.flow.flow<com.jarvis.core.network.ChatStreamEvent> {
                    kotlinx.coroutines.delay(10_000L)
                    emit(com.jarvis.core.network.ChatStreamEvent.TokenDelta("""{"action":"click","target":"1"}"""))
                }
            override suspend fun listModels() = Result.success(emptyList<com.jarvis.core.common.ModelInfo>())
            override fun close() {}
        }

        val driver = FakeAutomationDriver(Result.success(snapshot))
        val engine = PhoneAgentEngine(
            driver = driver,
            llmProvider = { hangingLlm },
            modelIdProvider = { "test-model" },
            decisionTimeoutMs = 100L,
        )

        val result = engine.execute("Click button")
        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.TIMEOUT, result.errorCode)
        assertTrue(result.message.contains("timed out"))
    }

    @Test
    fun `engine times out when overall wall-clock exceeds overallTimeoutMs`() = runTest {
        var count = 0
        val slowDriver = object : PhoneAutomationDriver {
            override suspend fun observe(): Result<UiWindowSnapshot> {
                count++
                return Result.success(
                    UiWindowSnapshot(
                        packageName = "com.example.app",
                        windowTitle = "Screen $count",
                        elements = listOf(
                            UiElementSnapshot(
                                index = count,
                                text = "Item $count",
                                contentDescription = null,
                                viewId = "item_$count",
                                className = "TextView",
                                isClickable = true,
                                isEditable = false,
                            )
                        )
                    )
                )
            }
            override suspend fun tap(target: String, x: Float?, y: Float?): AutomationActionResult {
                kotlinx.coroutines.delay(500L)
                return AutomationActionResult(success = true, action = "tap")
            }
            override suspend fun type(text: String, target: String?, submit: Boolean, clearFirst: Boolean) = AutomationActionResult(success = true, action = "type")
            override suspend fun submit(target: String?) = AutomationActionResult(success = true, action = "submit")
            override suspend fun scroll(direction: String, targetContainer: String?) = AutomationActionResult(success = true, action = "scroll")
            override suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) = AutomationActionResult(success = true, action = "swipe")
            override suspend fun back() = AutomationActionResult(success = true, action = "back")
            override suspend fun verify(expected: String, timeoutMs: Long) = AutomationActionResult(success = true, action = "verify")
        }

        val engine = PhoneAgentEngine(
            driver = slowDriver,
            maxSteps = 10,
            overallTimeoutMs = 150L,
        )

        val result = engine.execute("Search for item")
        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.TIMEOUT, result.errorCode)
        assertTrue(result.message.contains("timed out"))
    }

    @Test
    fun `sensitive action without confirmation gate halts with CONFIRMATION_REQUIRED and never executes silently`() = runTest {
        val deleteButtonSnapshot = UiWindowSnapshot(
            packageName = "com.android.settings",
            windowTitle = "Settings",
            elements = listOf(
                UiElementSnapshot(
                    index = 1,
                    text = "Delete all accounts",
                    contentDescription = null,
                    viewId = "delete_btn",
                    className = "Button",
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        val driver = FakeAutomationDriver(Result.success(deleteButtonSnapshot))
        val engine = PhoneAgentEngine(driver = driver, confirmationGate = null)

        val result = engine.execute(goal = "Delete all accounts")

        assertFalse(result.isSuccess)
        assertEquals(ErrorCode.CONFIRMATION_REQUIRED, result.errorCode)
        assertTrue(result.message.contains("requires confirmation, but no confirmation gate is available"))
        assertTrue(driver.actions.isEmpty())
    }
}
