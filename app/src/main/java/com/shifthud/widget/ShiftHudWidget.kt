package com.shifthud.widget

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.work.WorkManager
import com.shifthud.ShiftHudApplication
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.*

class ShiftHudWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, NORMAL, EXPANDED))
    // Room + Preferences DataStore are authoritative. Glance stores no session or timer state.
    override val stateDefinition = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as ShiftHudApplication
        app.widgetRefresh.markNow()
        val initial = app.repository.snapshot()
        val threshold = app.preferences.lunchThresholdMinutes.first()
        app.widgetRefresh.configureWork()
        val factory = WidgetStateFactory(app.engine)
        fun state(schedule: List<com.shifthud.domain.model.ScheduledShift>, session: com.shifthud.domain.model.WorkSession?, limit: Int, now: Instant) =
            factory.create(schedule, session, limit, now, ZoneId.systemDefault(), context.resources.configuration.locales[0], android.text.format.DateFormat.is24HourFormat(context))
        val initialState = state(initial.schedule, initial.session, threshold, Instant.now())
        provideContent {
            // Observe during Glance's finite composition session: updateAll alone does not restart it.
            val updates = remember {
                combine(app.repository.schedule, app.repository.latestSession, app.preferences.lunchThresholdMinutes, app.widgetRefresh.now) { schedule, session, limit, now ->
                    state(schedule, session, limit, now)
                }
            }
            val data by updates.collectAsState(initialState)
            WidgetContent(data, context)
        }
    }
    companion object {
        val COMPACT = DpSize(180.dp, 200.dp)
        val NORMAL = DpSize(280.dp, 240.dp)
        val EXPANDED = DpSize(320.dp, 300.dp)
    }
}

private fun openApp(context: Context, destination: String): Action = actionStartActivity(widgetDestinationIntent(context, destination))

@Composable
private fun WidgetContent(data: WidgetState, context: Context) {
    val size = LocalSize.current
    val normal = size.width >= 280.dp && size.height >= 240.dp
    val expanded = size.width >= 320.dp && size.height >= 300.dp
    val white = ColorProvider(Color(0xFFF1F5EF))
    val muted = ColorProvider(Color(0xFFBACBBE))
    val primary = data.command?.let { widgetAction(it) } ?: openApp(context, data.destination)
    Column(GlanceModifier.fillMaxSize().background(Color(0xFF15221A)).padding(12.dp).appWidgetBackground()) {
        Text("SHIFT HUD  ›", modifier = GlanceModifier.fillMaxWidth().clickable(openApp(context, "Dashboard")),
            style = TextStyle(color = muted, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Spacer(GlanceModifier.height(6.dp))
        Text(data.headline, style = TextStyle(color = white, fontSize = if (normal) 20.sp else 16.sp, fontWeight = FontWeight.Bold), maxLines = 2)
        Spacer(GlanceModifier.height(4.dp))
        Text(data.detailForSize(normal), style = TextStyle(color = white, fontSize = if (!normal && data.completedLunch != null) 12.sp else 14.sp),
            maxLines = if (data.completedLunch != null) 3 else 2)
        if (normal) {
            data.scheduledOut?.let { Text(it, style = TextStyle(color = muted, fontSize = 14.sp), maxLines = 2) }
        }
        if (expanded) data.extra?.let { Text(it, style = TextStyle(color = muted, fontSize = 14.sp), maxLines = 2) }
        Spacer(GlanceModifier.defaultWeight())
        // Refresh is always user-driven here; periodic work may be deferred by Android.
        Text(data.updated + " · ↻", modifier = GlanceModifier.fillMaxWidth().clickable(widgetAction(WidgetCommand(WidgetOperation.REFRESH, 0, LocalDate.now()))),
            style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(data.actionLabel, onClick = primary, modifier = GlanceModifier.defaultWeight().height(48.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color(0xFF9DD5AA)), contentColor = ColorProvider(Color(0xFF12331D))))
            if (showsQuickFind(size.width.value, size.height.value)) {
                Spacer(GlanceModifier.width(6.dp))
                Column(GlanceModifier.width(72.dp).height(48.dp).clickable(openApp(context, QUICK_FIND_DESTINATION)),
                    verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("QUICK FIND", style = TextStyle(color = white, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                }
            }
        }
        if (expanded && data.status == WidgetStatus.WORKING) {
            Text("Open app to clock out ›", modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp).clickable(openApp(context, "Dashboard")),
                style = TextStyle(color = muted, fontSize = 14.sp), maxLines = 1)
        }
    }
}

private val OperationKey = ActionParameters.Key<String>("operation")
private val SessionKey = ActionParameters.Key<Long>("session")
private val DateKey = ActionParameters.Key<Long>("date")
private fun widgetAction(command: WidgetCommand): Action = actionRunCallback<ShiftWidgetAction>(
    actionParametersOf(OperationKey to command.operation.name, SessionKey to command.sessionId, DateKey to command.date.toEpochDay())
)

class ShiftWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as ShiftHudApplication
        val command = runCatching {
            WidgetCommand(WidgetOperation.valueOf(requireNotNull(parameters[OperationKey])), requireNotNull(parameters[SessionKey]), LocalDate.ofEpochDay(requireNotNull(parameters[DateKey])))
        }.getOrNull()
        if (command == null) { app.widgetRefresh.refresh(); return }
        WidgetActionExecutor(app.repository, app.engine, { app.widgetRefresh.refresh() }).execute(command)
    }
}

class ShiftHudWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShiftHudWidget()
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WidgetRefresh.WORK_NAME)
    }
}
