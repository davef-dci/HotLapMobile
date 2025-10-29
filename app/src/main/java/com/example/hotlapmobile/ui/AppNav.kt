package com.example.hotlapmobile.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.hotlapmobile.ui.calibration.CalibrateScreen
import com.example.hotlapmobile.ui.permissions.EnsureLocationPermission

object Routes {
    const val Splash = "splash"
    const val Menu = "menu"
    const val Calibrate = "calibrate"
    const val SelectTrack = "select_track"
    const val Settings = "settings"
    const val Race = "race"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Splash) {
        composable(Routes.Splash) {
            SplashScreen(onFinished = {
                nav.navigate(Routes.Menu) { popUpTo(Routes.Splash) { inclusive = true } }
            })
        }

        composable(Routes.Menu) {
            MainMenu(
                onCalibrate = { nav.navigate(Routes.Calibrate) },
                onSelectTrack = { nav.navigate(Routes.SelectTrack) },
                onSettings = { nav.navigate(Routes.Settings) },
                onRace = { nav.navigate(Routes.Race) }
            )
        }

        composable(Routes.Calibrate) {
            CalibrateScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.Settings) {
            SettingsScreen(
                onBack = { nav.popBackStack() }
            )
        }

        composable(Routes.Race) {
            EnsureLocationPermission {
                RacingScreen()
            }
        }

        composable(Routes.SelectTrack) {
            SelectTrackScreen(
                onBack = { nav.popBackStack() }
            )
        }
    }
}
