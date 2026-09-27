package com.wavetalk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wavetalk.app.di.AppContainer
import com.wavetalk.app.presentation.onboarding.OnboardingScreen
import com.wavetalk.app.presentation.settings.SettingsScreen
import com.wavetalk.app.presentation.theme.WaveTalkTheme
import com.wavetalk.app.presentation.walkie.WalkieScreen
import com.wavetalk.app.presentation.walkie.WalkieViewModel
import com.wavetalk.app.settings.AppSettings

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as WaveTalkApp).container

        setContent {
            WaveTalkRoot(container)
        }
    }
}

@Composable
private fun WaveTalkRoot(container: AppContainer) {
    val settings by container.settingsRepository.settings.collectAsState(initial = null)
    val onboardedFlag by container.settingsRepository.onboarded.collectAsState(initial = null)
    var showSettings by remember { mutableStateOf(false) }

    val currentSettings = settings
    val isOnboarded = onboardedFlag
    if (currentSettings == null || isOnboarded == null) {
        // Splash still covering; render nothing yet.
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }

    WaveTalkTheme(themeMode = currentSettings.themeMode) {
        Scaffold { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialThemeColorBackground(),
            ) {
                if (!isOnboarded) {
                    OnboardingScreen(
                        container = container,
                        onDone = { /* onboarded flow flips automatically */ },
                    )
                } else {
                    val vm: WalkieViewModel = viewModel(factory = WalkieViewModel.Factory(container))

                    // Keep the readiness service in sync with the user setting.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    LaunchedEffect(currentSettings.backgroundReadyEnabled) {
                        if (currentSettings.backgroundReadyEnabled) {
                            WalkieService.start(context)
                        } else {
                            WalkieService.stop(context)
                        }
                    }

                    WalkieScreen(
                        viewModel = vm,
                        onOpenSettings = { showSettings = true },
                    )

                    AnimatedVisibility(
                        visible = showSettings,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        SettingsScreen(viewModel = vm, onBack = { showSettings = false })
                    }
                }
            }
        }
    }
}

/** Background color resolved from the current theme for the root surface. */
@Composable
private fun MaterialThemeColorBackground() = androidx.compose.material3.MaterialTheme.colorScheme.background
