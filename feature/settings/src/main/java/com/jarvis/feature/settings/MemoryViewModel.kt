package com.jarvis.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Memory
import com.jarvis.core.common.MemoryCategory
import com.jarvis.core.database.repository.MemoryRepository
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class MemoryUiState(
    val memoryEnabled: Boolean = true,
    val memories: List<Memory> = emptyList(),
    val filteredMemories: List<Memory> = emptyList(),
    val selectedCategory: MemoryCategory? = null,
    val searchQuery: String = "",
    val editingMemory: Memory? = null,
    val showClearAllDialog: Boolean = false,
    val showAddDialog: Boolean = false,
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val userPreferences: UserPreferencesRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow<MemoryCategory?>(null)
    private val _editingMemory = MutableStateFlow<Memory?>(null)
    private val _showClearAllDialog = MutableStateFlow(false)
    private val _showAddDialog = MutableStateFlow(false)

    private val _dialogState = combine(
        _editingMemory,
        _showClearAllDialog,
        _showAddDialog,
    ) { editing, showClear, showAdd ->
        Triple(editing, showClear, showAdd)
    }

    val uiState: StateFlow<MemoryUiState> = combine(
        userPreferences.memoryEnabled,
        memoryRepository.observeActive(),
        _selectedCategory,
        _searchQuery,
        _dialogState,
    ) { enabled, list, category, query, dialogs ->
        val (editing, showClear, showAdd) = dialogs
        val filtered = list.filter { memory ->
            val matchesCategory = category == null || memory.category == category
            val matchesQuery = query.isBlank() || memory.content.contains(query, ignoreCase = true) ||
                memory.source.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }
        MemoryUiState(
            memoryEnabled = enabled,
            memories = list,
            filteredMemories = filtered,
            selectedCategory = category,
            searchQuery = query,
            editingMemory = editing,
            showClearAllDialog = showClear,
            showAddDialog = showAdd,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MemoryUiState(),
    )

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            userPreferences.setMemoryEnabled(enabled)
        }
    }

    fun setCategoryFilter(category: MemoryCategory?) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setEditingMemory(memory: Memory?) {
        _editingMemory.value = memory
    }

    fun setShowClearAllDialog(show: Boolean) {
        _showClearAllDialog.value = show
    }

    fun setShowAddDialog(show: Boolean) {
        _showAddDialog.value = show
    }

    fun saveMemory(category: MemoryCategory, content: String, isPrivate: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            val existing = _editingMemory.value
            if (existing != null) {
                memoryRepository.upsert(
                    existing.copy(
                        category = category,
                        content = content.trim(),
                        isPrivate = isPrivate,
                    )
                )
                _editingMemory.value = null
            } else {
                memoryRepository.upsert(
                    Memory(
                        id = UUID.randomUUID().toString(),
                        category = category,
                        content = content.trim(),
                        source = "user_manual",
                        confidence = 1.0f,
                        isPrivate = isPrivate,
                    )
                )
                _showAddDialog.value = false
            }
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch(dispatchers.io) {
            memoryRepository.delete(id)
        }
    }

    fun forgetConversation(conversationId: String) {
        viewModelScope.launch(dispatchers.io) {
            memoryRepository.deleteBySource("chat:$conversationId")
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch(dispatchers.io) {
            memoryRepository.clearAll()
            _showClearAllDialog.value = false
        }
    }
}
