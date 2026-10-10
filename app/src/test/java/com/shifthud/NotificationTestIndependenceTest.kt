package com.shifthud

import android.Manifest
import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.data.preferences.*
import com.shifthud.data.repository.ShiftSnapshot
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class NotificationTestIndependenceTest {
    @Test fun allTestChannelsLeaveRowsPayAndAllReminderPreferencesUnchanged()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShiftNotifications(context).createChannels()
        val db=Room.inMemoryDatabaseBuilder(context,ShiftDatabase::class.java).build()
        try {
            val start=Instant.parse("2026-10-09T04:00:00Z")
            val now=start.plusSeconds(6*3600)
            val session=WorkSession(id=79001,scheduledShiftId=8,clockIn=start)
            val schedule=ScheduledShift(8,LocalDate.of(2026,10,9),LocalTime.of(4,0),LocalTime.of(10,15))
            db.shifts().save(schedule.entity());db.shifts().insert(session.entity())
            val prefs=ShiftPreferences(context)
            val engine=ShiftEngine()
            prefs.deliverWarning(session,start,engine,true){true}
            prefs.deliverWarning(session,now.minusSeconds(30*60),engine,true){true}
            val attention=prefs.deliverAttention(session,now,engine,true){_,_->true}.state!!
            assertTrue(prefs.snooze(79001,attention.receipt,{session},now,engine))
            val snapshot=ShiftSnapshot(listOf(schedule),session)
            val end=prefs.deliverShiftEnd(snapshot,now,ZoneOffset.UTC){_,_->true}.state!!
            assertTrue(prefs.snoozeShiftEnd(end.receipt,{snapshot},now,ZoneOffset.UTC))
            prefs.deliverWeeklyWarning(listOf(session),listOf(schedule),now,ZoneOffset.UTC){_,_->true}
            val pay=PayRatePreferences(context);pay.save(PayRate(LocalDate.of(2026,10,1),1700))
            val rates=pay.rates.first()
            val gross=PayEstimator(engine).week(listOf(session),rates,now,ZoneOffset.UTC)
            val rows=db.shifts().sessionsBetween(0,Long.MAX_VALUE)
            val schedules=db.shifts().scheduleSnapshot()
            // Inspect the delegate's actual store: Robolectric may retain its original context.
            @Suppress("UNCHECKED_CAST")
            val store=ShiftPreferences::class.java.getDeclaredField("store").apply{isAccessible=true}.get(prefs) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
            val before=store.data.first().asMap()
            var elapsed=0L
            val center=NotificationTestCenter(context){elapsed}
            repeat(2) { TestNotificationChannel.entries.forEach { assertTrue(center.post(it).posted);elapsed+=3000 } }
            assertEquals(rows,db.shifts().sessionsBetween(0,Long.MAX_VALUE));assertEquals(schedules,db.shifts().scheduleSnapshot())
            assertEquals(before,store.data.first().asMap());assertEquals(rates,pay.rates.first())
            assertEquals(gross,PayEstimator(engine).week(listOf(db.shifts().session(79001)!!.model()),pay.rates.first(),now,ZoneOffset.UTC))
            assertEquals(ShiftState.WORKING,db.shifts().session(79001)!!.model().state)
        } finally { db.close() }
    }
}
