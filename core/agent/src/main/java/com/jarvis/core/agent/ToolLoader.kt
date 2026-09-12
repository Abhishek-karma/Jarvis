package com.jarvis.core.agent

import com.jarvis.core.agent.tools.DeclarativeHttpTool
import com.jarvis.core.agent.tools.HttpToolConfig
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.ToolSource
import com.jarvis.core.database.repository.AuditLogEntry
import com.jarvis.core.database.repository.AuditLogRepository
import com.jarvis.core.database.repository.ToolCatalogEntry
import com.jarvis.core.database.repository.ToolCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Bridges persistent [ToolCatalogRepository] with runtime [ToolRegistry].
 * Responsible for syncing built-in tools, validating definitions, instantiating custom/imported tools,
 * and registering enabled tools into the in-memory registry.
 */
class ToolLoader(
    private val catalogRepository: ToolCatalogRepository,
    private val registry: ToolRegistry,
    private val auditLogRepository: AuditLogRepository? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Ensures all built-in tools provided by the application are present in the catalog.
     * Preserves existing enabled/disabled user preferences.
     */
    suspend fun syncBuiltIns(builtInTools: List<Tool>) = withContext(Dispatchers.IO) {
        val existingEntries = catalogRepository.getAll().associateBy { it.name }
        val toUpsert = mutableListOf<ToolCatalogEntry>()

        for (tool in builtInTools) {
            val existing = existingEntries[tool.name]
            if (existing == null) {
                toUpsert.add(
                    ToolCatalogEntry(
                        id = "builtin_${tool.name}",
                        name = tool.name,
                        description = tool.description,
                        parametersSchemaJson = tool.parametersSchemaJson,
                        tier = tool.tier,
                        source = ToolSource.BUILTIN,
                        version = tool.version,
                        enabled = tool.enabled,
                    ),
                )
            } else if (
                existing.description != tool.description ||
                existing.parametersSchemaJson != tool.parametersSchemaJson ||
                existing.tier != tool.tier ||
                existing.version != tool.version
            ) {
                toUpsert.add(
                    existing.copy(
                        description = tool.description,
                        parametersSchemaJson = tool.parametersSchemaJson,
                        tier = tool.tier,
                        version = tool.version,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        if (toUpsert.isNotEmpty()) {
            catalogRepository.upsertAll(toUpsert)
        }
    }

    /**
     * Loads all enabled tools from the catalog and updates [ToolRegistry].
     * Safely handles instantiation and schema validation.
     */
    suspend fun reloadActiveTools(
        builtInTools: List<Tool>,
        mcpTools: List<Tool> = emptyList(),
    ): List<Tool> = withContext(Dispatchers.IO) {
        syncBuiltIns(builtInTools)
        val enabledCatalog = catalogRepository.getEnabled()
        val builtInMap = builtInTools.associateBy { it.name }
        val mcpMap = mcpTools.associateBy { it.name }

        registry.clear()
        val activeTools = mutableListOf<Tool>()

        for (entry in enabledCatalog) {
            val toolInstance = resolveTool(entry, builtInMap, mcpMap)
            if (toolInstance != null) {
                if (validateToolDefinition(toolInstance)) {
                    registry.registerOrReplace(toolInstance)
                    activeTools.add(toolInstance)
                } else {
                    logAudit("ToolLoader", "validation_failed", "Tool ${entry.name} failed schema/name validation")
                }
            } else {
                logAudit("ToolLoader", "resolution_failed", "No runtime implementation for tool ${entry.name}")
            }
        }

        activeTools
    }

    suspend fun setToolEnabled(
        id: String,
        enabled: Boolean,
        builtInTools: List<Tool>,
        mcpTools: List<Tool> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        catalogRepository.setEnabled(id, enabled)
        reloadActiveTools(builtInTools, mcpTools)
    }

    suspend fun registerCustomTool(
        entry: ToolCatalogEntry,
        builtInTools: List<Tool>,
        mcpTools: List<Tool> = emptyList(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(entry.name.isNotBlank()) { "Tool name cannot be blank" }
            require(entry.name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                "Tool name must contain only alphanumeric characters, underscores, and dashes"
            }
            catalogRepository.upsert(entry)
            reloadActiveTools(builtInTools, mcpTools)
            Unit
        }
    }

    suspend fun deleteTool(
        id: String,
        name: String,
    ) = withContext(Dispatchers.IO) {
        catalogRepository.delete(id)
        registry.unregister(name)
    }

    private fun resolveTool(
        entry: ToolCatalogEntry,
        builtInMap: Map<String, Tool>,
        mcpMap: Map<String, Tool>,
    ): Tool? =
        when (entry.source) {
            ToolSource.BUILTIN -> builtInMap[entry.name]
            ToolSource.MCP -> mcpMap[entry.name]
            ToolSource.CUSTOM, ToolSource.IMPORTED -> {
                val cfg = entry.configJson
                if (!cfg.isNullOrBlank()) {
                    runCatching {
                        val config = json.decodeFromString<HttpToolConfig>(cfg)
                        DeclarativeHttpTool(
                            name = entry.name,
                            description = entry.description,
                            parametersSchemaJson = entry.parametersSchemaJson,
                            tier = entry.tier,
                            source = entry.source,
                            version = entry.version,
                            enabled = entry.enabled,
                            config = config,
                        )
                    }.getOrNull()
                } else {
                    null
                }
            }
        }

    private fun validateToolDefinition(tool: Tool): Boolean {
        if (tool.name.isBlank() || tool.description.isBlank()) return false
        if (tool.parametersSchemaJson.isBlank()) return false
        return true
    }

    private suspend fun logAudit(toolName: String, status: String, message: String) {
        auditLogRepository?.record(
            AuditLogEntry(
                id = UUID.randomUUID().toString(),
                agentRunId = null,
                toolName = toolName,
                tier = PermissionTier.READ_ONLY.wireName,
                paramsRedactedJson = "{\"message\":\"$message\"}",
                resultStatus = status,
                userConfirmed = false,
            ),
        )
    }
}
