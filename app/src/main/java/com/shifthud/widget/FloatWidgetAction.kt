package com.shifthud.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.*
import androidx.glance.appwidget.action.*
import com.shifthud.ShiftHudApplication
import com.shifthud.frozen.FrozenTaskStore
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal val FloatDeltaKey=ActionParameters.Key<Int>("float-delta")
internal fun floatWidgetAction(delta:Int):Action=actionRunCallback<FloatWidgetAction>(actionParametersOf(FloatDeltaKey to delta))
data class FloatCommand(val delta:Int,val requestId:String=UUID.randomUUID().toString())
/** The same executor backs callback tests; it has no shift/lunch/notification dependencies. */
class FloatActionExecutor(private val store:FrozenTaskStore,private val redraw:suspend ()->Unit = {}) {
    suspend fun execute(command:FloatCommand):Boolean=try {
        store.adjustFloat(command.delta,command.requestId);true
    } catch(e:CancellationException) {throw e}
    catch(_:Exception) {false}
    finally {redraw()}
}
class FloatWidgetAction:ActionCallback {
    override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters) {
        val app=context.applicationContext as ShiftHudApplication
        val delta=parameters[FloatDeltaKey] ?: return
        // Glance exposes no unique delivery ID. A render-level token would lose rapid taps;
        // each callback invocation gets a new ID, reused if its command is retried.
        FloatActionExecutor(app.frozenTasks).execute(FloatCommand(delta))
    }
}
