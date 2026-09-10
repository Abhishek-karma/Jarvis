package com.jarvis.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            JarvisHeader(
                title = "Permissions",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->


        var resumeTick by remember { mutableIntStateOf(0) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeTick++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            PermissionRow(
                key = resumeTick,
                icon = Icons.Outlined.Mic,
                title = "Microphone",
                subtitle = "Voice conversations",
                permission = Manifest.permission.RECORD_AUDIO,
            )
            PermissionRow(
                key = resumeTick,
                icon = Icons.Outlined.Notifications,
                title = "Notifications",
                subtitle = "Jarvis updates",
                permission = Manifest.permission.POST_NOTIFICATIONS,
            )
            Text(
                text = "For agent actions",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md, start = Spacing.xs),
            )
            PermissionRow(
                key = resumeTick,
                icon = Icons.Outlined.Folder,
                title = "Device Storage & Files",
                subtitle = "Read documents, downloads, and device files",
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                },
                additionalPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                    )
                } else {
                    emptyList()
                },
            )
            PermissionRow(
                key = resumeTick,
                icon = Icons.Outlined.Event,
                title = "Calendar",
                subtitle = "Create events and reminders",
                permission = Manifest.permission.READ_CALENDAR,
            )
            PermissionRow(
                key = resumeTick,
                icon = Icons.Outlined.Person,
                title = "Contacts",
                subtitle = "Look up numbers to call or text",
                permission = Manifest.permission.READ_CONTACTS,
            )
            PermissionRow(
                key = resumeTick,
                icon = Icons.Outlined.Sms,
                title = "SMS",
                subtitle = "Send text messages on request",
                permission = Manifest.permission.SEND_SMS,
            )
            PermissionRow(
                key = resumeTick,
                icon = Icons.Outlined.Call,
                title = "Phone",
                subtitle = "Dial calls on request",
                permission = Manifest.permission.CALL_PHONE,
            )

            Text(
                text =
                    "Only what Jarvis uses. Denied permissions can be retried here " +
                        "or in system settings.",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs),
            )
            Spacer(modifier = Modifier.height(Spacing.huge))
        }
    }
}

/** One `.perm` card: icon tile + title/subtitle + trailing action pill. */
@Composable
private fun PermissionRow(
    key: Int,
    icon: ImageVector,
    title: String,
    subtitle: String,
    permission: String,
    additionalPermissions: List<String> = emptyList(),
) {
    val context = LocalContext.current
    val allPermissions = remember(permission, additionalPermissions) {
        listOf(permission) + additionalPermissions
    }
    val granted =
        remember(key, allPermissions) {
            allPermissions.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier.padding(
                    start = Spacing.md,
                    top = Spacing.md,
                    bottom = Spacing.md,
                    end = Spacing.lg,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            JarvisIconTile(icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = JarvisText.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PermissionToggle(granted = granted, permissions = allPermissions, name = title)
        }
    }
}


@Composable
private fun PermissionToggle(
    granted: Boolean,
    permissions: List<String>,
    name: String,
) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val anyGranted = results.values.any { it }
            if (!anyGranted &&
                context is Activity &&
                permissions.any { !context.shouldShowRequestPermissionRationale(it) }
            ) {
                openAppSettings(context)
            }
        }

    Switch(
        checked = granted,
        onCheckedChange = { want ->
            if (want) {
                launcher.launch(permissions.toTypedArray())
            } else {
                openAppSettings(context)
            }
        },
        modifier = Modifier.semantics { contentDescription = "$name toggle" },
    )
}

/** Opens the app's system-settings page so a permanently-denied permission can be re-granted. */
private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}
