package com.shifthud

import android.app.Application
import android.app.Notification
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import com.shifthud.domain.weekly.WeeklyTargetSettings
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
class PlannerPersistenceTest {
    @Test fun settingsAndDeliverySurviveRepositoryRecreation()=runBlocking {
        val app=RuntimeEnvironment.getApplication();val p=ShiftPreferences(app)
        val day=LocalDate.of(2026,11,6);val zone=ZoneOffset.UTC
        fun at(h:Int)=day.atTime(h,0).toInstant(zone)
        val settings=PlannerSettings(60,31,LunchStrategy.CUSTOM,mapOf(day to 31),setOf(60,30,15,0))
        p.setPlanner(settings);assertEquals(settings,ShiftPreferences(app).plannerSettings.first())
        p.setWeeklyTarget(WeeklyTargetSettings(targetMinutes=480))
        val shifts=listOf(ScheduledShift(1,day,LocalTime.of(4,0),LocalTime.of(14,0)))
        val live=WorkSession(1,1,at(4),at(8),at(9),state=ShiftState.WORKING)
        var posts=0
        p.deliverPlanner(listOf(live),shifts,at(10),zone){_,_,_->posts++;true}
        p.deliverPlanner(listOf(live),shifts,at(12),zone){boundary,_,_->assertEquals(60,boundary);posts++;false}
        ShiftPreferences(app).deliverPlanner(listOf(live),shifts,at(12),zone){_,_,_->posts++;true}
        ShiftPreferences(app).deliverPlanner(listOf(live),shifts,at(12),zone){_,_,_->error("duplicate")}
        assertEquals(2,posts)
    }
    @Test fun realNotificationUsesWeeklyChannelAndAppTap() {
        val n=ShiftNotifications(RuntimeEnvironment.getApplication()).plannerReminder(15,"receipt")
        assertEquals(ShiftNotifications.WEEKLY_CHANNEL,n.channelId)
        assertNotNull(n.contentIntent);assertEquals(Notification.CATEGORY_REMINDER,n.category)
        assertEquals("Planned clock-out in 15 min",n.extras.getString(Notification.EXTRA_TITLE))
        assertNotEquals(ShiftNotifications.WEEKLY_ID,ShiftNotifications.PLANNER_ID)
        assertNotEquals(com.shifthud.service.ActiveShiftService.NOTIFICATION_ID,ShiftNotifications.PLANNER_ID)
    }
}
