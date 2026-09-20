package com.jarvis.core.capability

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Automation capabilities - controlled UI interaction fallback
 */
class AutomationCapability(
    private val context: Context,
) : Capability {

    override val id = CapabilityIds.UI_AUTOMATION

    override val description = "Controlled UI automation as a last resort when no native API exists"

    override val requiredPermissions = listOf(
        android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
    )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        enabledServices.contains(context.packageName)
    }

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        return CapabilityResult.Failure(
            code = "NOT_IMPLEMENTED",
            message = "UI automation not implemented. Use native capabilities instead.",
            isRetryable = false,
        )
    }
}