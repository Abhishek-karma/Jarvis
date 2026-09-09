package com.jarvis.core.database.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Room-backed [ChatRepository] to the domain-facing [ConversationRepository] contract. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ChatRepository): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindAuditLogRepository(impl: RoomAuditLogRepository): AuditLogRepository

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: RoomMemoryRepository): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: RoomTaskRepository): TaskRepository

    @Binds
    @Singleton
    abstract fun bindRoutineRepository(impl: RoomRoutineRepository): RoutineRepository

    @Binds
    @Singleton
    abstract fun bindReversibleActionRepository(impl: RoomReversibleActionRepository): ReversibleActionRepository

    @Binds
    @Singleton
    abstract fun bindOperationRepository(impl: RoomOperationRepository): OperationRepository

    @Binds
    @Singleton
    abstract fun bindToolCatalogRepository(impl: RoomToolCatalogRepository): ToolCatalogRepository
}
