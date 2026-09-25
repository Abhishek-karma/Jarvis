package com.jarvis.core.agent.di

import com.jarvis.core.agent.AgentRunner
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.ConfirmationGate
import com.jarvis.core.agent.DefaultToolPolicy
import com.jarvis.core.agent.ToolExecutor
import com.jarvis.core.agent.ToolPolicy
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.needle.NeedleConfig
import com.jarvis.core.agent.needle.NeedleEngine
import com.jarvis.core.agent.needle.NeedleRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideConfirmationGate(): ConfirmationGate = ConfirmationGate { _, _ -> false }

    @Provides
    @Singleton
    fun provideToolPolicy(): ToolPolicy = DefaultToolPolicy()

    @Provides
    @Singleton
    fun provideNeedleConfig(): NeedleConfig = NeedleConfig()

    @Provides
    @Singleton
    fun provideNeedleEngine(config: NeedleConfig): NeedleEngine = NeedleEngine(config)

    @Provides
    @Singleton
    fun provideNeedleRouter(engine: NeedleEngine, config: NeedleConfig): NeedleRouter =
        NeedleRouter(engine = engine, config = config)

    @Provides
    @Singleton
    fun provideToolExecutor(
        registry: ToolRegistry,
        audit: AuditLogger,
        toolPolicy: ToolPolicy,
    ): ToolExecutor = ToolExecutor(
        registry = registry,
        audit = audit,
        toolPolicy = toolPolicy,
    )

    @Provides
    @Singleton
    fun provideAgentRunner(
        registry: ToolRegistry,
        audit: AuditLogger,
        confirmationGate: ConfirmationGate,
        toolPolicy: ToolPolicy,
        toolExecutor: ToolExecutor,
    ): AgentRunner = AgentRunner(
        registry = registry,
        audit = audit,
        confirmationGate = confirmationGate,
        toolPolicy = toolPolicy,
        toolExecutor = toolExecutor,
    )

    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
