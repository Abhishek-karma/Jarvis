package com.jarvis.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.designsystem.JarvisSnackbarHost
import com.jarvis.core.designsystem.Motion
import com.jarvis.feature.settings.components.OnboardingTopBar
import com.jarvis.feature.settings.components.PermissionsStep
import com.jarvis.feature.settings.components.SetupStep
import com.jarvis.feature.settings.components.WelcomeStep

@Composable
fun OnboardingRoute(
    onOpenProviders: () -> Unit,
    onOpenLocalModels: () -> Unit,
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.finished.collect { onFinished() }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is OnboardingUiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            snackbarHost = { JarvisSnackbarHost(hostState = snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                OnboardingTopBar(
                    step = uiState.step,
                    onBack = viewModel::back,
                    onSkip = viewModel::complete,
                    modifier = Modifier.statusBarsPadding(),
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                AnimatedContent(
                    targetState = uiState.step,
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal) {
                            (slideInHorizontally(tween(260)) { width -> width / 4 } + fadeIn(tween(Motion.SCREEN_FADE_IN_MS)))
                                .togetherWith(slideOutHorizontally(tween(220)) { width -> -width / 4 } + fadeOut(tween(Motion.SCREEN_FADE_OUT_MS)))
                        } else {
                            (slideInHorizontally(tween(260)) { width -> -width / 4 } + fadeIn(tween(Motion.SCREEN_FADE_IN_MS)))
                                .togetherWith(slideOutHorizontally(tween(220)) { width -> width / 4 } + fadeOut(tween(Motion.SCREEN_FADE_OUT_MS)))
                        }
                    },
                    label = "onboardingStep",
                    modifier = Modifier.fillMaxSize(),
                ) { step ->
                    when (step) {
                        OnboardingStep.WELCOME -> WelcomeStep(
                            onContinue = viewModel::advance,
                        )
                        OnboardingStep.SETUP -> SetupStep(
                            uiState = uiState,
                            onOpenProviders = onOpenProviders,
                            onOpenLocalModels = onOpenLocalModels,
                            onContinue = viewModel::advance,
                        )
                        OnboardingStep.PERMISSIONS -> PermissionsStep(
                            onDone = viewModel::complete,
                        )
                    }
                }
            }
        }
    }
}
