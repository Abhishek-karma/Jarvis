package com.jarvis.feature.settings.components

import android.content.Context
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import java.util.Locale

/** Local Storage usage card — real device stats above a thin progress bar. */
@Composable
fun LocalStorageCard() {
    val context = LocalContext.current
    val stats = rememberStorageStats(context)
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Spacing.xxl),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Local Storage",
                    style = JarvisText.ConvTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Models are stored only on this device",
                    style = JarvisText.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { stats.usedFraction.toFloat() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    color = JarvisColors.Accent.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "${formatBytes(stats.usedBytes)} used · ${formatBytes(stats.freeBytes)} available",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(stats.usedFraction * 100).toInt()}%",
                style = JarvisText.H3,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Storage stats derived from the app's files dir volume — models live there. */
private data class StorageStats(
    val usedBytes: Long,
    val freeBytes: Long,
) {
    val totalBytes: Long get() = usedBytes + freeBytes
    val usedFraction: Double get() = if (totalBytes > 0) usedBytes.toDouble() / totalBytes else 0.0
}

@Composable
private fun rememberStorageStats(context: Context): StorageStats =
    remember {
        runCatching {
            val dir = context.filesDir
            val stat = StatFs(dir.path)
            val total = stat.totalBytes
            val free = stat.freeBytes
            StorageStats(usedBytes = total - free, freeBytes = free)
        }.getOrDefault(StorageStats(0, 0))
    }

/** Human byte size, matching the storage card's phrasing. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.US, "%.0f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.US, "%.0f MB", mb)
    return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
