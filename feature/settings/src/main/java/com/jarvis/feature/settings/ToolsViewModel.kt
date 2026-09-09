package com.jarvis.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolLoader
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.agent.ToolSource
import com.jarvis.core.agent.tools.HttpToolConfig
import com.jarvis.core.database.repository.ToolCatalogEntry
import com.jarvis.core.database.repository.ToolCatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

enum class ToolFilterTab {
    ALL,
    BUILTIN,
    CUSTOM,
    MCP,
}

data class ToolTestState(
    val toolName: String? = null,
    val argumentsJson: String = "{}",
    val isRunning: Boolean = false,
    val result: ToolResult? = null,
    val error: String? = null,
)

data class ToolsUiState(
    val tools: List<ToolCatalogEntry> = emptyList(),
    val activeFilter: ToolFilterTab = ToolFilterTab.ALL,
    val searchQuery: String = "",
    val testState: ToolTestState = ToolTestState(),
    val isCreating: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val catalogRepository: ToolCatalogRepository,
    private val toolRegistry: ToolRegistry,
    private val toolLoader: ToolLoader,
    private val builtInTools: List<@JvmSuppressWildcards Tool>,
) : ViewModel() {

    private val _filterTab = MutableStateFlow(ToolFilterTab.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _testState = MutableStateFlow(ToolTestState())
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ToolsUiState> = combine(
        catalogRepository.observeAll(),
        _filterTab,
        _searchQuery,
        _testState,
        _errorMessage,
    ) { catalog, tab, query, test, error ->
        val filtered = catalog.filter { entry ->
            val matchesTab = when (tab) {
                ToolFilterTab.ALL -> true
                ToolFilterTab.BUILTIN -> entry.source == ToolSource.BUILTIN
                ToolFilterTab.CUSTOM -> entry.source == ToolSource.CUSTOM || entry.source == ToolSource.IMPORTED
                ToolFilterTab.MCP -> entry.source == ToolSource.MCP
            }
            val matchesQuery = query.isBlank() ||
                entry.name.contains(query, ignoreCase = true) ||
                entry.description.contains(query, ignoreCase = true)
            matchesTab && matchesQuery
        }
        ToolsUiState(
            tools = filtered,
            activeFilter = tab,
            searchQuery = query,
            testState = test,
            errorMessage = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ToolsUiState(),
    )

    init {
        viewModelScope.launch {
            toolLoader.syncBuiltIns(builtInTools)
            toolLoader.reloadActiveTools(builtInTools)
        }
    }

    fun setFilterTab(tab: ToolFilterTab) {
        _filterTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleTool(id: String, enabled: Boolean) {
        viewModelScope.launch {
            toolLoader.setToolEnabled(id, enabled, builtInTools)
        }
    }

    fun openTestDialog(entry: ToolCatalogEntry) {
        _testState.value = ToolTestState(
            toolName = entry.name,
            argumentsJson = "{}",
            isRunning = false,
            result = null,
            error = null,
        )
    }

    fun closeTestDialog() {
        _testState.value = ToolTestState()
    }

    fun setTestArgumentsJson(json: String) {
        _testState.update { it.copy(argumentsJson = json) }
    }

    fun executeTest(toolName: String, argsJson: String) {
        viewModelScope.launch {
            _testState.update { it.copy(isRunning = true, error = null, result = null) }
            val tool = toolRegistry.get(toolName)
                ?: builtInTools.find { it.name == toolName }
            if (tool == null) {
                _testState.update {
                    it.copy(
                        isRunning = false,
                        error = "Tool not found in active registry or built-ins",
                    )
                }
                return@launch
            }
            try {
                val res = tool.execute(argsJson)
                _testState.update { it.copy(isRunning = false, result = res) }
            } catch (err: Throwable) {
                _testState.update {
                    it.copy(
                        isRunning = false,
                        error = err.message ?: "Execution failed",
                    )
                }
            }
        }
    }

    fun createCustomHttpTool(
        name: String,
        description: String,
        tier: PermissionTier,
        schemaJson: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyTemplate: String?,
    ) {
        viewModelScope.launch {
            try {
                val config = HttpToolConfig(
                    url = url,
                    method = method,
                    headers = headers,
                    bodyTemplate = bodyTemplate,
                )
                val configJson = Json.encodeToString(config)
                val entry = ToolCatalogEntry(
                    id = "custom_${UUID.randomUUID()}",
                    name = name.trim(),
                    description = description.trim(),
                    parametersSchemaJson = schemaJson.ifBlank { "{}" },
                    tier = tier,
                    source = ToolSource.CUSTOM,
                    version = "1.0.0",
                    enabled = true,
                    configJson = configJson,
                )
                val res = toolLoader.registerCustomTool(entry, builtInTools)
                if (res.isFailure) {
                    _errorMessage.value = res.exceptionOrNull()?.message ?: "Failed to save tool"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to create tool"
            }
        }
    }

    fun deleteTool(id: String, name: String) {
        viewModelScope.launch {
            toolLoader.deleteTool(id, name)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
