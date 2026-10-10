package com.shifthud

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.ScheduledShift
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.upcoming.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class UpcomingSchedulingTest {
    @Test fun workReplacesPendingRequestAndIsIndependentOfActiveService()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        try {
            val r=UpcomingReminders(context,{emptyList()})
            r.reschedule(true)
            r.reschedule(true)
            val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork(UpcomingReminders.WORK).get()
            assertEquals(1,work.count{!it.state.isFinished})
            assertTrue(work.single{!it.state.isFinished}.tags.contains(UpcomingReminderWorker::class.java.name))
            assertNull(Shadows.shadowOf(context).nextStartedService)
        } finally { WorkManagerTestInitHelper.closeWorkDatabase() }
    }
    @Test fun roomScheduleWritesTriggerRefreshButDoNotCreateSessions()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val db=Room.inMemoryDatabaseBuilder(context,ShiftDatabase::class.java).build()
        try {
            var calls=0
            val repo=ShiftRepository(db,ShiftEngine(),onScheduleChanged={calls++})
            val shift=ScheduledShift(id=98,date=LocalDate.now().plusDays(1),scheduledStart=LocalTime.of(5,0),scheduledEnd=LocalTime.of(14,0))
            repo.save(shift);repo.save(shift.copy(scheduledStart=LocalTime.of(6,0)));repo.delete(98)
            assertEquals(3,calls);assertTrue(repo.snapshot().schedule.isEmpty());assertNull(repo.snapshot().session)
        } finally { db.close() }
    }
}
