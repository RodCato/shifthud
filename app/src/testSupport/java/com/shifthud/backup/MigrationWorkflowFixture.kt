package com.shifthud.backup

import androidx.datastore.preferences.core.*
import com.shifthud.ShiftHudApplication
import com.shifthud.frozen.*
import com.shifthud.frozen.count.*
import com.shifthud.data.preferences.EmployeeNumberPreferences
import kotlinx.coroutines.flow.first
import java.time.*

/** Synthetic records only; shared by isolated JVM and separately packaged device tests. */
suspend fun seedMigrationWorkflows(app:ShiftHudApplication) {
    val date=LocalDate.of(2026,11,2)
    val at=Instant.parse("2026-11-02T18:05:01.123Z")
    val store=FrozenTaskStore(app)
    val tasks=listOf(
        FrozenTask("audit-done",date,"Synthetic completed task","Fixture",FrozenState.COMPLETED,"Keep notes Ω",listOf(FrozenEvent(FrozenState.COMPLETED,at))),
        FrozenTask("audit-carryover",date,"Synthetic carryover","Fixture",FrozenState.CARRY_OVER,"Unfinished freight",listOf(FrozenEvent(FrozenState.CARRY_OVER,at)),carryoverExcluded=false),
        FrozenTask("audit-archived",date,"Archived fixture","Fixture",carryoverArchived=true),
        FrozenTask("audit-endcap",date,"Synthetic product","Fixture",sku="12345",plan="Synthetic plan",sourceNames=listOf("Original OCR label"),sourceSkus=listOf("12345")))
    store.store.guardedEdit {
        it[stringPreferencesKey("tasks_v1")]=store.encode(tasks)
        it[stringPreferencesKey("truck_workflows_v1")]=store.encodeTrucks(listOf(FrozenTruckWorkflow(date,listOf("audit-done"),listOf(TruckArrivalEvent(true,at,"America/Chicago")))))
        it[stringPreferencesKey("selected_workflow")]="daily:$date"
        listOf("daily_workflow_dates","friday_template_dates_v1","daily_template_dates_v1").forEach {key->it[stringSetPreferencesKey(key)]=setOf(date.toString())}
        it[booleanPreferencesKey("carryovers_archived_before_2026_11_01")]=true
        it[stringPreferencesKey("daily_float_counts_v1")]=encodeFloatCounts(FloatCounts(mapOf(date to 7L,date.plusDays(1) to 3L)))
        it[stringPreferencesKey("float_requests_v1")]="[\"audit-retry-1\"]"
    }
    val route=CountRouteStore(app)
    route.preferences.guardedEdit {it.clear()}
    route.saveMap(route.data.first().revision,simpleDoorMap().mapIndexed {i,s->s.copy(count=if(i==0)2 else 0)})
    route.start(route.data.first().revision)
    var data=route.data.first()
    route.resolve(data.revision,data.active!!.number,data.active!!.current!!,DoorState.SKIPPED,"Fixture skipped")
    data=route.data.first();route.finish(data.revision,data.active!!.number)
    route.start(route.data.first().revision)
    data=route.data.first();route.count(data.revision,data.active!!.number,data.active!!.current!!)
    data=route.data.first();route.pause(data.revision,data.active!!.number)
    EmployeeNumberPreferences(app).store.guardedEdit {it[stringPreferencesKey("employee_number")]="001234"}
    app.preferences.store.guardedEdit {it[booleanPreferencesKey("show_decimal_hours")]=false}
    check(app.getSharedPreferences("on_lunch_alerts",0).edit().putBoolean("minimum",false).putBoolean("planned",true).putInt("snooze",7).putBoolean("repeat",false).commit())
    check(app.getSharedPreferences("paycheck_import_reminder",0).edit().putBoolean("enabled",true).putInt("day",4).putInt("minute",1100).commit())
}
