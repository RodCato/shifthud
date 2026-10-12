package com.shifthud

import android.app.NotificationManager
import android.Manifest
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.notification.ShiftNotifications
import com.shifthud.domain.planner.PlannerSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ShiftHudApplication::class)
class PlanningRefreshTest {
    @Test fun weeklyEditNotifiesOnceAfterPersistenceWithoutTimekeepingHook()=runBlocking {
        val app=RuntimeEnvironment.getApplication() as ShiftHudApplication
        val dates=mutableListOf<LocalDate>();var timekeepingHooks=0
        val p=ShiftPreferences(app,onPlanningChanged={week->assertEquals(31,ShiftPreferences(app).weeklyPlannerSettings.first().weeklyMinutes(week));dates+=week},onChanged={timekeepingHooks++})
        p.setPlanner(PlannerSettings());timekeepingHooks=0
        val week=LocalDate.of(2030,1,5)
        p.setWeeklyLunch(week,31);p.setWeeklyLunch(week,31)
        assertEquals(listOf(week),dates);assertEquals(0,timekeepingHooks)
    }
    @Test fun stalePlannerNoticeClearsWithoutCreatingHistoricalAlertsOrChangingRecords()=runBlocking {
        val app=RuntimeEnvironment.getApplication() as ShiftHudApplication
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager=app.getSystemService(NotificationManager::class.java)
        app.notifications.createChannels()
        val records=app.repository.sessions.first();val payroll=app.payroll.paychecks.first();val schedules=app.repository.schedule.first()
        manager.notify(ShiftNotifications.PLANNER_ID,app.notifications.plannerReminder(15,"old"))
        assertTrue(manager.activeNotifications.any{it.id==ShiftNotifications.PLANNER_ID})
        app.notifications.planningChanged();app.notifications.planningChanged()
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(records,app.repository.sessions.first());assertEquals(payroll,app.payroll.paychecks.first());assertEquals(schedules,app.repository.schedule.first())
    }
}
