package com.shifthud

import com.shifthud.domain.analytics.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.payroll.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.domain.weekly.WeeklyTargetSettings
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
import java.time.*

class PayrollTest {
    private val start=LocalDate.of(2026,10,3)
    private val now=Instant.parse("2026-10-10T18:00:00Z")
    private val estimator=PayEstimator(ShiftEngine(Clock.fixed(now,ZoneOffset.UTC)))
    private fun estimate(minutes:Long=480, rates:List<PayRate> = DEFAULT_PAY_RATES, active:Boolean=false):WorkAnalytics {
        val clockIn=start.atTime(4,0).toInstant(ZoneOffset.UTC)
        val session=WorkSession(id=1,clockIn=clockIn,clockOut=if(active)null else clockIn.plusSeconds(minutes*60),state=if(active)ShiftState.WORKING else ShiftState.COMPLETE)
        return workAnalytics(AnalyticsPeriod.week(start),listOf(session),emptyList(),rates,now,ZoneOffset.UTC,estimator,WeeklyTargetSettings())
    }
    private fun complete()=Paycheck(periodStart=start,periodEnd=start.plusDays(6),payDate=start.plusDays(12),reportedHours=BigDecimal("8.00"),grossCents=12800,netCents=10000,deductionsComplete=true,
        lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="Tax",cents=2800)),deposits=listOf(PayrollDeposit(date=start.plusDays(11),cents=10000)))
    @Test fun weekDefaultsSaturdayThroughFriday(){assertEquals(start,AnalyticsPeriod.week(start.plusDays(4)).start);assertEquals(start.plusDays(7),AnalyticsPeriod.week(start).endExclusive)}
    @Test fun decimalHoursAreNotClockMinutes(){assertEquals(Duration.ofMinutes(39*60+9),employerDuration(parsePayrollHours("39.15")!!));assertEquals("39h 9m",employerHoursLabel(BigDecimal("39.15")));assertEquals(Duration.ofSeconds(127368),employerDuration(BigDecimal("35.38")))}
    @Test fun financialParsingIsExact(){assertEquals(49610L,parsePayrollCents("496.10"));assertEquals(1L,parsePayrollCents("0,01"));assertEquals(-44L,parsePayrollCents("-0.44"));assertEquals(56608L,grossCents(employerDuration(BigDecimal("35.38")),1600));assertEquals(62640L,grossCents(employerDuration(BigDecimal("39.15")),1600))}
    @Test fun missingDiffersFromZero(){assertNull(parsePayrollCents(""));assertNull(parsePayrollHours(" "));assertEquals(0L,parsePayrollCents("0"));assertEquals(BigDecimal.ZERO,parsePayrollHours("0"))}
    @Test fun allFourComparisonsAreIndependent(){val r=PayrollReconciliation(complete().copy(reportedHours=BigDecimal("8.25"),grossCents=12900,netCents=10020),estimate());assertEquals(0,r.hoursDifference!!.compareTo(BigDecimal("0.25")));assertEquals(100L,r.grossDifference);assertEquals(80L,r.netDifference);assertEquals(20L,r.depositDifference);assertEquals(ReconciliationStatus.DIFFERENCE,r.status)}
    @Test fun completeMatchingFactsReconcile(){assertEquals(ReconciliationStatus.RECONCILED,PayrollReconciliation(complete(),estimate()).status)}
    @Test fun essentialMissingFactsNeverReconcile(){val p=complete();listOf(p.copy(payDate=null),p.copy(reportedHours=null),p.copy(grossCents=null),p.copy(netCents=null),p.copy(deductionsComplete=false),p.copy(lines=p.lines.map{it.copy(cents=null)}),p.copy(deposits=emptyList()),p.copy(deposits=listOf(PayrollDeposit(cents=10000)))).forEach{assertEquals(ReconciliationStatus.INCOMPLETE,PayrollReconciliation(it,estimate()).status)}}
    @Test fun deductionsRequireExplicitCompleteness(){assertNull(PayrollReconciliation(complete().copy(deductionsComplete=false),estimate()).deductions);assertEquals(0L,PayrollReconciliation(complete().copy(lines=emptyList()),estimate()).deductions)}
    @Test fun additionalCategoriesAndCorrectionsSumExactly(){val p=complete().copy(lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="A",cents=2801),PayrollLine(kind=PayrollKind.DEDUCTION,label="Refund",cents=-1),PayrollLine(kind=PayrollKind.EARNING,label="Premium already in gross",cents=500)));val r=PayrollReconciliation(p,estimate());assertEquals(2800L,r.deductions);assertEquals(10000L,r.calculatedNet);assertEquals(ReconciliationStatus.RECONCILED,r.status)}
    @Test fun splitDepositsAndReversal(){val p=complete().copy(deposits=listOf(6001L,4000L,-1L).map{PayrollDeposit(date=start.plusDays(12),cents=it)});assertEquals(10000L,PayrollReconciliation(p,estimate()).deposits);assertEquals(0L,PayrollReconciliation(p,estimate()).depositDifference)}
    @Test fun missingDepositAmountDoesNotBecomeZero(){val r=PayrollReconciliation(complete().copy(deposits=listOf(PayrollDeposit(cents=10000),PayrollDeposit())),estimate());assertEquals(10000L,r.knownDeposits);assertNull(r.deposits);assertNull(r.depositDifference)}
    @Test fun firstReferenceDatasetRemainsIncomplete(){val p=Paycheck(periodStart=LocalDate.of(2026,9,26),periodEnd=LocalDate.of(2026,10,2),reportedHours=BigDecimal("35.38"),deposits=listOf(PayrollDeposit(cents=49610)));val r=PayrollReconciliation(p,estimate());assertEquals(ReconciliationStatus.INCOMPLETE,r.status);assertNull(r.grossDifference);assertNull(r.netDifference);assertNull(r.depositDifference)}
    @Test fun secondReferenceNeverFabricatesActualPay(){val estimated=estimate(2350).let{it.copy(breakdown=SessionBreakdown(it.breakdown.contributions.map{c->c.copy(grossCents=62684)}))};val p=Paycheck(periodStart=start,periodEnd=start.plusDays(6),reportedHours=BigDecimal("39.15"));val r=PayrollReconciliation(p,estimated);assertEquals(44L,estimated.grossCents!!-grossCents(employerDuration(p.reportedHours!!),1600));assertNull(r.grossDifference);assertEquals(ReconciliationStatus.INCOMPLETE,r.status)}
    @Test fun roundedHoursAreExplainedButNotSilentlyMatched(){val r=PayrollReconciliation(complete().copy(reportedHours=BigDecimal("8.02")),estimate(481));assertTrue(r.possibleHoursRounding);assertEquals(ReconciliationStatus.DIFFERENCE,r.status)}
    @Test fun historicalRatesAndBaseOnlyOvertime(){val rates=listOf(PayRate(LocalDate.MIN,1600),PayRate(start.plusDays(1),2000));assertEquals(67200L,estimate(42*60,rates).grossCents)}
    @Test fun missingRateAndActiveSessionCannotReconcile(){assertEquals(ReconciliationStatus.INCOMPLETE,PayrollReconciliation(complete(),estimate(rates=emptyList())).status);assertEquals(ReconciliationStatus.INCOMPLETE,PayrollReconciliation(complete(),estimate(active=true)).status)}
    @Test fun invalidPrecisionAndPeriodAreRejected(){assertThrows(IllegalArgumentException::class.java){parsePayrollCents("1.001")};assertThrows(IllegalArgumentException::class.java){parsePayrollHours("39:15")};assertThrows(IllegalArgumentException::class.java){validatePaycheck(complete().copy(periodEnd=start.minusDays(1)))}}
}
