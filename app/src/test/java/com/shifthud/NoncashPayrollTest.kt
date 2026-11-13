package com.shifthud

import com.shifthud.domain.payroll.*
import com.shifthud.domain.paystub.paystubArithmetic
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

internal fun noncashFixture() = Paycheck(id=1,periodStart=LocalDate.of(2026,10,24),periodEnd=LocalDate.of(2026,10,30),payDate=LocalDate.of(2026,11,5),reportedHours="36.19".toBigDecimal(),grossCents=67904,netCents=48675,deductionsComplete=true,
    lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="Taxes withheld",cents=9229),PayrollLine(kind=PayrollKind.NONCASH,label="Thanksgiving gift card",cents=10000,taxable=true,includedInGross=true,category="Gift Card")),deposits=listOf(PayrollDeposit(date=LocalDate.of(2026,11,5),cents=48675)))
class NoncashPayrollTest {
    @Test fun novemberExampleReconcilesWithoutChangingReportedGrossOrDeposits() {
        val p=noncashFixture()
        assertEquals(57904L,cashWages(p));assertEquals(48675L,calculatedCashNet(p));assertEquals(0L,cashNetDifference(p))
        assertNull(cashReconciliationWarning(p));assertTrue(paystubArithmetic(p).isEmpty());assertTrue(earningsDeductions(p).warnings.isEmpty())
        assertEquals(67904L,p.grossCents);assertEquals(48675L,p.deposits.single().cents)
    }
    @Test fun noncashDoesNotCreateFalseWorkEarningsDifference() {
        val p=noncashFixture()
        val start=p.periodStart.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val end=start.plus(employerDuration(p.reportedHours!!))
        val engine=com.shifthud.domain.usecase.ShiftEngine(java.time.Clock.fixed(end,java.time.ZoneOffset.UTC))
        val session=com.shifthud.domain.model.WorkSession(id=1,clockIn=start,clockOut=end,state=com.shifthud.domain.model.ShiftState.COMPLETE)
        val estimate=com.shifthud.domain.analytics.workAnalytics(p.period,listOf(session),emptyList(),listOf(com.shifthud.domain.pay.PayRate(LocalDate.MIN,1600)),end,java.time.ZoneOffset.UTC,com.shifthud.domain.pay.PayEstimator(engine),com.shifthud.domain.weekly.WeeklyTargetSettings())
        val r=PayrollReconciliation(p,estimate)
        assertEquals(0L,r.grossDifference);assertEquals(0L,r.netDifference)
        assertEquals(ReconciliationStatus.RECONCILED,r.status)
    }
    @Test fun ordinaryPaychecksStillReconcile() {
        val p=noncashFixture().let { it.copy(grossCents=57904,lines=it.lines.take(1)) }
        assertEquals(48675L,calculatedCashNet(p));assertEquals(0L,noncashCents(p));assertEquals(57904L,cashWages(p))
    }
    @Test fun taxStatusAndGrossInclusionAreIndependentExplicitChoices() {
        val p=noncashFixture()
        val benefit=p.lines.last().copy(taxable=false,includedInGross=false)
        val changed=p.copy(lines=p.lines.take(1)+benefit)
        assertEquals(67904L,cashWages(changed));assertEquals(58675L,calculatedCashNet(changed))
        assertEquals("0.00".toBigDecimal(),EarningsTotals(listOf(changed)).taxableNoncash.known)
        assertEquals(1,EarningsTotals(listOf(changed.copy(lines=listOf(benefit.copy(taxable=null))))).taxableNoncash.missing)
    }
    @Test fun missingNoncashAmountsRemainUnknownAndRoundingIsExplained() {
        val p=noncashFixture()
        assertNull(calculatedCashNet(p.copy(lines=p.lines.map { if(it.kind==PayrollKind.NONCASH)it.copy(cents=null) else it })))
        assertNull(calculatedCashNet(p.copy(deductionsComplete=false)))
        assertEquals("Unreconciled difference: $12.50",cashReconciliationWarning(p.copy(netCents=47425)))
        assertTrue(cashReconciliationWarning(p.copy(netCents=48674))!!.contains("possible rounding"))
    }
    @Test fun summaryRowsAreNotDoubleCounted() {
        val p=noncashFixture().copy(lines=noncashFixture().lines+PayrollLine(kind=PayrollKind.DEDUCTION,label="Federal tax",cents=9229))
        assertEquals(9229L,confirmedDeductions(p));assertEquals(0L,cashNetDifference(p))
        assertNull(confirmedDeductions(p.copy(lines=p.lines+PayrollLine(kind=PayrollKind.DEDUCTION,label="Other tax",cents=1))))
    }
    @Test fun monthYearAndLifetimeUsePayDatesAndNoDoubleCounting() {
        val p=noncashFixture()
        val overview=earningsOverview(listOf(p,p))
        listOf(EarningsPeriod(),EarningsPeriod(2026),EarningsPeriod(2026,11)).forEach { period ->
            val t=overview.totals(period)
            assertEquals(1,t.checks.size);assertEquals("679.04".toBigDecimal(),t.gross.known)
            assertEquals("579.04".toBigDecimal(),t.cash.known);assertEquals("100.00".toBigDecimal(),t.noncash.known)
            assertEquals("100.00".toBigDecimal(),t.taxableNoncash.known);assertEquals("486.75".toBigDecimal(),t.net.known)
            assertEquals("92.29".toBigDecimal(),t.taxes.known)
        }
        assertTrue(overview.totals(EarningsPeriod(2026,10)).checks.isEmpty())
    }
}
