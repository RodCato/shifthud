package com.shifthud.backup

import org.json.JSONObject

/** Source-data comparison, not an estimate or a claim about files outside ShiftHUD. */
data class BackupVerification(val passed:Boolean,val details:List<String>) {
    val summary get()=(if(passed)"PASS" else "FAIL")+" · "+details.joinToString("\n")
}
fun compareBackupState(expected:BackupSnapshot,actual:BackupSnapshot):BackupVerification {
    val details=mutableListOf<String>()
    var passed=true
    fun compare(name:String,a:Any?,b:Any?) {
        val left=BackupSnapshot.digest(canonical(a).toByteArray())
        val right=BackupSnapshot.digest(canonical(b).toByteArray())
        val matches=left==right
        passed=passed && matches
        details+="${if(matches)"PASS" else "FAIL"} $name · SHA-256 $right"
    }
    BACKUP_TABLES.forEach {table ->
        val a=expected.data.getJSONObject("tables").getJSONArray(table)
        val b=actual.data.getJSONObject("tables").getJSONArray(table)
        compare("$table (${a.length()} → ${b.length()} records)",a,b)
    }
    PREFERENCE_GROUPS.sorted().forEach {name ->compare(name,expected.data.getJSONObject("preferences").getJSONObject(name),actual.data.getJSONObject("preferences").getJSONObject(name))}
    compare("stable ID sequences",expected.data.getJSONObject("sequences"),actual.data.getJSONObject("sequences"))
    return BackupVerification(passed,details)
}
fun BackupSnapshot.coverageWarnings():List<String> = buildList {
    if(manifest.optInt("sourcePayloadVersion",2)==1) add("Legacy backup: Frozen tasks, floats, count route, Employee Number, lunch alerts and paycheck reminder settings were NOT exported. They cannot be recovered from this file; replacement clears those stores. Create a new backup on the old phone if available.")
    add("Saved records and settings are included. Unfinished OCR reviews and original screenshots are not retained by ShiftHUD; finish reviews and transfer gallery images separately.")
}
fun BackupSnapshot.workflowCounts():Map<String,Int> {
    val prefs=BackupPreferences.decode(data.getJSONObject("preferences").getJSONObject("frozen"))
    fun arrayCount(key:String,array:String)= (prefs[key] as? String)?.let {JSONObject(it).getJSONArray(array).length()} ?: 0
    val route=BackupPreferences.decode(data.getJSONObject("preferences").getJSONObject("countRoute"))["count_route_v1"] as? String
    return mapOf("Frozen tasks" to arrayCount("tasks_v1","tasks"),"Truck workflows" to arrayCount("truck_workflows_v1","workflows"),
        "Count cycles" to com.shifthud.frozen.count.decodeCountRoute(route).cycles.size,
        "Float days" to com.shifthud.frozen.decodeFloatCounts(prefs["daily_float_counts_v1"] as? String).days.size)
}
