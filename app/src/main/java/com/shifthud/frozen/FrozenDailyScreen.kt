package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import com.shifthud.domain.model.ScheduledShift
import com.shifthud.ui.schedule.ScheduleDatePicker
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable fun FrozenDailyScreen(vm:FrozenTasksViewModel,openCountRoute:()->Unit,openManual:()->Unit,
    currentDate:()->LocalDate={LocalDate.now()},schedule:List<ScheduledShift> = emptyList(),scheduleLoaded:Boolean=true) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val trucks by vm.truckWorkflows.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf(currentDate()) }
    // Fresh tab visits start today. Browsing is local to this visit.
    var browsing by remember { mutableStateOf<String?>(null) }
    var weekView by remember { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while(true) { val date=currentDate();if(date!=today) {today=date;browsing=null;weekView=false};delay(1000) }
    } }
    val date=browsing?.let(LocalDate::parse) ?: today
    val preview=date>today
    var loaded by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(date,today,weekView) {
        loaded=null
        if(!preview && !weekView) vm.openDaily(date)
        loaded=date
    }
    val rows=tasks?.let {frozenPlanningRows(it,date,today)}
    val truck=trucks.singleOrNull { it.date==date }
    val listState=key(date) { rememberLazyListState() }
    if(importing) {
        androidx.activity.compose.BackHandler { importing=false }
        EndcapImportScreen(vm,date) {importing=false}
    } else Column(Modifier.fillMaxSize()) {
        FrozenDayNavigation(date,today,{browsing=it.takeUnless {it==today}?.toString();weekView=false},{weekView=true})
        Box(Modifier.weight(1f)) {
            if(weekView) {
                androidx.activity.compose.BackHandler {weekView=false}
                if(tasks==null) Text(error ?: "Loading tasks…")
                else FrozenWeekView(frozenPlanningWeek(tasks.orEmpty(),schedule,date,today),scheduleLoaded,
                    {browsing=it.takeUnless {it==today}?.toString();weekView=false},
                    {browsing=date.minusWeeks(1).toString()},{browsing=date.plusWeeks(1).toString()},{weekView=false})
            } else if(preview) {
                if(rows==null) Text(error ?: "Loading preview…")
                else FrozenFuturePreview(rows,date,dailyFrozenTemplate(date).isNotEmpty())
            } else FrozenTasksContent(if(loaded==date) rows?.map { it.task } else null,busy,error,vm::clearError,{},
                { id,state->vm.dailyState(date,id,state) },vm::save,workflow=dailyWorkflowId(date),dailyDate=date,showDailyDate=false,
                resolveByOther={vm.resolveDailyByOther(date,it)},dismissTask={vm.dismissDaily(date,it)},
                sources=rows.orEmpty().associate { it.task.id to it.sources },listState=listState,
                controls={
                    TextButton(onClick={tools=!tools}) {Text("Workflow tools & history",Modifier.fillMaxWidth())}
                    if(tools) {
                        Text("Viewing tasks does not record a work assignment.",style=MaterialTheme.typography.bodySmall)
                        Text(FROZEN_REFERENCE_NOTE,style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={choosing=true}) {Text("Browse another date",Modifier.fillMaxWidth())}
                        TextButton(onClick=openCountRoute) {Text("Count Route",Modifier.fillMaxWidth())}
                        TextButton(onClick={importing=true}) {Text("Import Endcap Sheet",Modifier.fillMaxWidth())}
                        TextButton(onClick=openManual) {Text("Manual workflows & saved history",Modifier.fillMaxWidth())}
                    }
                    if(dailyFrozenTemplate(date).isEmpty()) Text("No weekday template is defined. Saved tasks and outstanding carryovers are shown.",style=MaterialTheme.typography.bodySmall)
                },removeProduct=vm::removeProduct,editProduct=vm::editProduct,chooseVariety=vm::chooseVariety,
                truck=truck,truckArrival={vm.truckArrival(date,it)})
        }
    }
    if(choosing) ScheduleDatePicker(date,{choosing=false}) {browsing=it.toString();weekView=false;choosing=false}
}
