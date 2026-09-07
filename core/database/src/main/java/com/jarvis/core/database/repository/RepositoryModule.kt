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
}
