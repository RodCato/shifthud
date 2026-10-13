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
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import androidx.work.WorkManager
import com.shifthud.ShiftHudApplication
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.*

class ShiftHudWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, NORMAL, EXPANDED, TALL, LARGE, HUGE))
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
        val initialSessions=app.repository.sessions.first()
        val initialPlanning=app.preferences.weeklyPlannerSettings.first()
        val initialWeekly=app.preferences.weeklyTargetSettings.first()
        val initialNow=Instant.now()
        val initialState = state(initial.schedule, initial.session, threshold, initialNow)
            .withMetrics(initialSessions,initial.schedule,initialPlanning,initialWeekly,initialNow,ZoneId.systemDefault())
        val initialItems = app.quickFind.items.first()
        val initialGuide = app.quickFind.guide.first()
        provideContent {
            // Observe during Glance's finite composition session: updateAll alone does not restart it.
            val updates = remember {
                val source=combine(app.repository.schedule,app.repository.sessions,app.preferences.lunchThresholdMinutes,app.widgetRefresh.now) { schedule,sessions,limit,now ->
                    WidgetSource(schedule,sessions,limit,now)
                }
                combine(source,app.preferences.weeklyPlannerSettings,app.preferences.weeklyTargetSettings) { source,planning,weekly ->
                    val latest=source.sessions.sortedWith(compareBy<com.shifthud.domain.model.WorkSession> { it.clockOut!=null }.thenByDescending { it.clockOut ?: it.clockIn }.thenByDescending { it.id }).firstOrNull()
                    state(source.schedule,latest,source.limit,source.now).withMetrics(source.sessions,source.schedule,planning,weekly,source.now,ZoneId.systemDefault())
                }
            }
            val data by updates.collectAsState(initialState)
            val items by app.quickFind.items.collectAsState(initialItems)
            val guide by app.quickFind.guide.collectAsState(initialGuide)
            val restoring by app.backups.busy.collectAsState()
            if(restoring)Text("ShiftHUD · Restoring data",style=TextStyle(color=ColorProvider(Color.White)))
            else WidgetContent(data, context, items, guide)
        }
    }
    companion object {
        val LARGE = DpSize(320.dp, 480.dp)
        val HUGE = DpSize(320.dp, 600.dp)
        val COMPACT = DpSize(180.dp, 200.dp)
        val NORMAL = DpSize(280.dp, 240.dp)
        val TALL = DpSize(320.dp, 360.dp)
        val EXPANDED = DpSize(320.dp, 300.dp)
    }
}

private data class WidgetSource(val schedule:List<com.shifthud.domain.model.ScheduledShift>,val sessions:List<com.shifthud.domain.model.WorkSession>,val limit:Int,val now:Instant)

private fun openApp(context: Context, destination: String): Action = actionStartActivity(widgetDestinationIntent(context, destination))

