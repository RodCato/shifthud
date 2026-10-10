package com.shifthud.ui

import androidx.navigation.NavHostController

fun NavHostController.selectTab(destination: String) {
    navigate(destination) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        // A saved stack associated with the start destination can contain a direct widget route.
        // Dashboard must return to the actual start screen, not restore that child stack.
        restoreState = destination != "Dashboard"
    }
}
