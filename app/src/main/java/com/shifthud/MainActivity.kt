package com.shifthud

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.shifthud.service.requiresActiveRefresh
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
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
    private var explainNotifications by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onStart() {
        super.onStart()
        com.shifthud.notification.upcoming.UpcomingReminders.createChannel(this)
        lifecycleScope.launch { (application as ShiftHudApplication).upcoming.reschedule() }
        // Visible-activity recovery is permitted even when Android denied a background start.
        lifecycleScope.launch { (application as ShiftHudApplication).widgetRefresh.refresh() }
    }
    private fun requestActiveNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prompts = getSharedPreferences("permission_prompts", MODE_PRIVATE)
        if (!prompts.getBoolean("active_notification_requested", false)) {
            prompts.edit { putBoolean("active_notification_requested", true) }
            explainNotifications = true
        }
    }
    private var quickFindFocusRequest by mutableIntStateOf(0)
    private var widgetDestination by mutableStateOf<String?>(null)
    companion object { const val DESTINATION = "com.shifthud.widget.DESTINATION" }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetDestination = intent.getStringExtra(DESTINATION)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("quick_find_focus", quickFindFocusRequest)
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quickFindFocusRequest = savedInstanceState?.getInt("quick_find_focus") ?: 0
        widgetDestination = if (savedInstanceState == null) intent.getStringExtra(DESTINATION) else null
        enableEdgeToEdge()
        setContent {
            ShiftHudTheme {
                if (explainNotifications) AlertDialog(
                    onDismissRequest = { explainNotifications = false },
                    title = { Text("Shift notifications") },
                    text = { Text("Allow notifications to see ongoing shift progress and your personal lunch reminders. Tracking continues if you decline. You can change this in Settings.") },
                    confirmButton = { TextButton(onClick = { explainNotifications = false; if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Continue") } },
                    dismissButton = { TextButton(onClick = { explainNotifications = false }) { Text("Not now") } })
                val vm: ShiftViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = ShiftViewModel(application as ShiftHudApplication) as T
                })
                val data by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(data.session?.state) {
                    if (requiresActiveRefresh(data.session?.state)) requestActiveNotificationPermission()
                }
                val busy by vm.busy.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                LaunchedEffect(widgetDestination) {
                    widgetDestination?.takeIf { it == "Schedule" || it == "Dashboard" || it == "Settings" || it == com.shifthud.widget.QUICK_FIND_DESTINATION }?.let { destination ->
                        if (destination == com.shifthud.widget.QUICK_FIND_DESTINATION) quickFindFocusRequest++
                        nav.navigate(destination) { popUpTo(nav.graph.startDestinationId); launchSingleTop = true }
                    }
                    widgetDestination = null
                }
                val entry by nav.currentBackStackEntryAsState()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); vm.clearError() } }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                    NavigationBar {
                        listOf("Dashboard", "Schedule", "Quick Find", "Settings").forEach { destination ->
                            NavigationBarItem(selected = (entry?.destination?.route ?: "Dashboard").let { it == destination || (it == "Analytics" && destination == "Dashboard") }, onClick = { if (destination == "Quick Find") quickFindFocusRequest++; nav.selectTab(destination) }, icon = { if (destination == "Quick Find") Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_search), contentDescription = null) else Text(destination.take(1)) }, label = { Text(destination) })
                        }
                    }
                }) { padding ->
                    NavHost(nav, startDestination = "Dashboard", modifier = Modifier.padding(padding)) {
                        composable("Dashboard") { DashboardScreen(data, vm, busy) { nav.navigate("Analytics") { launchSingleTop = true } } }
                        composable("Analytics") {
                            val analytics: com.shifthud.ui.analytics.AnalyticsViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T =
                                    com.shifthud.ui.analytics.AnalyticsViewModel(application as ShiftHudApplication, extras.createSavedStateHandle()) as T
                            })
                            com.shifthud.ui.analytics.AnalyticsScreen(analytics, vm, data, busy) { nav.popBackStack() }
                        }
                        composable("Schedule") { ScheduleScreen(data, vm, busy) }
                        composable("Quick Find") {
                            val quickVm: com.shifthud.ui.quickfind.QuickFindViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T = com.shifthud.ui.quickfind.QuickFindViewModel((application as ShiftHudApplication).quickFind) as T
                            })
                            com.shifthud.ui.quickfind.QuickFindScreen(quickVm, quickFindFocusRequest)
                        }
                        composable("Settings") { SettingsScreen(data, vm, busy) }
                    }
                }
            }
        }
    }
}
