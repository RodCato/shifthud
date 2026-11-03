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
import kotlinx.coroutines.flow.catch
import java.time.*

class ShiftHudWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
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
        val initialDate=initialNow.atZone(ZoneId.systemDefault()).toLocalDate()
        val initialFrozen=try {
            app.frozenTasks.openDaily(initialDate,select=false,notify=false)
            widgetFrozenState(app.frozenTasks.tasks.first(),app.frozenTasks.floats.first(),initialDate)
        } catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(_:Exception) {WidgetFrozenState(initialDate,error="Tasks unavailable · open Tasks") }
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
            val frozenUpdates=remember {
                combine(app.frozenTasks.tasks,app.frozenTasks.floats,app.widgetRefresh.now) {tasks,counts,now->
                    widgetFrozenState(tasks,counts,now.atZone(ZoneId.systemDefault()).toLocalDate())
                }.catch {emit(WidgetFrozenState(LocalDate.now(),error="Tasks unavailable · open Tasks"))}
            }
            val frozen by frozenUpdates.collectAsState(initialFrozen)
            LaunchedEffect(frozen.date) {
                try {app.frozenTasks.openDaily(frozen.date,select=false,notify=false)}
                catch(e:kotlinx.coroutines.CancellationException) {throw e}
                catch(_:Exception) { /* Keep the shift area available; next redraw retries. */ }
            }
            val restoring by app.backups.busy.collectAsState()
            if(restoring)Text("ShiftHUD · Restoring data",style=TextStyle(color=ColorProvider(Color.White)))
            else WidgetContent(data, context, frozen)
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
internal fun WidgetContent(data: WidgetState, context: Context, frozen:WidgetFrozenState) {
    val size = LocalSize.current
    val normal = size.width >= 280.dp && size.height >= 240.dp
    val expanded = size.width >= 320.dp && size.height >= 300.dp
    val white = ColorProvider(Color(0xFFF1F5EF))
    val muted = ColorProvider(Color(0xFFBACBBE))
    val primary = data.command?.let { widgetAction(it) } ?: openApp(context, data.destination)
    val fontScale=context.resources.configuration.fontScale
    val pinnedCounter=expanded
    val roomy=normal && (!expanded || size.height>=480.dp) && (fontScale<=1.3f || size.height>=600.dp)
    Column(GlanceModifier.fillMaxSize().background(Color(0xFF15221A)).padding(12.dp).appWidgetBackground()) {
        val header: @Composable () -> Unit = {
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
        }
        if(!expanded) header()
        // Secondary information may scroll in small/large-font hosts. All actions remain fixed.
        // Checklist rows are budgeted against actual host dimensions, including wrapped titles.
        androidx.glance.appwidget.lazy.LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
        item {
        Column(GlanceModifier.fillMaxWidth()) {
        if(expanded) Column(GlanceModifier.fillMaxWidth()) { header() }
        if(data.lunchMetric==null) Text(data.detailForSize(normal),style=TextStyle(color=muted,fontSize=12.sp),maxLines=if(data.completedLunch!=null)3 else 2)
        if(normal && !roomy) WidgetPaid(data.todayPaid,white,muted)
        if(normal) data.scheduledOut?.let { Text(it,style=TextStyle(color=muted,fontSize=12.sp),maxLines=2) }
        if(expanded) data.extra?.let { Text(it,style=TextStyle(color=muted,fontSize=14.sp),maxLines=2) }
        if(expanded) data.weekly?.let { weekly ->
            val color=ColorProvider(Color(weekly.tone.argb))
            Text("WEEKLY · ${weekly.paid} / ${weekly.target}",modifier=GlanceModifier.padding(top=8.dp),style=TextStyle(color=white,fontSize=16.sp,fontWeight=FontWeight.Bold),maxLines=2)
            LinearProgressIndicator(progress=weekly.fraction,modifier=GlanceModifier.fillMaxWidth().height(5.dp).padding(vertical=2.dp),color=color,backgroundColor=ColorProvider(Color(0xFF34473A)))
            Text(weekly.detail,style=TextStyle(color=color,fontSize=12.sp),maxLines=2)
        }

        if(expanded) {
            Column(GlanceModifier.fillMaxWidth().padding(top=8.dp).clickable(openApp(context,TASKS_DESTINATION))) {
                Text("TODAY'S FROZEN TASKS",style=TextStyle(color=muted,fontSize=11.sp,fontWeight=FontWeight.Bold),maxLines=2)
                Text(frozen.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d",context.resources.configuration.locales[0])),style=TextStyle(color=muted,fontSize=11.sp),maxLines=1)
                Text(frozen.summary,style=TextStyle(color=white,fontSize=14.sp,fontWeight=FontWeight.Bold),maxLines=2)
                val visible=checklistCapacity(context,size,data,frozen)
                // Glance RemoteViews columns support at most ten direct children.
                // Group rows so large checklists are not silently dropped by translation.
                Column(GlanceModifier.fillMaxWidth()) {
                frozen.tasks.take(visible).chunked(10).forEach { group ->
                Column(GlanceModifier.fillMaxWidth()) {
                group.forEach {task->
                    Text(task.label,modifier=GlanceModifier.fillMaxWidth().padding(vertical=2.dp)
                        .clickable(openApp(context,TASKS_DESTINATION))
                        .semantics {contentDescription="${if(task.completed) "Completed" else "Unfinished"}: ${task.title}"},
                        style=TextStyle(color=if(task.completed)muted else white,fontSize=12.sp),maxLines=Int.MAX_VALUE)
                }
                }
                }
                }
                if(visible<frozen.tasks.size) Text("+${frozen.tasks.size-visible} more tasks — Open Tasks",
                    modifier=GlanceModifier.fillMaxWidth().clickable(openApp(context,TASKS_DESTINATION)),
                    style=TextStyle(color=white,fontSize=12.sp,fontWeight=FontWeight.Bold),maxLines=2)
            }
            if(!pinnedCounter) WidgetFloatCounter(frozen,white,muted)
        }
        Text(data.updated+" · ↻",modifier=GlanceModifier.fillMaxWidth().padding(top=6.dp).clickable(widgetAction(WidgetCommand(WidgetOperation.REFRESH,0,LocalDate.now()))),style=TextStyle(color=muted,fontSize=12.sp),maxLines=1)
        }
        }
        }
        if(pinnedCounter) WidgetFloatCounter(frozen,white,muted)
        if(!expanded || data.actionLabel!="OPEN APP") Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(data.actionLabel, onClick = primary, modifier = GlanceModifier.defaultWeight().height(48.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color(0xFF9DD5AA)), contentColor = ColorProvider(Color(0xFF12331D))))
            if(!expanded && showsQuickFind(size.width.value,size.height.value)) {
                Spacer(GlanceModifier.width(6.dp))
                Column(GlanceModifier.width(72.dp).height(if(fontScale>1.3f)60.dp else 48.dp).clickable(openApp(context, QUICK_FIND_DESTINATION)),
                    verticalAlignment=Alignment.CenterVertically,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("QUICK FIND",style=TextStyle(color=white,fontSize=(if(fontScale>1.3f)10 else 12).sp,fontWeight=FontWeight.Bold),maxLines=2)
                }
            }
        }
        if(expanded) Row(GlanceModifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            listOf("OPEN APP" to "Dashboard","TASKS" to TASKS_DESTINATION,"QUICK FIND" to QUICK_FIND_DESTINATION).forEach {(label,destination)->
                Column(GlanceModifier.defaultWeight().height(if(fontScale>1.3f)60.dp else 48.dp).clickable(openApp(context,destination)),
                    verticalAlignment=Alignment.CenterVertically,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(label,style=TextStyle(color=white,fontSize=(if(fontScale>1.3f)10 else 11).sp,fontWeight=FontWeight.Bold),maxLines=2)
                }
            }
        }
    }
}

