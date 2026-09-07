package com.jarvis.feature.settings

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Motion
import com.jarvis.core.designsystem.Spacing
import com.jarvis.feature.settings.components.PermissionsStep
import com.jarvis.feature.settings.components.SetupStep
import com.jarvis.feature.settings.components.StepDots
import com.jarvis.feature.settings.components.WelcomeStep

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingRoute(
    onOpenProviders: () -> Unit,
    onOpenLocalModels: () -> Unit,
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
                        onOpenLocalModels = onOpenLocalModels,
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
