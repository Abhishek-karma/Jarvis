package com.jarvis.feature.chat

import com.jarvis.core.common.Conversation
import com.jarvis.core.common.TimeGroup
import com.jarvis.core.database.repository.ConversationRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class HistoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: HistoryViewModel
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationsFlow: MutableStateFlow<List<Conversation>>

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        conversationRepository = mockk(relaxed = true)
        conversationsFlow = MutableStateFlow(emptyList())

        coEvery { conversationRepository.observeConversations() } returns conversationsFlow

        viewModel =
            HistoryViewModel(
                conversationRepository = conversationRepository,
                dispatchers =
                    com.jarvis.core.common
                        .DispatcherProvider(),
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() {
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `conversations are grouped by time`() =
        runTest {
            val now = System.currentTimeMillis()
            val conversations =
                listOf(
                    Conversation(id = "1", title = "Today", updatedAt = now),
                    Conversation(id = "2", title = "Yesterday", updatedAt = now - 86_400_000),
                    Conversation(id = "3", title = "Old", updatedAt = now - 86_400_000 * 10),
                )
            conversationsFlow.value = conversations
            advanceUntilIdle()

            val sections = viewModel.uiState.value.sections
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(sections.isNotEmpty())
        }

    @Test
    fun `pinned conversations appear in PINNED section at top`() =
        runTest {
            val now = System.currentTimeMillis()
            val conversations =
                listOf(
                    Conversation(id = "1", title = "Pinned", pinned = true, updatedAt = now),
                    Conversation(id = "2", title = "Not pinned", pinned = false, updatedAt = now),
                )
            conversationsFlow.value = conversations
            advanceUntilIdle()

            val sections = viewModel.uiState.value.sections
            assertEquals(TimeGroup.PINNED, sections.first().group)
            assertEquals(
                "Pinned",
                sections
                    .first()
                    .conversations
                    .first()
                    .title,
            )
        }

    @Test
    fun `togglePin calls repository with negated pin state`() =
        runTest {
            val conversation = Conversation(id = "1", title = "Test", pinned = false)
            coEvery { conversationRepository.setPinned(any(), any()) } just Runs

            viewModel.togglePin(conversation)
            advanceUntilIdle()

            coVerify { conversationRepository.setPinned("1", true) }
        }

    @Test
    fun `rename calls repository with new title`() =
        runTest {
            val conversation = Conversation(id = "1", title = "Old Title")
            coEvery { conversationRepository.renameConversation(any(), any()) } just Runs

            viewModel.rename(conversation, "New Title")
            advanceUntilIdle()

            coVerify { conversationRepository.renameConversation("1", "New Title") }
        }

    @Test
    fun `rename ignores blank titles`() =
        runTest {
            val conversation = Conversation(id = "1", title = "Old Title")

            viewModel.rename(conversation, "   ")
            advanceUntilIdle()

            coVerify(exactly = 0) { conversationRepository.renameConversation(any(), any()) }
        }

    @Test
    fun `delete calls repository with conversation id`() =
        runTest {
            val conversation = Conversation(id = "1", title = "To Delete")
            coEvery { conversationRepository.deleteConversation(any()) } just Runs

            viewModel.delete(conversation)
            advanceUntilIdle()

            coVerify { conversationRepository.deleteConversation("1") }
        }

    @Test
    fun `conversations within section are sorted by updatedAt descending`() =
        runTest {
            val now = System.currentTimeMillis()
            val conversations =
                listOf(
                    Conversation(id = "1", title = "Older today", updatedAt = now - 3_600_000),
                    Conversation(id = "2", title = "Newer today", updatedAt = now),
                )
            conversationsFlow.value = conversations
            advanceUntilIdle()

            val todaySection =
                viewModel.uiState.value.sections
                    .firstOrNull { it.group == TimeGroup.TODAY }
            assertEquals("Newer today", todaySection?.conversations?.first()?.title)
        }
}