@Composable private fun WidgetFloatCounter(frozen:WidgetFrozenState,white:ColorProvider,muted:ColorProvider) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text("FLOATS TODAY",style=TextStyle(color=muted,fontSize=11.sp,fontWeight=FontWeight.Bold),maxLines=2)
            Text(frozen.floatCount?.toString() ?: "—",style=TextStyle(color=white,fontSize=24.sp,fontWeight=FontWeight.Bold),maxLines=1)
        }
        if(frozen.floatCount!=null) {
            Button("−1",onClick=floatWidgetAction(-1),modifier=GlanceModifier.width(52.dp).height(48.dp))
            Spacer(GlanceModifier.width(6.dp))
            Button("+1 FLOAT",onClick=floatWidgetAction(1),modifier=GlanceModifier.width(120.dp).height(48.dp))
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

/** Measure wrapped text using the host density and accessibility font scale. The remaining
 * height belongs to task rows; reserve all existing shift details and fixed controls first. */
private fun checklistCapacity(context:Context,size:DpSize,data:WidgetState,frozen:WidgetFrozenState):Int {
    val metrics=context.resources.displayMetrics
    val scale=context.resources.configuration.fontScale
    val width=((size.width.value-24)*metrics.density).toInt().coerceAtLeast(1)
    fun text(value:String?,sp:Int,lines:Int=Int.MAX_VALUE):Float {
        if(value==null)return 0f
        val paint=android.text.TextPaint().apply {textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,sp.toFloat(),metrics)}
        val layout=android.text.StaticLayout.Builder.obtain(value,0,value.length,paint,width)
            .setIncludePad(true).setMaxLines(lines).build()
        return layout.height/metrics.density+2 // conservative rounding / Glance text padding
    }
    val lunch=data.lunchMetric
    val header=if(lunch==null)text(data.headline,24,2) else {
        val timer=if(scale>1.3f)24f else if(size.height>=480.dp)48f else 36f
        text("ON LUNCH",14,1)+timer*scale*1.3f+text(lunch.label,14,2)
    }
    val paid=6+text("TODAY’S PAID",12,1)+text(data.todayPaid ?: "—",24,1)
    val footer=(if(scale>1.3f)60 else 48)+
        maxOf(48f,text("FLOATS TODAY",11,2)+text(frozen.floatCount?.toString() ?: "—",24,1))+12+
        (if(data.actionLabel!="OPEN APP")48 else 0)
    val details=(if(lunch==null)text(data.detailForSize(true),12,if(data.completedLunch!=null)3 else 2) else 0f)+
        text(data.scheduledOut,12,2)+text(data.extra,14,2)+
        (data.weekly?.let {8+text("WEEKLY · ${it.paid} / ${it.target}",16,2)+9+text(it.detail,12,2)} ?: 0f)
    val heading=8+text("TODAY'S FROZEN TASKS",11,2)+text(frozen.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d",context.resources.configuration.locales[0])),11,1)+text(frozen.summary,14,2)
    val available=size.height.value-24-header-paid-footer-details-heading-6-text(data.updated+" · ↻",12,1)-8
    return visibleWidgetTaskCount(frozen.tasks.map {text(it.label,12)+4},available,
        text("+${frozen.tasks.size} more tasks — Open Tasks",12,2))
}
