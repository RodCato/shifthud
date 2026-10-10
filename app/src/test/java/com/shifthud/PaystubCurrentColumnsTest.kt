package com.shifthud

import com.shifthud.domain.paystub.*
import com.shifthud.domain.payroll.*
import com.shifthud.domain.pay.PayRate
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

val CURRENT_PAYSTUB = """
Pay Date 10/08/2026
Pay Period 09/26/2026 - 10/02/2026
Pay $496.10 YTD Pay
Amount:
Hours and Gross Earnings
Hours Current
Regular (Straight) Hours 35.39 $566.24
Total Gross 35.39 $566.24
Deductions
Current
Taxes Withheld
Current
Federal
TX Withholding Tax $26.82
TX EE Social Security Tax $35.11
TX EE Medicare Tax $8.21
Total Taxes $70.14
Payments
Dir Dep Acct 8011 $496.10
""".trimIndent()

class PaystubCurrentColumnsTest {
    @Test fun realOcrAutoPopulatesWithoutPromotingCalculatedNet() {
        val e=extractPaystub(CURRENT_PAYSTUB)
        val p=e.draft(LocalDate.MIN,LocalDate.MIN)
        assertEquals(LocalDate.of(2026,9,26),p.periodStart)
        assertEquals(LocalDate.of(2026,10,2),p.periodEnd)
        assertEquals(LocalDate.of(2026,10,8),p.payDate)
        assertEquals("35.39".toBigDecimal(),e.regular)
        assertEquals(e.regular,p.reportedHours)
        assertEquals(56624L,p.grossCents)
        assertEquals(listOf("TX Withholding Tax","TX EE Social Security Tax","TX EE Medicare Tax"),p.lines.map{it.label})
        assertEquals(listOf(2682L,3511L,821L),p.lines.map{it.cents})
        assertTrue(p.lines.all{it.kind==PayrollKind.DEDUCTION})
        assertEquals(7014L,e.taxTotal)
        assertEquals(49610L,p.deposits.single().cents)
        assertEquals(49610L,e.calculatedNet)
        assertNull(p.netCents)
        assertEquals(ExtractionConfidence.HIGH,e.confidence["reportedHours"])
        assertEquals(ExtractionConfidence.UNKNOWN,e.confidence["net"])
        assertTrue(e.warnings.toString(),e.warnings.isEmpty())
    }
    @Test fun currentSectionResetsYtdButMixedRowsRemainUnknown() {
        val e=extractPaystub("Current YTD\nGross earnings 1000.00\nTaxes Withheld\nCurrent\nFederal Tax $10.00 $80.00\nState Tax $5.00")
        assertNull(e.gross);assertEquals(listOf("State Tax"),e.lines.map{it.label})
        assertTrue(e.warnings.isNotEmpty())
    }
    @Test fun variedHoursOvertimeAndAccountNumbers() {
        val e=extractPaystub(CURRENT_PAYSTUB.replace("35.39","40.00").replace("566.24","760.00")
            .replace("Total Gross 40.00","Overtime Hours 4.00 $120.00\nTotal Gross 44.00")
            .replace("8011","9753")+"\nDir Dep Acct 1234 $20.00")
        assertEquals("44.00".toBigDecimal(),e.reportedHours)
        assertEquals("4.00".toBigDecimal(),e.overtime)
        assertEquals(76000L,e.gross);assertEquals(2,e.deposits.size)
    }
    @Test fun inferredHoursRequireReviewAndCroppedOvertimePreventsInference() {
        val e=extractPaystub("Regular hours 32.25")
        assertEquals(ExtractionConfidence.REVIEW_REQUIRED,e.confidence["reportedHours"])
        assertTrue(e.warnings.any{"Paid hours" in it})
        assertNull(extractPaystub("Regular hours 32.25\nOvertime hours").reportedHours)
    }
    @Test fun hoursAndRateColumnsNotGuessed() {
        val e=extractPaystub("Regular Hours 35.39 16.00 $566.24\nTotal Gross 35.39 $566.24 $5000.00")
        assertNull(e.regular);assertNull(e.gross);assertNull(e.reportedHours)
    }
    @Test fun grossOnlyTotalNeverBecomesHours() {
        val e=extractPaystub("Total Gross 566.24")
        assertEquals(56624L,e.gross)
        assertNull(e.reportedHours)
    }
    @Test fun editedTaxesAreCheckedAgainstRecognizedSummary() {
        val e=extractPaystub(CURRENT_PAYSTUB);val p=e.draft(e.start!!,e.end!!)
        assertTrue(paystubSummaryCheck(p,e).isEmpty())
        assertTrue(paystubSummaryCheck(p.copy(lines=p.lines.dropLast(1)),e).isNotEmpty())
    }
    @Test fun historicalRateIsOnlyAComparison() {
        val e=extractPaystub(CURRENT_PAYSTUB);val p=e.draft(e.start!!,e.end!!)
        assertTrue(paystubRateCheck(p,null,listOf(PayRate(LocalDate.MIN,1600))).isEmpty())
        assertTrue(paystubRateCheck(p,null,listOf(PayRate(LocalDate.MIN,1800))).isNotEmpty())
        assertEquals(56624L,p.grossCents)
        assertTrue(paystubRateCheck(p,"2".toBigDecimal(),listOf(PayRate(LocalDate.MIN,1800))).isEmpty())
    }
}
