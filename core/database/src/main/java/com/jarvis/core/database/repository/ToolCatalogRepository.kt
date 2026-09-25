package com.jarvis.core.database.repository

import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.ToolSource
import com.jarvis.core.database.dao.ToolCatalogDao
import com.jarvis.core.database.entity.ToolCatalogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

typealias RoomToolCatalogRepository = ToolCatalogRepository

/**
 * Domain representation of an installed or available tool in the Jarvis catalog.
 */
data class ToolCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
    val tier: PermissionTier = PermissionTier.READ_ONLY,
    val source: ToolSource = ToolSource.BUILTIN,
    val version: String = "1.0.0",
    val enabled: Boolean = true,
    val configJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

fun ToolCatalogEntity.toDomain(): ToolCatalogEntry = ToolCatalogEntry(
    id = id,
    name = name,
    description = description,
    parametersSchemaJson = parametersSchemaJson,
    tier = PermissionTier.fromWire(tier),
    source = ToolSource.fromWire(source),
    version = version,
    enabled = enabled,
    configJson = configJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ToolCatalogEntry.toEntity(): ToolCatalogEntity = ToolCatalogEntity(
    id = id,
    name = name,
    description = description,
    parametersSchemaJson = parametersSchemaJson,
    tier = tier.wireName,
    source = source.wireName,
    version = version,
    enabled = enabled,
    configJson = configJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Singleton
open class ToolCatalogRepository @Inject constructor(
    private val dao: ToolCatalogDao? = null,
) {
    open fun observeAll(): Flow<List<ToolCatalogEntry>> =
        dao?.observeAll()?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open fun observeEnabled(): Flow<List<ToolCatalogEntry>> =
        dao?.observeEnabled()?.map { list -> list.map { it.toDomain() } } ?: emptyFlow()

    open suspend fun getAll(): List<ToolCatalogEntry> =
        dao?.getAll()?.map { it.toDomain() } ?: emptyList()

    open suspend fun getEnabled(): List<ToolCatalogEntry> =
        dao?.getEnabled()?.map { it.toDomain() } ?: emptyList()

    open suspend fun get(id: String): ToolCatalogEntry? =
        dao?.get(id)?.toDomain()

    open suspend fun upsert(entry: ToolCatalogEntry) {
        dao?.upsert(entry.toEntity())
    }

    open suspend fun upsertAll(entries: List<ToolCatalogEntry>) {
        dao?.upsertAll(entries.map { it.toEntity() })
    }

    open suspend fun setEnabled(id: String, enabled: Boolean) {
        dao?.setEnabled(id, enabled, System.currentTimeMillis())
    }

    open suspend fun delete(id: String) {
        dao?.delete(id)
    }

    open suspend fun deleteBySource(source: String) {
        dao?.deleteBySource(source)
    }
}
