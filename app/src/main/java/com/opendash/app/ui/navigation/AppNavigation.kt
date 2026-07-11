package com.opendash.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.opendash.app.ui.settings.SettingsScreen
import com.opendash.app.ui.settings.multiroom.SettingsSpeakerGroupsScreen
import com.opendash.app.ui.settings.providers.ProvidersScreen
import com.opendash.app.ui.settings.spotify.SpotifySettingsScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = AppRoute.Settings.route,
        modifier = modifier
    ) {
        composable(AppRoute.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSpeakerGroups = { navController.navigate(AppRoute.SpeakerGroups.route) },
                onOpenProviders = { navController.navigate(AppRoute.Providers.route) },
                onOpenSpotify = { navController.navigate(AppRoute.Spotify.route) }
            )
        }
        composable(AppRoute.SpeakerGroups.route) {
            SettingsSpeakerGroupsScreen(onBack = { navController.popBackStack() })
        }
        composable(AppRoute.Providers.route) {
            ProvidersScreen(onBack = { navController.popBackStack() })
        }
        composable(AppRoute.Spotify.route) {
            SpotifySettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
