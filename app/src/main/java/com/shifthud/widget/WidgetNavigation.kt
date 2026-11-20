package com.shifthud.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.shifthud.MainActivity

const val TASKS_DESTINATION = "Tasks"
const val QUICK_FIND_DESTINATION = "Quick Find"
fun widgetDestinationIntent(context: Context, destination: String): Intent = Intent(context, MainActivity::class.java).apply {
    data = "shifthud://widget/${destination.replace(" ", "-")}".toUri()
    putExtra(MainActivity.DESTINATION, destination)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
}
fun showsQuickFind(widthDp: Float, heightDp: Float) = widthDp >= 280 && heightDp >= 240

/** A widget Tasks entry must discard an already-open future preview and start today. */
fun androidx.navigation.NavHostController.openWidgetDestination(destination:String) {
    if(destination !in setOf("Dashboard","Schedule","Settings",QUICK_FIND_DESTINATION,TASKS_DESTINATION))return
    if(destination==TASKS_DESTINATION) popBackStack(graph.startDestinationId,false)
    navigate(destination) {popUpTo(graph.startDestinationId);launchSingleTop=true}
    if(destination==TASKS_DESTINATION) currentBackStackEntry?.savedStateHandle?.set("tasksOpenToday",true)
}
