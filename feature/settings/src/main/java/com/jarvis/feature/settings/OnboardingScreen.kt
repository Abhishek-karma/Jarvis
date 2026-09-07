package com.jarvis.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Motion
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.Spacing


@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingRoute(
    onOpenProviders: () -> Unit,
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.finished.collect { onFinished() }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
        ) {

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepDots(step = uiState.step)
                Spacer(modifier = Modifier.weight(1f))
                if (uiState.step != OnboardingStep.PERMISSIONS) {
                    Text(
                        text = "Skip",
                        style = JarvisText.SenderLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .clip(JarvisShapes.pill)
                                .clickable(role = Role.Button) { viewModel.complete() }
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    )
                }
            }


            androidx.compose.animation.AnimatedContent(
                targetState = uiState.step,
                transitionSpec = {
                    fadeIn(tween(Motion.SCREEN_FADE_IN_MS)) togetherWith fadeOut(tween(Motion.SCREEN_FADE_OUT_MS))
                },
                label = "onboardingStep",
                modifier = Modifier.weight(1f),
            ) { step ->
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeStep(onContinue = viewModel::advance)
                    OnboardingStep.SETUP -> SetupStep(
                        hasProvider = uiState.hasProvider,
                        onOpenProviders = onOpenProviders,
                        onContinue = viewModel::advance,
                    )
                    OnboardingStep.PERMISSIONS -> PermissionsStep(
                        onDone = viewModel::complete,
                        onBack = viewModel::back,
                    )
                }
            }
        }
    }
}

/** The three progress dots — current in ink, others on the soft surface. */
@Composable
private fun StepDots(step: OnboardingStep) {

    val stepIndex = OnboardingStep.entries.indexOf(step) + 1
    val total = OnboardingStep.entries.size
    val a11yLabel = "Step $stepIndex of $total"
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier =
            Modifier
                .padding(start = Spacing.xs)
                .semantics { contentDescription = a11yLabel },
    ) {
        OnboardingStep.entries.forEach { s ->
            val active = s == step
            Box(
                modifier =
                    Modifier
                        .size(if (active) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
            )
        }
    }
}


@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xxl)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        JarvisMark(size = 56.dp)
        Spacer(modifier = Modifier.height(Spacing.xxl))
        Text(
            text = "Hi, I'm Jarvis.",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Your assistant for Android.",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text =
                "Reason and chat with your own providers, run tools with your " +
                    "approval, work fully on-device — your data stays on your phone.",
            style = JarvisText.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.huge))
        InkButton(text = "Get started", onClick = onContinue)
    }
}


@Composable
private fun SetupStep(
    hasProvider: Boolean,
    onOpenProviders: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xxl)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Connect a provider",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text =
                if (hasProvider) {
                    "You're set — you can add more or switch defaults anytime in Settings."
                } else {
                    "Bring your own key, or skip this and chat on-device. You can " +
                        "change providers anytime in Settings."
                },
            style = JarvisText.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.xxl))

        SetupCard(
            icon = Icons.Default.Cloud,
            title = "Cloud provider",
            description =
                "OpenAI-compatible, Anthropic, or Gemini — your key, your endpoint.",
            onClick = onOpenProviders,
        )
        Spacer(modifier = Modifier.height(Spacing.mdPlus))
        SetupCard(
            icon = Icons.Default.PhoneAndroid,
            title = "On-device model",
            description =
                "Private and works offline. Download anytime from Settings → Providers.",
            onClick = null,
            enabled = false,
        )

        Spacer(modifier = Modifier.height(Spacing.huge))
        InkButton(
            text = if (hasProvider) "Continue" else "Continue without a provider",
            onClick = onContinue,
        )
    }
}

/** One .sugg card: border, surface icon chip, semibold 13sp title, muted 12sp line. */
@Composable
private fun SetupCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
) {


    val alpha = if (enabled) 1f else 0.55f
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(alpha)
                .clip(JarvisShapes.card)
                .then(
                    if (onClick != null && enabled) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lgPlus),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            IconChip(icon = icon, size = 32.dp, iconSize = Spacing.xlPlus)
            Text(
                text = title,
                style = JarvisText.SenderLabel,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun PermissionsStep(
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current


    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions =
        remember {
            listOf(
                PermissionRowSpec(Manifest.permission.RECORD_AUDIO, Icons.Outlined.Mic, "Microphone", "Voice conversations"),
                PermissionRowSpec(Manifest.permission.POST_NOTIFICATIONS, Icons.Outlined.Notifications, "Notifications", "Jarvis updates"),
            )
        }
    val granted =
        remember(resumeTick) {
            permissions.associate { spec ->
                spec.permission to (
                    ContextCompat.checkSelfPermission(context, spec.permission) ==
                        PackageManager.PERMISSION_GRANTED
                )
            }
        }

    var pendingRequest by remember { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { wasGranted ->
            val requested = pendingRequest
            pendingRequest = null


            if (!wasGranted && requested != null &&
                context is Activity &&
                !context.shouldShowRequestPermissionRationale(requested)
            ) {
                openAppSettings(context)
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xxl)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Permissions",
            style = JarvisText.Display,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text =
                "Toggle what Jarvis may use — voice, updates, camera. " +
                    "Nothing is requested before you flip a switch, and you can " +
                    "change these anytime in Settings.",
            style = JarvisText.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))



        Surface(
            shape = JarvisShapes.card,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                permissions.forEachIndexed { index, spec ->
                    val isGranted = granted[spec.permission] == true
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        IconChip(icon = spec.icon, size = 36.dp, iconSize = Spacing.xl)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = spec.title,
                                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = spec.subtitle,
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = isGranted,
                            onCheckedChange = { want ->
                                if (want) {
                                    pendingRequest = spec.permission
                                    launcher.launch(spec.permission)
                                } else {


                                    openAppSettings(context)
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = spec.title },
                        )
                    }
                    if (index < permissions.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 68.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xxl))
        InkButton(text = "Start chatting", onClick = onDone)

        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = "Back",
            style = JarvisText.Metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .clip(JarvisShapes.pill)
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

/** One toggle row in the onboarding permissions card. */
private data class PermissionRowSpec(
    val permission: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

/** Opens the app's system-settings page for grant/revoke recovery. */
private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}


@Composable
private fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        shape = JarvisShapes.card,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Spacing.xl))
            Spacer(modifier = Modifier.size(Spacing.smPlus))
        }
        Text(text, style = JarvisText.Button)
    }
}

/** The secondary CTA — the HTML .abtn treatment: surface fill, ink text, 48dp height. */
@Composable
private fun SurfaceButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = JarvisShapes.card,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.huge),
    ) {
        Text(text, style = JarvisText.Button)
    }
}


@Composable
private fun IconChip(
    icon: ImageVector,
    size: Dp,
    iconSize: Dp,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(Radius.codeBlock))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
    }
}
