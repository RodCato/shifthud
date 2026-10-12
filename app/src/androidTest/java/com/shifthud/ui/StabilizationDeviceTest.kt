package com.shifthud.ui

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.data.local.entity.entity
import com.shifthud.domain.model.*
import com.shifthud.domain.payroll.Paycheck
import com.shifthud.domain.scheduleimport.*
import com.shifthud.domain.planner.*
import com.shifthud.notification.*
import com.shifthud.notification.upcoming.UpcomingReminders
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.time.*

/** Explicit opt-in synthetic fixture only. Does not call the backup/restore feature. */
class StabilizationDeviceTest {
    @Test fun fixtureAndRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("phase")
        if(phase !in setOf("006c_seed","006c_verify","006c_notifications","006c_active","006c_active_verify")) return@runBlocking
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
        val week=LocalDate.of(2026,10,17);val zone=ZoneId.systemDefault()
        val history=WorkSession(9001,null,LocalDate.of(2026,10,10).atTime(4,0).atZone(zone).toInstant(),
            LocalDate.of(2026,10,10).atTime(8,0).atZone(zone).toInstant(),LocalDate.of(2026,10,10).atTime(9,0).atZone(zone).toInstant(),
            LocalDate.of(2026,10,10).atTime(13,0).atZone(zone).toInstant(),ShiftState.COMPLETE,manuallyEntered=true)
        if(phase=="006c_active") {
            check(app.repository.sessions.first()==listOf(history))
            val start=Instant.now().minusSeconds(300).toEpochMilli()
            app.getSharedPreferences("stabilization_test",0).edit().putLong("clockIn",start).commit()
            app.database.shifts().insert(WorkSession(9002,clockIn=Instant.ofEpochMilli(start)).entity())
            return@runBlocking
        }
        if(phase=="006c_active_verify") {
            val active=app.repository.sessions.first().single { it.id==9002L }
            assertEquals(app.getSharedPreferences("stabilization_test",0).getLong("clockIn",-1),active.clockIn.toEpochMilli())
            assertEquals(ShiftState.WORKING,active.state);assertNull(active.lunchStart);assertNull(active.clockOut)
            assertEquals(history,app.repository.sessions.first().single { it.id==9001L })
            assertEquals(12800L,app.payroll.paychecks.first().single().grossCents)
            return@runBlocking
        }
        if(phase=="006c_seed") {
            check(app.repository.schedule.first().isEmpty() && app.repository.sessions.first().isEmpty() && app.payroll.paychecks.first().isEmpty())
            val fixture="""10/17/2026
                |Sat Oct 17 4 PM–11 PM Net 6 hours
                |Sun Oct 18 10 AM–6 PM Net 7 hours
                |Mon Oct 19 8 AM–5 PM Net 8 hours
                |Tue Oct 20 8 AM–5 PM Net 8 hours
                |Wed Oct 21 8 AM–5 PM Net 8 hours""".trimMargin()
            app.repository.importSchedule(extractSchedule(fixture).rows.map{ScheduleImportChoice(it.shift()!!)},emptyList(),week)
            app.database.shifts().insert(history.entity())
            app.payroll.save(Paycheck(periodStart=week.minusWeeks(1),periodEnd=week.minusDays(1),grossCents=12800,notes="Temporary emulator fixture"))
            app.preferences.setWeeklyLunch(week,31)
        }
        if(phase=="006c_verify") {
            val notifications=app.getSystemService(NotificationManager::class.java).activeNotifications
            val activeIds=setOf(com.shifthud.service.ActiveShiftService.NOTIFICATION_ID,ShiftNotifications.WARNING_ID,ShiftNotifications.END_ID,ShiftNotifications.WEEKLY_ID,ShiftNotifications.PLANNER_ID)
            assertTrue("No active/historical shift alert after restart",notifications.none { it.id in activeIds })
        }
        val rows=app.repository.schedule.first();assertEquals(5,rows.size)
        assertEquals(LocalTime.of(16,0),rows.first().scheduledStart);assertEquals(LocalTime.of(23,0),rows.first().scheduledEnd)
        assertEquals(history,app.repository.sessions.first().single())
        assertEquals(12800L,app.payroll.paychecks.first().single().grossCents)
        val settings=app.preferences.weeklyPlannerSettings.first();assertEquals(31,settings.weeklyMinutes(week))
        val plan=clockOutPlan(app.repository.sessions.first(),rows,Duration.ofHours(40),settings,week.atStartOfDay(zone).toInstant(),zone,week)
        assertEquals(Duration.ofMinutes(2365),plan.projected);assertEquals(Duration.ofMinutes(35),plan.shortfall)
        if(phase=="006c_notifications") {
            app.notifications.createChannels();UpcomingReminders.createChannel(app)
            val prefsBefore=app.preferences.store.data.first()
            val manager=app.getSystemService(NotificationManager::class.java)
            TestNotificationChannel.entries.forEach { channel ->
                // A separate manual tester per channel avoids artificial waiting; IDs remain isolated.
                val result=if(channel==TestNotificationChannel.UPCOMING)app.upcoming.test(NotificationTestCenter(app)) else NotificationTestCenter(app).post(channel)
                assertTrue(result.message,result.posted)
                assertTrue(manager.activeNotifications.any{it.id==channel.testId && it.notification.channelId==channel.channelId})
            }
            assertEquals(prefsBefore,app.preferences.store.data.first())
            assertEquals(history,app.repository.sessions.first().single())
            TestNotificationChannel.entries.forEach{manager.cancel(it.testId)}
        }
    }
}
