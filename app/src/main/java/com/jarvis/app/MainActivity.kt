package com.jarvis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jarvis.core.designsystem.JarvisTheme
import com.jarvis.core.navigation.Routes
import com.jarvis.feature.chat.ChatRoute
import com.jarvis.feature.chat.VoiceModeRoute
import com.jarvis.feature.settings.AboutScreen
import com.jarvis.feature.settings.ProviderEditScreen
import com.jarvis.feature.settings.ProvidersListScreen
import com.jarvis.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate so the system splash shows during cold start.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Edge-to-edge: system-bar contrast follows the canvas.
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JarvisNavHost()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun JarvisNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
    ) {
        composable(Routes.CHAT) {
            ChatRoute(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenVoiceMode = { navController.navigate(Routes.VOICE_MODE) },
            )
        }
        composable(Routes.VOICE_MODE) { backStackEntry ->
            // Voice mode shares the chat destination's ChatViewModel so recording and
            // streaming state remain continuous between the two screens.
            val chatEntry = remember(backStackEntry) { navController.getBackStackEntry(Routes.CHAT) }
            VoiceModeRoute(
                onEnd = { navController.popBackStack() },
                viewModel = hiltViewModel(chatEntry),
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS_LIST) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PROVIDERS_LIST) {
            ProvidersListScreen(
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
