package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shifthud.ui.components.ExpandableSection
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun FrozenTasksScreen(vm:FrozenTasksViewModel,back:()->Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val selected by vm.selectedWorkflow.collectAsStateWithLifecycle()
    val trucks by vm.truckWorkflows.collectAsStateWithLifecycle()
    val truck=trucks.singleOrNull { isWednesdayWorkflow(selected) && it.date==frozenWorkflowDate(selected) }
    var importing by remember { mutableStateOf(false) }
    val visible=tasks?.let { frozenWorkflowTasks(it,selected) }
    if(importing && selected.isNotBlank() && !isWednesdayWorkflow(selected)) {
        EndcapImportScreen(vm,java.time.LocalDate.parse(selected)) { importing=false }
    } else FrozenTasksContent(visible,busy,error,vm::clearError,back,vm::state,vm::save,
        workflow=selected,controls={ FrozenWorkflowControls(tasks.orEmpty(),selected,busy,vm::start,vm::select,vm::startWednesday) { importing=true } },
        removeProduct=vm::removeProduct,editProduct=vm::editProduct,chooseVariety=vm::chooseVariety,truck=truck,truckArrival={ arrived->truck?.let { vm.truckArrival(it.date,arrived) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FrozenTasksContent(tasks:List<FrozenTask>?,busy:Boolean,error:String?,clearError:()->Unit,
    back:()->Unit,changeState:(String,FrozenState)->Unit,save:(String?,String,String,String,()->Unit)->Unit,
    workflow:String="",controls:@Composable ()->Unit={},removeProduct:(String)->Unit={},
    editProduct:(String,String,String,()->Unit)->Unit={_,_,_,_->},truck:FrozenTruckWorkflow?=null,truckArrival:(Boolean)->Unit={},chooseVariety:(String,EndcapProduct,Boolean)->Unit={_,_,_->}) {
    var carryOpen by rememberSaveable(workflow) { mutableStateOf(true) }
    var completedOpen by rememberSaveable(workflow) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FrozenTask?>(null) }
    var productEditing by remember { mutableStateOf<FrozenTask?>(null) }
    var removing by remember { mutableStateOf<FrozenTask?>(null) }
    val closedSections=remember(workflow) { mutableStateMapOf<String,Boolean>() }
    LaunchedEffect(workflow,truck?.arrival) {
        if(truck?.arrival!=null) { closedSections.remove("TRUCK RECEIVING");closedSections.remove("STOCKING") }
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    val locale=LocalConfiguration.current.locales[0]
    val date=DateTimeFormatter.ofPattern("EEE, MMM d, uuuu",locale)
    val groups=groupFrozenTasks(tasks.orEmpty())
    val dark=androidx.compose.foundation.isSystemInDarkTheme()
    val green=MaterialTheme.colorScheme.primary
    val amber=if(dark) Color(0xFFE5BF75) else Color(0xFF795500)
    val red=if(dark) Color(0xFFE5A0A0) else Color(0xFF9A3434)
    val blue=if(dark) Color(0xFFACCBE9) else Color(0xFF315E85)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)) {
        item {
            TextButton(onClick=back,modifier=Modifier.semantics { contentDescription="Back to Dashboard" }) { Text("Back",Modifier.fillMaxWidth()) }
            Text("Frozen Tasks",Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineMedium)
            Text(if(workflow.isBlank()) FROZEN_TITLE else if(isWednesdayWorkflow(workflow)) "Wednesday — Frozen Truck" else "Frozen — Tuesday Workflow",Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium)
            Text(if(workflow.isBlank()) "${FROZEN_MONDAY.format(date)} · 8:00 AM–5:00 PM" else frozenWorkflowDate(workflow).format(date),Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium)
            Text(if(workflow.isBlank()) "One-time checklist · carryovers stay here for your next Frozen shift" else "Frozen assignment · manually selected workflow",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
        }
        if(isWednesdayWorkflow(workflow)) item { FrozenTruckStatus(truck,busy,truckArrival) }
        item { controls() }
        item {
            if(tasks==null) Text(if(error==null) "Loading tasks…" else "Checklist unavailable. Reopen Frozen Tasks to retry.")
            else {
                Text("${groups.completed.size} of ${groups.total} completed · ${groups.remaining} remaining · ${groups.blocked} blocked",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={adding=true},enabled=!busy) { Text("Add task",Modifier.fillMaxWidth()) }
            }
            Text(FROZEN_REFERENCE_NOTE,Modifier.fillMaxWidth(),color=blue,style=MaterialTheme.typography.bodySmall)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick=clearError) { Text("Dismiss message") } }
        }
        val sections=if(workflow.isBlank()) listOf(
            Triple("ACTIVE TASKS",groups.active,true),
            Triple("CARRYOVERS",groups.carryovers,carryOpen),
            Triple("COMPLETED",groups.completed,completedOpen)
        ) else if(isWednesdayWorkflow(workflow)) listOf(Triple("CARRYOVERS",groups.carryovers,carryOpen)) +
            truckSectionOrder(truck?.arrival!=null).map { section ->
                Triple(section,groups.active.filter { it.section==section || (section=="BEFORE TRUCK" && it.section !in truckSectionOrder(false)) },closedSections[section]!=true)
            } + listOf(Triple("COMPLETED",groups.completed,completedOpen))
        else listOf(Triple("CARRYOVERS",groups.carryovers,carryOpen)) +
            listOf("MORNING TRANSITION","ENDCAP — PICK FOR LAYOUT","ENDCAP — BUILD LAYOUT","MIDSHIFT","END OF SHIFT — FINAL FILL").map { section ->
                Triple(section,groups.active.filter { it.section==section || (section=="MORNING TRANSITION" && it.section.isBlank()) },closedSections[section]!=true)
            } + listOf(Triple("COMPLETED",groups.completed,completedOpen))
        if(tasks!=null) sections.forEach { (label,rows,open) ->
            item(key="section-$label") {
                if(label=="ACTIVE TASKS") Text("$label (${rows.size})",Modifier.fillMaxWidth().padding(vertical=8.dp),style=MaterialTheme.typography.titleSmall)
                else FrozenGroupHeader("$label (${rows.size})",open) {
                    if(label=="CARRYOVERS") carryOpen=!carryOpen else if(label=="COMPLETED") completedOpen=!completedOpen else closedSections[label]=open
                }
            }
            if(open && rows.isEmpty()) item(key="empty-$label") { Text(if(label=="CARRYOVERS") "No carryover tasks." else if(label=="COMPLETED") "No completed tasks." else "No active tasks.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall) }
            if(open) items(rows,key={it.id}) { task ->
            val color=when(task.state) { FrozenState.COMPLETED->green;FrozenState.BLOCKED->red;FrozenState.IN_PROGRESS,FrozenState.CARRY_OVER->amber;else->MaterialTheme.colorScheme.onSurfaceVariant }
            ExpandableSection(task.title,task.state.label,leading={
                Checkbox(checked=task.state==FrozenState.COMPLETED,enabled=!busy,
                    onCheckedChange={changeState(task.id,if(it) FrozenState.COMPLETED else FrozenState.TODO)},
                    modifier=Modifier.semantics { contentDescription=if(task.state==FrozenState.COMPLETED) "Reopen ${task.title}" else "Complete ${task.title}" })
            },titleContent={
                Text(task.title,Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium,
                    color=if(task.state==FrozenState.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            },subtitleContent={
                Text(task.state.label,Modifier.fillMaxWidth(),color=color,style=MaterialTheme.typography.bodyMedium)
                if(task.state==FrozenState.CARRY_OVER) Text("From ${task.date.format(date)}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
            }) {
                if(task.state!=FrozenState.CARRY_OVER) Text("From ${task.date.format(date)}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
                if(task.family.isNotBlank()) {
                    Text("Eligible varieties · ${task.category}",Modifier.fillMaxWidth(),color=blue)
                    Text("Choose additional varieties as they fit. These choices do not complete Final Fill.",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
                    task.eligible.forEach { product -> Row(Modifier.fillMaxWidth()) {
                        Checkbox(endcapProductIdentity(product) in task.chosen,{chooseVariety(task.id,product,it)},enabled=!busy,
                            modifier=Modifier.semantics { contentDescription="Use variety ${product.name}" })
                        Text(product.name+if(product.sku.isBlank()) "" else " · ${product.sku}",Modifier.weight(1f))
                    } }
                }
                if(task.sku.isNotBlank()) Text("Item / SKU: ${task.sku}",Modifier.fillMaxWidth())
                if(task.notes.isNotBlank())Text(task.notes,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
                Text(task.description.ifBlank { "No reference notes." },Modifier.fillMaxWidth(),color=blue,style=MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(task.state!=FrozenState.COMPLETED) {
                        TextButton(onClick={changeState(task.id,FrozenState.IN_PROGRESS)},enabled=!busy && task.state!=FrozenState.IN_PROGRESS) { Text("Start task",Modifier.fillMaxWidth()) }
                        TextButton(onClick={changeState(task.id,FrozenState.BLOCKED)},enabled=!busy && task.state!=FrozenState.BLOCKED) { Text("Mark blocked",Modifier.fillMaxWidth()) }
                        TextButton(onClick={changeState(task.id,FrozenState.CARRY_OVER)},enabled=!busy && task.state!=FrozenState.CARRY_OVER) { Text("Carry to next Frozen shift",Modifier.fillMaxWidth()) }
                        if(task.state!=FrozenState.TODO) TextButton(onClick={changeState(task.id,FrozenState.TODO)},enabled=!busy) { Text(if(task.state==FrozenState.CARRY_OVER) "Remove from carryover" else "Return to do",Modifier.fillMaxWidth()) }
                    }
                    if(task.plan.isNotBlank()) {
                        if(task.family.isBlank()) TextButton(onClick={productEditing=task},enabled=!busy) { Text("Edit product / SKU") }
                        TextButton(onClick={removing=task},enabled=!busy) { Text("Remove product") }
                    }
                    TextButton(onClick={editing=task},enabled=!busy) { Text("Edit task / notes",Modifier.fillMaxWidth()) }
                }
                if(task.history.isNotEmpty())ExpandableSection("Task history","${task.history.size} state changes",subtitleContent={Text("${task.history.size} state changes",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)}) {
                    task.history.asReversed().forEach { event -> Text("${event.state.label} · ${event.at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a",locale))}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall) }
                }
            }
        }
        }
    }
    productEditing?.let { product ->
        var name by remember(product.id) { mutableStateOf(product.title) }
        var sku by remember(product.id) { mutableStateOf(product.sku) }
        AlertDialog(onDismissRequest={productEditing=null},title={Text("Edit product")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name,{name=it},label={Text("Product description")})
            OutlinedTextField(sku,{sku=it},label={Text("Item / SKU (optional)")})
            error?.let { Text(it) }
        }},confirmButton={TextButton(enabled=!busy && name.isNotBlank() && name.length<=160 && sku.length<=60,onClick={editProduct(product.id,name,sku) { productEditing=null }}) { Text("Save product") }},dismissButton={TextButton(onClick={productEditing=null}) { Text("Cancel") }})
    }
    removing?.let { product -> AlertDialog(onDismissRequest={removing=null},title={Text("Remove product?")},text={Text(product.title)},
        confirmButton={TextButton(enabled=!busy,onClick={removeProduct(product.id);removing=null}) { Text("Remove") }},dismissButton={TextButton(onClick={removing=null}) { Text("Cancel") }}) }
    if(editing!=null || adding) FrozenTaskEditor(editing,busy,error,dismiss={editing=null;adding=false},save={title,description,notes ->
        save(editing?.id,title,description,notes) { editing=null;adding=false }
    })
}

/** Stable saveable booleans live above this header, so count changes never reset expansion. */
@Composable private fun FrozenGroupHeader(title:String,open:Boolean,toggle:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button,
        onClickLabel=if(open) "Collapse $title" else "Expand $title",onClick=toggle)
        .semantics(mergeDescendants=true) { stateDescription=if(open) "Expanded" else "Collapsed" }
        .padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(open) "−" else "+",Modifier.clearAndSetSemantics {})
    }
}

@Composable private fun FrozenTaskEditor(task:FrozenTask?,busy:Boolean,error:String?,dismiss:()->Unit,save:(String,String,String)->Unit) {
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title ?: "") }
    var description by rememberSaveable(task?.id) { mutableStateOf(task?.description ?: "") }
    var notes by rememberSaveable(task?.id) { mutableStateOf(task?.notes ?: "") }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text(if(task==null) "Add Frozen task" else "Edit Frozen task")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title,{title=it},label={Text("Task title")},modifier=Modifier.fillMaxWidth(),supportingText={Text("${title.length}/160")})
            OutlinedTextField(description,{description=it},label={Text("Personal reference")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(notes,{notes=it},label={Text("Notes / handoff")},modifier=Modifier.fillMaxWidth(),supportingText={Text("${notes.length}/1000")})
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={save(title,description,notes)},enabled=!busy && title.isNotBlank() && title.length<=160 && description.length<=3000 && notes.length<=1000) { Text("Save task") }},
        dismissButton={TextButton(onClick=dismiss,enabled=!busy) { Text("Cancel") }})
}
