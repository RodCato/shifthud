package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.*
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.ui.schedule.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ScheduleCompletionRepositoryTest {
    private lateinit var db:ShiftDatabase
    private val context get()=RuntimeEnvironment.getApplication()
    private fun open()=Room.databaseBuilder(context,ShiftDatabase::class.java,"completion-test.db").build()
    @Before fun setup(){context.deleteDatabase("completion-test.db");db=open()}
    @After fun close(){db.close();context.deleteDatabase("completion-test.db")}
    @Test fun queryReactsToChangesAndReconstructsAfterDatabaseReopen()=runBlocking {
        val date=LocalDate.of(2026,10,10)
        val shift=ScheduledShift(1,date,LocalTime.of(0,0),LocalTime.of(8,0))
        db.shifts().save(shift.entity())
        val repo=ShiftRepository(db,ShiftEngine())
        var latest:List<WorkSession>?=null
        val job=launch { repo.scheduledSessions(listOf(1)).collect { latest=it } }
        suspend fun awaitStatus(expected:ScheduleCompletionStatus)=withTimeout(5000){
            while(latest==null || scheduleCompletion(listOf(shift),latest!!).states[1L]!=expected) delay(10)
        }
        try {
            awaitStatus(ScheduleCompletionStatus.SCHEDULED)
            // Friday clock-in for a Saturday schedule must not be excluded by a week query.
            val record=WorkSession(1,1,Instant.parse("2026-10-09T23:50:00Z"))
            db.shifts().insert(record.entity());awaitStatus(ScheduleCompletionStatus.WORKING)
            val lunch=record.copy(lunchStart=record.clockIn.plusSeconds(3600),state=ShiftState.ON_LUNCH)
            db.shifts().update(lunch.entity());awaitStatus(ScheduleCompletionStatus.ON_LUNCH)
            val ended=lunch.copy(lunchEnd=record.clockIn.plusSeconds(7200),clockOut=record.clockIn.plusSeconds(10800),state=ShiftState.COMPLETE)
            db.shifts().update(ended.entity());awaitStatus(ScheduleCompletionStatus.COMPLETED)
            db.shifts().update(ended.copy(scheduledShiftId=null,correctionRevision=1).entity());awaitStatus(ScheduleCompletionStatus.SCHEDULED)
            db.shifts().update(ended.entity());awaitStatus(ScheduleCompletionStatus.COMPLETED)
            job.cancelAndJoin();db.close();db=open()
            val reloaded=ShiftRepository(db,ShiftEngine()).scheduledSessions(listOf(1)).first()
            assertEquals(ended,reloaded.single())
            assertEquals(1,scheduleCompletion(listOf(shift),reloaded).completed)
            db.shifts().deleteHistorical(1)
            assertEquals(0,scheduleCompletion(listOf(shift),ShiftRepository(db,ShiftEngine()).scheduledSessions(listOf(1)).first()).completed)
            assertEquals(8,db.openHelper.readableDatabase.version)
        } finally { job.cancelAndJoin() }
    }
}
