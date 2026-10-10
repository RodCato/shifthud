package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.*
import com.shifthud.domain.analytics.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.payroll.*
import com.shifthud.domain.usecase.*
import com.shifthud.domain.weekly.WeeklyTargetSettings
import com.shifthud.ui.payroll.PayrollDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PayrollRepositoryTest {
    private lateinit var db:ShiftDatabase
    private lateinit var repo:PayrollRepository
    private val date=LocalDate.of(2026,10,3)
    private fun paycheck()=Paycheck(periodStart=date,periodEnd=date.plusDays(6),reportedHours=BigDecimal("39.1500"),lines=defaultPayrollLines(),deposits=listOf(PayrollDeposit(date=date.plusDays(12),cents=49610)))
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build();repo=PayrollRepository(db)}
    @After fun close(){db.close()}
    @Test fun partialCreationAndEditPreserveStableChildren()=runBlocking {
        val id=repo.save(paycheck());val saved=repo.paychecks.first().single()
        assertEquals(id,saved.id);assertNull(saved.grossCents);assertNull(saved.netCents);assertEquals(BigDecimal("39.1500"),saved.reportedHours)
        assertTrue(saved.lines.all{it.id>0});assertTrue(saved.deposits.single().id>0)
        repo.save(saved.copy(grossCents=62684,lines=saved.lines.dropLast(1).map{it.copy(cents=0)},deposits=saved.deposits+PayrollDeposit(date=date.plusDays(13),cents=-10)))
        val edited=repo.paychecks.first().single();assertEquals(saved.id,edited.id);assertEquals(saved.revision+1,edited.revision)
        assertEquals(saved.lines.dropLast(1).map{it.id},edited.lines.map{it.id});assertEquals(saved.deposits.single().id,edited.deposits.first().id);assertEquals(2,edited.deposits.size)
    }
    @Test fun duplicatePeriodsAndStaleUpdatesAreRejected()=runBlocking {
        repo.save(paycheck());val saved=repo.paychecks.first().single()
        try{repo.save(paycheck());fail("duplicate allowed")}catch(_:IllegalArgumentException){}
        repo.save(saved.copy(notes="Updated"))
        try{repo.save(saved.copy(notes="Stale"));fail("stale allowed")}catch(_:IllegalStateException){}
        assertEquals("Updated",repo.paychecks.first().single().notes)
    }
    @Test fun childOwnershipIsEnforcedAndTransactionRollsBack()=runBlocking {
        repo.save(paycheck());val first=repo.paychecks.first().single()
        val second=paycheck().copy(periodStart=date.plusWeeks(1),periodEnd=date.plusWeeks(1).plusDays(6))
        try{repo.save(second.copy(lines=first.lines));fail("foreign child allowed")}catch(_:IllegalArgumentException){}
        assertEquals(listOf(first),repo.paychecks.first())
    }
    @Test fun customPeriodsSortNewestFirstAndChildrenCanBeRemoved()=runBlocking {
        repo.save(paycheck());repo.save(paycheck().copy(periodStart=date.plusWeeks(2),periodEnd=date.plusWeeks(4)))
        val all=repo.paychecks.first();assertEquals(date.plusWeeks(2),all.first().periodStart)
        repo.save(all.first().copy(lines=emptyList(),deposits=emptyList()))
        assertTrue(repo.paychecks.first().first().lines.isEmpty());assertTrue(repo.paychecks.first().first().deposits.isEmpty());assertFalse(repo.paychecks.first().last().lines.isEmpty())
    }
    @Test fun payrollWritesLeaveWorkAndRatesUnchangedWhileCorrectionsRefreshEstimates()=runBlocking {
        val now=Instant.parse("2026-10-10T18:00:00Z");val clock=Clock.fixed(now,ZoneOffset.UTC);val engine=ShiftEngine(clock)
        var effects=0
        val records=ShiftRepository(db,engine,clock,onChanged={effects++},onHistoricalChanged={effects++})
        val rates=MutableStateFlow(listOf(PayRate(LocalDate.MIN,1600),PayRate(date.plusDays(1),2000)))
        val originalRates=rates.value.toList()
        val analytics=AnalyticsRepository(records,rates,MutableStateFlow(WeeklyTargetSettings()),engine)
        val id=records.addHistorical(HistoricalShiftInput(date,LocalTime.of(4,0),LocalTime.of(12,0)),ZoneOffset.UTC)
        val before=records.sessions.first();val schedule=records.schedule.first();val count=effects
        repo.save(paycheck());val payroll=repo.paychecks.first().single();repo.save(payroll.copy(notes="Independent employer facts"))
        assertEquals(before,records.sessions.first());assertEquals(schedule,records.schedule.first());assertEquals(count,effects);assertEquals(originalRates,rates.value)
        val facts=repo.paychecks.first().single()
        var latest:WorkAnalytics?=null
        val job=launch(start=CoroutineStart.UNDISPATCHED){analytics.observe(facts.period,ZoneOffset.UTC,flowOf(now)).collect{latest=it}}
        suspend fun awaitGross(cents:Long)=withTimeout(5000){while(latest?.grossCents!=cents)delay(10)}
        try {awaitGross(12800);val session=records.session(id).first()!!;records.correct(session,TimeEvent.CLOCK_OUT,session.clockOut!!.minusSeconds(3600));awaitGross(11200);assertEquals(facts,repo.paychecks.first().single());assertEquals(originalRates,rates.value)}finally{job.cancelAndJoin()}
    }
    @Test fun draftRestartPreservesUnknownZeroAndInvalidIntermediateInput(){val draft=PayrollDraft.from(paycheck().copy(grossCents=0)).copy(hours="39.",notes="unsaved");assertEquals(draft,PayrollDraft.restore(draft.serialize()));val restored=PayrollDraft.restore(PayrollDraft.from(paycheck().copy(grossCents=0)).serialize()).model();assertEquals(0L,restored.grossCents);assertNull(restored.netCents);assertEquals(BigDecimal("39.1500"),restored.reportedHours)}
}
