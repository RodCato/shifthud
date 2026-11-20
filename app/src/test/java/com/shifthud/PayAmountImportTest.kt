package com.shifthud

import com.shifthud.domain.paystub.*
import com.shifthud.domain.payroll.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

val PAY_AMOUNT_FIXTURE="""
Pay Date 11/19/2026
Pay Period 11/07/2026 - 11/13/2026
Pay Amount $537.51
Regular Hours 38.61
Gross Earnings $617.76
Total Taxes $80.25
""".trimIndent()
class PayAmountImportTest {
    @Test fun suppliedExamplePrefillsReportedCashNetAndReconciles() {
        val e=extractPaystub(PAY_AMOUNT_FIXTURE);val p=e.draft(LocalDate.MIN,LocalDate.MIN).copy(deductionsComplete=true)
        assertEquals(61776L,p.grossCents);assertEquals(53751L,p.netCents);assertEquals(8025L,e.taxTotal)
        assertEquals("38.61".toBigDecimal(),p.reportedHours);assertEquals(LocalDate.of(2026,11,19),p.payDate)
        assertEquals(0L,cashNetDifference(p));assertEquals(ExtractionConfidence.HIGH,e.confidence["net"])
        assertTrue(p.deposits.isEmpty());assertTrue(paystubSummaryCheck(p,e).isEmpty())
    }
    @Test fun standaloneLabelAndValueAreSupported(){assertEquals(53751L,extractPaystub("Pay Amount:\n$537.51").reportedNet)}
    @Test fun ambiguityAndOcrErrorsRemainUnknown(){listOf("Pay Amount $537.5I","Pay Amount","Pay Amount $537.51 $9000.00","Pay Amount YTD $537.51","Current YTD\nPay Amount $537.51","Gift Card Pay Amount $100.00","Pay Amount $537.51\nPay Amount $537.50","Pay Amount $537.51\nNet Pay $500.00").forEach{assertNull(it,extractPaystub(it).reportedNet)}}
    @Test fun wrappedCurrentPayAmountDoesNotUseYtd(){assertEquals(53751L,extractPaystub("Pay $537.51 YTD Pay $9000.00\nAmount:").reportedNet);assertNull(extractPaystub("Pay $537.51 $9000.00 YTD Pay\nAmount:").reportedNet)}
    @Test fun agreeingLabelsAndLegacyNetWork(){assertEquals(53751L,extractPaystub("Pay Amount $537.51\nNet Pay $537.51").reportedNet);assertEquals(53751L,extractPaystub("Net Pay $537.51").reportedNet)}
    @Test fun depositsRemainSeparateAndTaxComponentsNeverDoubleCount(){val e=extractPaystub(PAY_AMOUNT_FIXTURE+"\nFederal Tax $70.00\nState Tax $10.25\nDirect Deposit $300.00\nDir Dep Acct 1234 $237.51");val p=e.draft(e.start!!,e.end!!).copy(deductionsComplete=true);assertEquals(2,p.deposits.size);assertEquals(2,p.lines.size);assertEquals(0L,cashNetDifference(p));assertTrue(paystubArithmetic(p).isEmpty())}
    @Test fun correctedNoncashAndDeductionsUseExistingArithmetic(){val e=extractPaystub(PAY_AMOUNT_FIXTURE);val p=e.draft(e.start!!,e.end!!).copy(deductionsComplete=true,grossCents=72776,lines=e.lines+PayrollLine(kind=PayrollKind.NONCASH,label="Benefit",cents=10000,taxable=true,includedInGross=true)+PayrollLine(kind=PayrollKind.DEDUCTION,label="Other deduction",cents=1000));assertEquals(0L,cashNetDifference(p));assertEquals("Unreconciled difference: $1.00",cashReconciliationWarning(p.copy(netCents=53651)))}
    @Test fun overtimeAndMultipleEarningsRetainExistingHours(){val e=extractPaystub(PAY_AMOUNT_FIXTURE.replace("Regular Hours 38.61","Regular Hours 38.61\nOvertime Hours 2.00\nEarnings\nHoliday bonus $10.00"));assertEquals("40.61".toBigDecimal(),e.reportedHours);assertEquals(53751L,e.reportedNet)}
}
