package com.jarvis.feature.chat.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.jarvis.core.agent.AuditLogger
import com.jarvis.core.agent.ToolRegistry
import com.jarvis.core.agent.tools.SystemInfoTools
import com.jarvis.core.database.repository.AuditLogEntry
import com.jarvis.core.database.repository.AuditLogRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {
    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
    ): ToolRegistry =
        ToolRegistry().apply {
            SystemInfoTools
                .all(
                    batteryPercent = { readBatteryPercent(context) },
                    storageFreeBytes = { readFreeBytes(context) },
                    networkState = { readNetworkState(context) },
                ).forEach { register(it) }
        }

    @Provides
    @Singleton
    fun provideAuditLogger(repository: AuditLogRepository): AuditLogger =
        AuditLogger { record ->
            repository.record(
                AuditLogEntry(
                    agentRunId = record.agentRunId,
                    toolName = record.toolName,
                    tier = record.tier,
                    paramsRedactedJson = record.paramsRedactedJson,
                    resultStatus = record.resultStatus,
                    userConfirmed = record.userConfirmed,
                    timestamp = record.timestamp,
                ),
            )
        }

    private fun readBatteryPercent(context: Context): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
    }

    private fun readFreeBytes(context: Context): Long? =
        runCatching { StatFs(Environment.getDataDirectory().absolutePath).availableBytes }.getOrNull()

    private fun readNetworkState(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "offline"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        val online =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!online) return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "online"
        }
    }
}
