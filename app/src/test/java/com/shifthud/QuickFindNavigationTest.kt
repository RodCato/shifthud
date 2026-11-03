package com.shifthud

import android.app.Application
import android.content.Intent
import com.shifthud.widget.*
import com.shifthud.ui.selectTab
import androidx.activity.ComponentActivity
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class QuickFindNavigationTest {
    @Test fun dashboardReturnsToStartAfterDirectWidgetEntry() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val nav=NavHostController(activity.get())
            nav.setLifecycleOwner(activity.get())
            nav.setViewModelStore(activity.get().viewModelStore)
            nav.navigatorProvider.addNavigator(ComposeNavigator())
            nav.graph=nav.createGraph(startDestination="Dashboard") {
                composable("Dashboard") {};composable("Quick Find") {};composable("Schedule") {}
            }
            nav.navigate("Quick Find") {popUpTo(nav.graph.startDestinationId);launchSingleTop=true}
            assertEquals("Quick Find",nav.currentDestination?.route)
            nav.selectTab("Dashboard")
            assertEquals("Dashboard",nav.currentDestination?.route)
            nav.selectTab("Quick Find")
            assertEquals("Quick Find",nav.currentDestination?.route)
            nav.selectTab("Schedule")
            assertEquals("Schedule",nav.currentDestination?.route)
            nav.selectTab("Dashboard")
            assertEquals("Dashboard",nav.currentDestination?.route)
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun tasksTabRestoresSavedStateAndBackReturnsToDashboard() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val nav=NavHostController(activity.get())
            nav.setLifecycleOwner(activity.get());nav.setViewModelStore(activity.get().viewModelStore)
            nav.navigatorProvider.addNavigator(ComposeNavigator())
            nav.graph=nav.createGraph(startDestination="Dashboard") {
                listOf("Dashboard","Schedule","Tasks","Quick Find","Settings","FrozenCountRoute").forEach { route->composable(route) {} }
            }
            nav.selectTab("Tasks");nav.currentBackStackEntry!!.savedStateHandle["scroll-position"]=7
            nav.navigate("FrozenCountRoute");nav.selectTab("Schedule");nav.selectTab("Tasks")
            assertEquals("Tasks",nav.currentDestination?.route)
            assertEquals(7,nav.currentBackStackEntry!!.savedStateHandle.get<Int>("scroll-position"))
            nav.popBackStack();assertEquals("Dashboard",nav.currentDestination?.route)
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun widgetIntentTargetsMainQuickFindAndReusesActivity() {
        val intent=widgetDestinationIntent(RuntimeEnvironment.getApplication(),QUICK_FIND_DESTINATION)
        assertEquals(MainActivity::class.java.name,intent.component!!.className)
        assertEquals("Quick Find",intent.getStringExtra(MainActivity.DESTINATION))
        assertEquals("shifthud://widget/Quick-Find",intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
    @Test fun widgetTasksEntryReplacesAnExistingPreviewWhilePreservingOtherDestinations() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val nav=NavHostController(activity.get())
            nav.setLifecycleOwner(activity.get());nav.setViewModelStore(activity.get().viewModelStore)
            nav.navigatorProvider.addNavigator(ComposeNavigator())
            nav.graph=nav.createGraph(startDestination="Dashboard") {listOf("Dashboard","Tasks","Quick Find","Schedule","Settings").forEach {route->composable(route){}}}
            nav.navigate("Tasks");val old=nav.currentBackStackEntry!!
            old.savedStateHandle["preview-date"]="2026-11-06"
            nav.openWidgetDestination(TASKS_DESTINATION)
            assertEquals("Tasks",nav.currentDestination?.route);assertNotSame(old,nav.currentBackStackEntry)
            assertNull(nav.currentBackStackEntry!!.savedStateHandle.get<String>("preview-date"))
            nav.openWidgetDestination(QUICK_FIND_DESTINATION);assertEquals("Quick Find",nav.currentDestination?.route)
            nav.openWidgetDestination("Dashboard");assertEquals("Dashboard",nav.currentDestination?.route)
            val intent=widgetDestinationIntent(RuntimeEnvironment.getApplication(),TASKS_DESTINATION)
            assertEquals("Tasks",intent.getStringExtra(MainActivity.DESTINATION));assertEquals("shifthud://widget/Tasks",intent.dataString)
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun compactOmitsShortcutNormalAndExpandedIncludeIt() {
        assertFalse(showsQuickFind(180f,200f));assertTrue(showsQuickFind(280f,240f));assertTrue(showsQuickFind(320f,300f))
    }
    @Test fun existingDestinationsRemainDistinct() {
        val context=RuntimeEnvironment.getApplication()
        assertEquals("Dashboard",widgetDestinationIntent(context,"Dashboard").getStringExtra(MainActivity.DESTINATION))
        assertEquals("Schedule",widgetDestinationIntent(context,"Schedule").getStringExtra(MainActivity.DESTINATION))
        assertNotEquals(widgetDestinationIntent(context,"Dashboard").data,widgetDestinationIntent(context,QUICK_FIND_DESTINATION).data)
    }
}
