package com.jarvis.core.database.repository

import com.jarvis.core.common.PermissionTier
import com.jarvis.core.common.ToolSource
import com.jarvis.core.database.dao.ToolCatalogDao
import com.jarvis.core.database.entity.ToolCatalogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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

interface ToolCatalogRepository {
    fun observeAll(): Flow<List<ToolCatalogEntry>>
    fun observeEnabled(): Flow<List<ToolCatalogEntry>>
    suspend fun getAll(): List<ToolCatalogEntry>
    suspend fun getEnabled(): List<ToolCatalogEntry>
    suspend fun get(id: String): ToolCatalogEntry?
    suspend fun upsert(entry: ToolCatalogEntry)
    suspend fun upsertAll(entries: List<ToolCatalogEntry>)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
    suspend fun deleteBySource(source: ToolSource)
}

@Singleton
class RoomToolCatalogRepository @Inject constructor(
    private val toolCatalogDao: ToolCatalogDao,
) : ToolCatalogRepository {

    override fun observeAll(): Flow<List<ToolCatalogEntry>> =
        toolCatalogDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEnabled(): Flow<List<ToolCatalogEntry>> =
        toolCatalogDao.observeEnabled().map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<ToolCatalogEntry> =
        toolCatalogDao.getAll().map { it.toDomain() }

    override suspend fun getEnabled(): List<ToolCatalogEntry> =
        toolCatalogDao.getEnabled().map { it.toDomain() }

    override suspend fun get(id: String): ToolCatalogEntry? =
        toolCatalogDao.get(id)?.toDomain()

    override suspend fun upsert(entry: ToolCatalogEntry) =
        toolCatalogDao.upsert(entry.toEntity())

    override suspend fun upsertAll(entries: List<ToolCatalogEntry>) =
        toolCatalogDao.upsertAll(entries.map { it.toEntity() })

    override suspend fun setEnabled(id: String, enabled: Boolean) =
        toolCatalogDao.setEnabled(id, enabled, System.currentTimeMillis())

    override suspend fun delete(id: String) =
        toolCatalogDao.delete(id)

    override suspend fun deleteBySource(source: ToolSource) =
        toolCatalogDao.deleteBySource(source.wireName)
}
