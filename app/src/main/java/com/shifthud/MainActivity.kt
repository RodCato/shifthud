package com.shifthud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.shifthud.ui.*
import com.shifthud.ui.dashboard.DashboardScreen
import com.shifthud.ui.schedule.ScheduleScreen
import com.shifthud.ui.settings.SettingsScreen
import com.shifthud.ui.theme.ShiftHudTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShiftHudTheme {
                val vm: ShiftViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = ShiftViewModel(application as ShiftHudApplication) as T
                })
                val data by vm.state.collectAsStateWithLifecycle()
                val busy by vm.busy.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); vm.clearError() } }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                    NavigationBar {
                        listOf("Dashboard", "Schedule", "Settings").forEach { destination ->
                            NavigationBarItem(selected = (entry?.destination?.route ?: "Dashboard") == destination, onClick = { nav.navigate(destination) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Text(destination.take(1)) }, label = { Text(destination) })
                        }
                    }
                }) { padding ->
                    NavHost(nav, startDestination = "Dashboard", modifier = Modifier.padding(padding)) {
                        composable("Dashboard") { DashboardScreen(data, vm, busy) }
                        composable("Schedule") { ScheduleScreen(data, vm, busy) }
                        composable("Settings") { SettingsScreen(data, vm, busy) }
                    }
                }
            }
        }
    }
}