@Composable
internal fun WidgetContent(data: WidgetState, context: Context, items: List<com.shifthud.domain.model.QuickFindItem>, guide: List<com.shifthud.domain.model.AisleGuideEntry>) {
    val size = LocalSize.current
    val normal = size.width >= 280.dp && size.height >= 240.dp
    val expanded = size.width >= 320.dp && size.height >= 300.dp
    val references = widgetReferences(items, guide, size.width.value, size.height.value, data.completedLunch != null)
    val hasReferences = references.favorites.isNotEmpty() || references.guide.isNotEmpty()
    val white = ColorProvider(Color(0xFFF1F5EF))
    val muted = ColorProvider(Color(0xFFBACBBE))
    val primary = data.command?.let { widgetAction(it) } ?: openApp(context, data.destination)
    val fontScale=context.resources.configuration.fontScale
    val roomy=normal && (fontScale<=1.3f || size.height>=600.dp)
    Column(GlanceModifier.fillMaxSize().background(Color(0xFF15221A)).padding(12.dp).appWidgetBackground()) {
        val lunch=data.lunchMetric
        if(lunch!=null) {
            val timerSize=if(fontScale>1.3f)24f else if(size.height>=480.dp)48f else if(normal)36f else 28f
            Text("ON LUNCH",modifier=GlanceModifier.clickable(openApp(context,"Dashboard")),style=TextStyle(color=white,fontSize=if(normal)14.sp else 12.sp,fontWeight=FontWeight.Bold),maxLines=1)
            val timer=android.widget.RemoteViews(context.packageName,com.shifthud.R.layout.widget_lunch_clock).apply {
                setChronometer(com.shifthud.R.id.widget_lunch_clock,android.os.SystemClock.elapsedRealtime()-Duration.between(lunch.startedAt,Instant.now()).toMillis().coerceAtLeast(0),null,true)
                setTextColor(com.shifthud.R.id.widget_lunch_clock,lunch.tone.argb.toInt())
                setTextViewTextSize(com.shifthud.R.id.widget_lunch_clock,android.util.TypedValue.COMPLEX_UNIT_SP,timerSize)
            }
            AndroidRemoteViews(timer,GlanceModifier.fillMaxWidth().height((timerSize*fontScale*1.3f).dp))
            val statusText=if(normal)lunch.label else when(lunch.tone) { WidgetTone.GREEN->"Minimum met";WidgetTone.BLUE->"Planned met";else->lunch.label }
            Text(statusText,modifier=GlanceModifier.semantics { contentDescription=lunch.label },style=TextStyle(color=ColorProvider(Color(lunch.tone.argb)),fontSize=if(normal)14.sp else 12.sp),maxLines=if(normal)2 else 1)
        } else {
            Text(data.headline,modifier=GlanceModifier.fillMaxWidth().clickable(openApp(context,"Dashboard")),style=TextStyle(color=white,fontSize=if(normal)24.sp else 18.sp,fontWeight=FontWeight.Bold),maxLines=2)
        }
        if(roomy) {
            WidgetPaid(data.todayPaid,white,muted)
        }
        // Secondary information may scroll in small/large-font hosts. Primary timer and action
        // stay outside the collection, so no action depends on scrolling through references.
        androidx.glance.appwidget.lazy.LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
        item {
        Column(GlanceModifier.fillMaxWidth()) {
        if(data.lunchMetric==null) Text(data.detailForSize(normal),style=TextStyle(color=muted,fontSize=12.sp),maxLines=if(data.completedLunch!=null)3 else 2)
        if(normal && !roomy) WidgetPaid(data.todayPaid,white,muted)
        if(normal) data.scheduledOut?.let { Text(it,style=TextStyle(color=muted,fontSize=12.sp),maxLines=2) }
        if(expanded && !hasReferences) data.extra?.let { Text(it,style=TextStyle(color=muted,fontSize=14.sp),maxLines=2) }
        if(expanded) data.weekly?.let { weekly ->
            val color=ColorProvider(Color(weekly.tone.argb))
            Text("WEEKLY · ${weekly.paid} / ${weekly.target}",modifier=GlanceModifier.padding(top=8.dp),style=TextStyle(color=white,fontSize=16.sp,fontWeight=FontWeight.Bold),maxLines=2)
            LinearProgressIndicator(progress=weekly.fraction,modifier=GlanceModifier.fillMaxWidth().height(5.dp).padding(vertical=2.dp),color=color,backgroundColor=ColorProvider(Color(0xFF34473A)))
            Text(weekly.detail,style=TextStyle(color=color,fontSize=12.sp),maxLines=2)
        }

        if (hasReferences) {
            Column(GlanceModifier.fillMaxWidth().padding(top = 8.dp).clickable(openApp(context, QUICK_FIND_DESTINATION))) {
                if (references.favorites.isNotEmpty()) { Column {
                    Text("★ FAVORITES" + if(references.moreFavorites>0) " · +${references.moreFavorites} more" else "", style=TextStyle(color=muted,fontSize=11.sp,fontWeight=FontWeight.Bold),maxLines=1)
                    references.favorites.forEach { Text(it,style=TextStyle(color=white,fontSize=12.sp),maxLines=1) }
                } }
                if (references.guide.isNotEmpty()) { Column {
                    Text("AISLE GUIDE" + if(references.moreGuide>0) " · +${references.moreGuide} more" else "",modifier=GlanceModifier.padding(top=if (references.favorites.isNotEmpty()) 6.dp else 0.dp),style=TextStyle(color=muted,fontSize=11.sp,fontWeight=FontWeight.Bold),maxLines=1)
                    references.guide.forEachIndexed { index, entry -> Column {
                        if (index > 0) {
                            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Color(0xFF34473A))) {}
                        }
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(entry.location,
                                modifier = GlanceModifier.width(if (entry.location.toBigIntegerOrNull() != null) 32.dp else 72.dp).padding(end = 8.dp),
                                style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                            Text(entry.categories, modifier = GlanceModifier.defaultWeight(),
                                style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 2)
                        }
                    } }
                } }
            }
        }
        Text(data.updated+" · ↻",modifier=GlanceModifier.fillMaxWidth().padding(top=6.dp).clickable(widgetAction(WidgetCommand(WidgetOperation.REFRESH,0,LocalDate.now()))),style=TextStyle(color=muted,fontSize=12.sp),maxLines=1)
        }
        }
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(data.actionLabel, onClick = primary, modifier = GlanceModifier.defaultWeight().height(48.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color(0xFF9DD5AA)), contentColor = ColorProvider(Color(0xFF12331D))))
            if (showsQuickFind(size.width.value, size.height.value)) {
                Spacer(GlanceModifier.width(6.dp))
                Column(GlanceModifier.width(72.dp).height(if(fontScale>1.3f)60.dp else 48.dp).clickable(openApp(context, QUICK_FIND_DESTINATION)),
                    verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("QUICK FIND", style = TextStyle(color = white, fontSize = (if(fontScale>1.3f)10 else 12).sp, fontWeight = FontWeight.Bold), maxLines = 2)
                }
            }
        }
        if (size.height>=480.dp && data.status == WidgetStatus.WORKING) {
            Text("Open app to clock out ›", modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp).clickable(openApp(context, "Dashboard")),
                style = TextStyle(color = muted, fontSize = 14.sp), maxLines = 1)
        }
    }
}

@Composable private fun WidgetPaid(paid:String?,white:ColorProvider,muted:ColorProvider) {
    Column(GlanceModifier.fillMaxWidth().padding(top=6.dp)) {
        Text("TODAY’S PAID",style=TextStyle(color=muted,fontSize=12.sp,fontWeight=FontWeight.Bold),maxLines=1)
        Text(paid ?: "—",style=TextStyle(color=white,fontSize=24.sp,fontWeight=FontWeight.Bold),maxLines=1)
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
