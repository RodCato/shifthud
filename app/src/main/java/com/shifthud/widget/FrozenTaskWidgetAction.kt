package com.shifthud.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.*
import androidx.glance.appwidget.action.*
import com.shifthud.ShiftHudApplication
import com.shifthud.frozen.FrozenTaskStore
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

private val TaskIdKey=ActionParameters.Key<String>("frozen-task-id")
private val TaskDateKey=ActionParameters.Key<Long>("frozen-task-date")
private val TaskRevisionKey=ActionParameters.Key<String>("frozen-task-revision")
private val TaskCompletedKey=ActionParameters.Key<Boolean>("frozen-task-completed")
data class FrozenTaskCommand(val date:LocalDate,val id:String,val revision:String,val completed:Boolean)
internal fun frozenTaskWidgetAction(date:LocalDate,task:WidgetFrozenTask):Action =
    actionRunCallback<FrozenTaskWidgetAction>(actionParametersOf(TaskIdKey to task.id,
        TaskDateKey to date.toEpochDay(),TaskRevisionKey to task.revision,TaskCompletedKey to !task.completed))

class FrozenTaskActionExecutor(private val store:FrozenTaskStore,private val redraw:suspend ()->Unit = {}) {
    suspend fun execute(command:FrozenTaskCommand):Boolean=try {
        store.setDailyCompletion(command.date,command.id,command.revision,command.completed)
    } catch(e:CancellationException) {throw e}
    catch(_:Exception) {false}
    finally {redraw()} // Also reconcile an optimistic Android checkbox after a rejected/stale action.
}
class FrozenTaskWidgetAction:ActionCallback {
    override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters) {
        val app=context.applicationContext as ShiftHudApplication
        val command=runCatching {FrozenTaskCommand(LocalDate.ofEpochDay(requireNotNull(parameters[TaskDateKey])),
            requireNotNull(parameters[TaskIdKey]),requireNotNull(parameters[TaskRevisionKey]),requireNotNull(parameters[TaskCompletedKey]))}.getOrNull()
        if(command==null) {app.widgetRefresh.redrawHistorical();return}
        FrozenTaskActionExecutor(app.frozenTasks) {app.widgetRefresh.redrawHistorical()}.execute(command)
    }
}
