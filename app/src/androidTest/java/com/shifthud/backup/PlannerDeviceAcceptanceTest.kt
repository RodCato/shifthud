package com.shifthud.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.planner.*
import com.shifthud.domain.model.*
import com.shifthud.domain.weekly.WeeklyTargetSettings
import com.shifthud.domain.pay.workWeekFor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** Mutating seed phase is opt-in, for an empty disposable emulator installation only. */
@RunWith(AndroidJUnit4::class)
class PlannerDeviceAcceptanceTest {
    @Test fun planner()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
        if(InstrumentationRegistry.getArguments().getString("phase")=="seed") {
            check(app.repository.schedule.first().isEmpty())
            check(app.database.shifts().sessionsBetween(Long.MIN_VALUE,Long.MAX_VALUE).isEmpty())
            val start=workWeekFor(LocalDate.now()).start.plusDays(2)
            repeat(5) { app.repository.save(ScheduledShift(date=start.plusDays(it.toLong()),scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0))) }
            app.preferences.setWeeklyTarget(WeeklyTargetSettings())
            app.preferences.setPlanner(PlannerSettings(minimumMinutes=45,strategy=LunchStrategy.SHORTER,alerts=setOf(60,30,15,0)))
        }
        val settings=app.preferences.plannerSettings.first()
        assertEquals(settings,com.shifthud.data.preferences.ShiftPreferences(app).plannerSettings.first())
        val before=app.backups.snapshot()
        clockOutPlan(app.repository.weeklySessions(Instant.now(),ZoneId.systemDefault()),app.repository.schedule.first(),
            app.preferences.weeklyTargetSettings.first().target,settings,Instant.now(),ZoneId.systemDefault())
        assertEquals(before.data.toString(),app.backups.snapshot().data.toString())
    }
}
