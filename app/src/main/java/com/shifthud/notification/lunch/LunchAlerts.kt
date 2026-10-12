package com.shifthud.notification.lunch

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.*
import com.shifthud.*
import com.shifthud.notification.*
import com.shifthud.service.ActiveShiftService
import com.shifthud.backup.guardedLock as withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.time.*
import java.util.concurrent.TimeUnit

/** Local notification settings/receipts only; never writes timekeeping or planning records. */
class LunchAlerts(private val context:Context, private val clock:()->Instant = Instant::now) {
    private val prefs=context.getSharedPreferences("on_lunch_alerts",Context.MODE_PRIVATE)
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val mutex=Mutex()
    private val _settings=MutableStateFlow(readSettings())
    val settings=_settings.asStateFlow()
    private val _next=MutableStateFlow<Instant?>(null)
    val next=_next.asStateFlow()
    private var scheduled:Instant?=null
    private var scheduledOnce=false
    private fun readSettings()=LunchAlertSettings(prefs.getBoolean("minimum",true),prefs.getBoolean("planned",false),prefs.getInt("snooze",10).coerceIn(1,120),prefs.getBoolean("repeat",false))
    private fun readLedger():LunchAlertLedger? = runCatching {
        val j=JSONObject(prefs.getString("ledger",null) ?: return null)
        LunchAlertLedger(j.getString("id"),j.getBoolean("min"),j.getBoolean("plan"),if(j.has("at"))Instant.ofEpochMilli(j.getLong("at")) else null,j.getLong("generation"),j.optString("receipt"),j.optBoolean("minimumSent",false))
    }.getOrNull()
    private fun writeLedger(s:LunchAlertLedger?) {
        val text=s?.let { JSONObject().put("id",it.identity).put("min",it.minimumDone).put("plan",it.plannedDone)
            .put("minimumSent",it.minimumSent).put("generation",it.generation).put("receipt",it.receipt).apply{it.snoozeAt?.let{at->put("at",at.toEpochMilli())}}.toString() }
        if(prefs.getString("ledger",null)!=text) check(prefs.edit().putString("ledger",text).commit())
    }
    suspend fun save(value:LunchAlertSettings) {
        mutex.withLock {
            val previous=settings.value
            val app=context.applicationContext as ShiftHudApplication
            val session=app.repository.snapshot().session
            readLedger()?.takeIf { it.identity==lunchIdentity(session) }?.let { ledger ->
                val start=session!!.lunchStart!!
                val planned=app.preferences.weeklyPlannerSettings.first().weeklyMinutes(session.clockIn.atZone(ZoneId.systemDefault()).toLocalDate())
                writeLedger(ledger.copy(
                    minimumDone=if(!previous.minimum && value.minimum) clock()>=start.plusSeconds(31*60) else ledger.minimumDone,
                    plannedDone=if(!previous.planned && value.planned) clock()>=start.plusSeconds(planned*60L) else ledger.plannedDone))
            }
            check(prefs.edit().putBoolean("minimum",value.minimum).putBoolean("planned",value.planned).putInt("snooze",value.snoozeMinutes).putBoolean("repeat",value.repeat).commit())
            _settings.value=value
            if(!value.repeat && readLedger()?.receipt?.contains(":SNOOZE:")==true) writeLedger(readLedger()?.copy(snoozeAt=null))
            manager.cancel(ID)
        }
        refresh();ActiveShiftService.requestRefresh()
    }
    private fun open()=PendingIntent.getActivity(context,ID,Intent(context,MainActivity::class.java).putExtra(MainActivity.DESTINATION,"Dashboard").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun notification(event:LunchAlertEvent):Notification {
        val text=lunchAlertText(event.kind,event.minutes)
        val snooze=PendingIntent.getBroadcast(context,0,Intent(context,LunchAlertActionReceiver::class.java).setAction("SNOOZE_ON_LUNCH")
            .setData("shifthud://on-lunch/${event.receipt}".toUri()).putExtra("receipt",event.receipt),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context,ShiftNotifications.WARNING_CHANNEL).setSmallIcon(com.shifthud.R.drawable.ic_stat_shift)
            .setContentTitle(text.first).setContentText(text.second).setContentIntent(open()).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setTimeoutAfter(10*60_000L)
            .addExtras(android.os.Bundle().apply{putString(RECEIPT,event.receipt)})
            .addAction(0,"SNOOZE ${settings.value.snoozeMinutes}M",snooze).addAction(0,"OPEN SHIFTHUD",open()).build()
    }
    suspend fun refresh()=mutex.withLock {
        val app=context.applicationContext as ShiftHudApplication
        app.notifications.createChannels()
        val session=app.repository.snapshot().session
        val now=clock()
        val minutes=session?.let { app.preferences.weeklyPlannerSettings.first().weeklyMinutes(it.clockIn.atZone(ZoneId.systemDefault()).toLocalDate()) } ?: 31
        val decision=lunchAlertDecision(session,settings.value,minutes,readLedger(),now)
        var state=decision.ledger
        if(decision.cancel || state==null) manager.cancel(ID)
        decision.event?.let { event ->
            if(app.notifications.warningsAllowed()) {
                try {
                    if(manager.activeNotifications.none { it.id==ID && it.notification.extras.getString(RECEIPT)==event.receipt }) manager.notify(ID,notification(event))
                    state=acknowledgeLunchAlert(state!!,event,settings.value,minutes,now)
                } catch(_:SecurityException) { /* Keep pending; Android permission may have changed. */ }
            }
        }
        writeLedger(state)
        val after=lunchAlertDecision(session,settings.value,minutes,state,now)
        // Prevent a denied-permission busy loop while retaining the boundary within its freshness window.
        val target=after.next?.let { if(it<=now)now.plusSeconds(60) else it }
        _next.value=target
        if(!scheduledOnce || target!=scheduled) {
            val work=WorkManager.getInstance(context)
            if(target==null)work.cancelUniqueWork(WORK)
            else work.enqueueUniqueWork(WORK,ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<LunchAlertWorker>()
                .setInitialDelay(Duration.between(now,target).toMillis().coerceAtLeast(1),TimeUnit.MILLISECONDS).build())
            scheduledOnce=true;scheduled=target
        }
    }
    suspend fun snooze(receipt:String):Boolean {
        val accepted=mutex.withLock {
            val app=context.applicationContext as ShiftHudApplication
            val next=snoozeLunchAlert(app.repository.snapshot().session,readLedger(),receipt,settings.value.snoozeMinutes,clock()) ?: return@withLock false
            writeLedger(next);manager.cancel(ID);true
        }
        refresh();ActiveShiftService.requestRefresh()
        return accepted
    }
    companion object { const val ID=1010;const val RECEIPT="shifthud.on_lunch.receipt";const val WORK="shifthud-on-lunch-alert" }
}
class LunchAlertWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try { (applicationContext as ShiftHudApplication).lunchAlerts.refresh();Result.success() }
        catch(e:CancellationException){throw e} catch(_:Exception){Result.retry()}
}
class LunchAlertActionReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!="SNOOZE_ON_LUNCH")return
        val receipt=intent.getStringExtra("receipt") ?: return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { withTimeout(8000){(context.applicationContext as ShiftHudApplication).lunchAlerts.snooze(receipt)} }
            catch(e:Exception) { android.util.Log.w("ShiftHUDLunch","Snooze deferred; punches unchanged",e) }
            finally { pending.finish() }
        }
    }
}
