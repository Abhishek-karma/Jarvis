package com.jarvis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jarvis.core.designsystem.JarvisTheme
import com.jarvis.core.navigation.Routes
import com.jarvis.core.preferences.ThemeMode
import com.jarvis.feature.chat.ChatRoute
import com.jarvis.feature.chat.VoiceModeRoute
import com.jarvis.feature.settings.AboutScreen
import com.jarvis.feature.settings.OnboardingRoute
import com.jarvis.feature.settings.ProviderEditScreen
import com.jarvis.feature.settings.ProvidersListScreen
import com.jarvis.feature.settings.PermissionsScreen
import com.jarvis.feature.settings.SettingsScreen
import com.jarvis.app.update.UpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Holds the system splash until the first-run flag has been read from DataStore. */
    private var keepSplashOnScreen = true

    /** Throttled startup update check — posts a notification when a newer release exists. */
    private val updateViewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {

        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSplashOnScreen }
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {

            val mainViewModel: MainViewModel = hiltViewModel()
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val showOnboarding by mainViewModel.showOnboarding.collectAsStateWithLifecycle()



            LaunchedEffect(showOnboarding) {
                if (showOnboarding != null) keepSplashOnScreen = false
            }

            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            JarvisTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val firstRun = showOnboarding) {

                        null -> Unit
                        else -> JarvisNavHost(startOnboarding = firstRun)
                    }
                }
            }
        }
    }
}

@Composable
private fun JarvisNavHost(startOnboarding: Boolean) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.CHAT,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onOpenProviders = { navController.navigate(Routes.PROVIDERS_LIST) },
                onOpenLocalModels = { navController.navigate(Routes.providers("local")) },
                onFinished = {


                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CHAT) {
            ChatRoute(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenVoiceMode = { navController.navigate(Routes.VOICE_MODE) },
            )
        }
        composable(Routes.VOICE_MODE) { backStackEntry ->




            val chatEntry =
                remember(backStackEntry) {
                    runCatching { navController.getBackStackEntry(Routes.CHAT) }.getOrNull()
                }
            if (chatEntry != null) {
                VoiceModeRoute(
                    onEnd = { navController.popBackStack() },
                    viewModel = hiltViewModel(chatEntry),
                )
            } else {
                VoiceModeRoute(
                    onEnd = { navController.popBackStack() },
                )
            }
        }
                composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS_LIST) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.PROVIDERS_LIST + "?${Routes.PROVIDER_ARG_TAB}={${Routes.PROVIDER_ARG_TAB}}",
            arguments = listOf(navArgument(Routes.PROVIDER_ARG_TAB) { defaultValue = "cloud" }),
        ) { entry ->
            ProvidersListScreen(
                initialTab = entry.arguments?.getString(Routes.PROVIDER_ARG_TAB) ?: "cloud",
                onBack = { navController.popBackStack() },
                onAddProvider = { navController.navigate(Routes.PROVIDER_EDIT) },
                onEditProvider = { id -> navController.navigate(Routes.providerEdit(id)) },
            )
        }
        composable(
            route = "${Routes.PROVIDER_EDIT}?${Routes.PROVIDER_ARG_ID}={${Routes.PROVIDER_ARG_ID}}",
            arguments =
                listOf(
                    navArgument(Routes.PROVIDER_ARG_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue =
                            null
                    },
                ),
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getString(Routes.PROVIDER_ARG_ID)
            ProviderEditScreen(
                providerId = providerId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
    }
}
