package com.shifthud

import com.shifthud.domain.payroll.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
import java.time.*

class EarningsOverviewTest {
    private val start=LocalDate.of(2026,9,26)
    private fun check(id:Long=1,week:Long=0)=Paycheck(id,start.plusWeeks(week),start.plusWeeks(week).plusDays(6),start.plusWeeks(week).plusDays(12),BigDecimal("35.390000"),56624,49610,true,
        lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="TX Withholding Tax",cents=2682),PayrollLine(kind=PayrollKind.DEDUCTION,label="TX EE Social Security Tax",cents=3511),PayrollLine(kind=PayrollKind.DEDUCTION,label="TX EE Medicare Tax",cents=821)))
    private fun decimal(value:String,actual:BigDecimal?) {assertNotNull(actual);assertEquals(0,BigDecimal(value).compareTo(actual))}
    @Test fun emptyIsUnavailableNotZero(){val t=earningsOverview(emptyList()).totals();assertFalse(t.gross.complete);assertNull(t.takeHomeRate);assertTrue(t.checks.isEmpty())}
    @Test fun oneConfirmedRecordUsesExactFacts(){val t=earningsOverview(listOf(check())).totals();decimal("566.24",t.gross.known);decimal("496.10",t.net.known);decimal("70.14",t.taxes.known);decimal("0",t.other.known);decimal("35.39",t.hours.known);assertEquals(1,t.checks.size)}
    @Test fun allTimeSumsAndPercentagesAreWeighted(){val b=check(2,1).copy(grossCents=10000,netCents=5000,lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="Other deductions",cents=5000)))
        val t=earningsOverview(listOf(check(),b)).totals();decimal("666.24",t.gross.known);decimal("546.10",t.net.known);decimal("50",t.other.known);decimal("82.0",t.takeHomeRate);decimal("10.5",t.withholdingRate)}
    @Test fun monthsAndYearsUsePayDateNotWorkPeriod(){val a=check().copy(payDate=LocalDate.of(2027,1,1));val b=check(2,1);val o=earningsOverview(listOf(a,b));assertEquals(listOf(a),o.totals(EarningsPeriod(2027,1)).checks);assertEquals(listOf(b),o.totals(EarningsPeriod(2026)).checks);assertEquals(YearMonth.of(2027,1),o.months(EarningsPeriod()).keys.first())}
    @Test fun missingPayDateOnlyInAllTime(){val o=earningsOverview(listOf(check().copy(payDate=null)));assertEquals(1,o.totals().checks.size);assertTrue(o.totals(EarningsPeriod(2026)).checks.isEmpty());assertTrue(o.months(EarningsPeriod()).isEmpty())}
    @Test fun unknownFieldsNeverBecomeZero(){val t=EarningsTotals(listOf(check(),check(2,1).copy(netCents=null,reportedHours=null,deductionsComplete=false)));assertEquals(1,t.net.missing);assertEquals(1,t.hours.missing);assertEquals(1,t.taxes.missing);assertNull(t.takeHomeRate);assertNull(t.withholdingRate);decimal("496.10",t.net.known)}
    @Test fun blankTaxAndUnclassifiedDeductionsAreUnknown(){val p=check().copy(lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="TX tax"),PayrollLine(kind=PayrollKind.DEDUCTION,label="Employer code Z",cents=100)));val d=earningsDeductions(p);assertNull(d.taxes);assertNull(d.other);assertTrue(d.warnings.any{it.contains("Unclassified")})}
    @Test fun repeatedDeductionRowsCannotInflateTaxTotals(){val p=check().copy(lines=check().lines+check().lines.first());assertNull(earningsDeductions(p).taxes);assertTrue(earningsDeductions(p).warnings.any{it.contains("Repeated")})}
    @Test fun exactLabelsArePreserved(){assertEquals(check().lines.map{it.label}.toSet(),EarningsTotals(listOf(check())).taxComponents.keys)}
    @Test fun taxSummaryIsNotAddedToComponents(){val p=check().copy(lines=check().lines+PayrollLine(kind=PayrollKind.DEDUCTION,label="Total taxes",cents=7014));decimal("70.14",earningsDeductions(p).taxes);assertEquals(3,earningsDeductions(p).components.size)}
    @Test fun taxSummaryAloneDoesNotInventBreakdown(){val p=check().copy(lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="Total taxes",cents=7014)));decimal("70.14",earningsDeductions(p).taxes);assertTrue(earningsDeductions(p).components.isEmpty())}
    @Test fun mismatchedTaxSummaryNeedsReview(){val p=check().copy(lines=check().lines+PayrollLine(kind=PayrollKind.DEDUCTION,label="Total taxes",cents=7000));assertNull(earningsDeductions(p).taxes);assertTrue(earningsDeductions(p).warnings.isNotEmpty())}
    @Test fun arithmeticMismatchDoesNotRewriteNet(){val p=check().copy(netCents=100);val d=earningsDeductions(p);assertTrue(d.warnings.any{it.contains("does not equal")});decimal("1.00",EarningsTotals(listOf(p)).net.known);assertEquals(100L,p.netCents)}
    @Test fun sameIdentityCountedOnce(){assertEquals(1,earningsOverview(listOf(check(),check())).totals().checks.size)}
    @Test fun overlappingPeriodsQuarantinedWithoutDeleting(){val a=check();val b=check(2).copy(periodEnd=start.plusDays(7));val o=earningsOverview(listOf(a,b));assertEquals(listOf(a,b),o.review);assertTrue(o.checks.isEmpty())}
    @Test fun equalAmountsInDistinctPeriodsAreNotDuplicates(){assertEquals(2,earningsOverview(listOf(check(),check(2,1))).checks.size)}
    @Test fun editsDeletionAndConfirmationAreRecomputed(){val a=check();decimal("566.24",earningsOverview(listOf(a)).totals().gross.known);decimal("600",earningsOverview(listOf(a.copy(grossCents=60000))).totals().gross.known);assertTrue(earningsOverview(emptyList()).checks.isEmpty())}
    @Test fun reconciliationMetadataDoesNotChangeEmployerFacts(){val a=check();assertEquals(EarningsTotals(listOf(a)).gross,EarningsTotals(listOf(a.copy(revision=2,notes="reviewed"))).gross)}
    @Test fun largeTotalsAndPenniesRemainExact(){val checks=(1..1000).map { check(it.toLong(),it.toLong()).copy(grossCents=MAX_PAYROLL_CENTS,netCents=1) };val t=EarningsTotals(checks);decimal("999999999990.00",t.gross.known);decimal("10.00",t.net.known)}
    @Test fun zeroGrossDoesNotDivide(){assertNull(EarningsTotals(listOf(check().copy(grossCents=0))).takeHomeRate)}
    private val now=Instant.parse("2026-10-20T18:00:00Z")
    private fun session(id:Long=1,date:LocalDate=start)=WorkSession(id=id,clockIn=date.atTime(8,0).toInstant(ZoneOffset.UTC),clockOut=date.atTime(16,0).toInstant(ZoneOffset.UTC),state=ShiftState.COMPLETE)
    private fun coverage(s:List<WorkSession>,p:List<Paycheck>)=earningsCoverage(s,p,DEFAULT_PAY_RATES,now,ZoneOffset.UTC,PayEstimator(ShiftEngine(Clock.fixed(now,ZoneOffset.UTC))))
    @Test fun unknownCadenceNeverClaimsOverdue(){val c=coverage(listOf(session()),emptyList()).single();assertEquals(CoverageStatus.UNRECORDED,c.status);decimal("128",c.estimatedGross);assertEquals(Duration.ofHours(8),c.paid)}
    @Test fun linkedWorkIsNotPending(){assertEquals(CoverageStatus.CONFIRMED,coverage(listOf(session()),listOf(check())).single().status)}
    @Test fun partialPeriodIsUncertain(){assertEquals(CoverageStatus.UNCERTAIN,coverage(listOf(session()),listOf(check().copy(periodEnd=start.plusDays(1)))).single().status)}
    @Test fun activeWorkIncompleteAndCurrentWeekNotYetPayable(){val active=session(date=LocalDate.of(2026,10,20)).copy(clockOut=null,state=ShiftState.WORKING);assertEquals(CoverageStatus.INCOMPLETE,coverage(listOf(active),emptyList()).single().status);assertEquals(CoverageStatus.CURRENT,coverage(listOf(session(date=LocalDate.of(2026,10,19))),emptyList()).single().status)}
    @Test fun noCalendarWeeksFabricated(){assertTrue(coverage(emptyList(),emptyList()).isEmpty())}
}
