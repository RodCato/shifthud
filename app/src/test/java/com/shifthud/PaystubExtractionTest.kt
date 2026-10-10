package com.shifthud

import com.shifthud.domain.paystub.*
import com.shifthud.domain.payroll.*
import com.shifthud.ui.paystub.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

val PAYSTUB_FIXTURE="""
Pay period: 09/26/2026 - 10/02/2026
Pay date: 10/08/2026
Regular hours: 35.39
Gross earnings: $566.24
Taxes
TX Withholding Tax $26.82
TX EE Social Security Tax $35.11
TX EE Medicare Tax $8.21
Total taxes $70.14
Direct deposit $496.10
""".trimIndent()
class PaystubExtractionTest {
    @Test fun referenceDatesAndExactDecimalHours(){val e=extractPaystub(PAYSTUB_FIXTURE);assertEquals(LocalDate.of(2026,9,26),e.start);assertEquals(LocalDate.of(2026,10,2),e.end);assertEquals(LocalDate.of(2026,10,8),e.payDate);assertEquals("35.39".toBigDecimal(),e.regular);assertNull(e.overtime)}
    @Test fun exactLabelsAndIndividualTaxesExcludeTotal(){val e=extractPaystub(PAYSTUB_FIXTURE);assertEquals(listOf("TX Withholding Tax","TX EE Social Security Tax","TX EE Medicare Tax"),e.lines.map{it.label});assertEquals(listOf(2682L,3511L,821L),e.lines.map{it.cents});assertEquals(7014L,e.taxTotal);assertEquals(7014L,e.lines.sumOf{it.cents!!})}
    @Test fun calculatedNetIsNeverAutoReported(){val e=extractPaystub(PAYSTUB_FIXTURE);assertEquals(56624L,e.gross);assertNull(e.reportedNet);assertEquals(49610L,e.calculatedNet);assertNull(e.draft(e.start!!,e.end!!).netCents);assertFalse(e.draft(e.start,e.end).deductionsComplete);assertEquals(49610L,e.deposits.single().cents);assertNull(e.deposits.single().date)}
    @Test fun explicitlyReportedNetIsSeparate(){val e=extractPaystub(PAYSTUB_FIXTURE+"\nNet pay $490.00");assertEquals(49000L,e.reportedNet);assertEquals(49610L,e.calculatedNet)}
    @Test fun blankAndCroppedFieldsRemainUnknown(){val e=extractPaystub("Gross earnings\nNet pay\nRegular hours\nTX Withholding Tax\nDeposit 496.");assertNull(e.gross);assertNull(e.reportedNet);assertNull(e.regular);assertTrue(e.deposits.isEmpty());assertTrue(e.lines.isEmpty());assertNull(e.calculatedNet)}
    @Test fun missingVersusZero(){val e=extractPaystub("Gross earnings 0.00\nNet pay 0.00\nRegular hours 0\nOvertime hours");assertEquals(0L,e.gross);assertEquals(0L,e.reportedNet);assertEquals("0".toBigDecimal(),e.regular);assertNull(e.overtime)}
    @Test fun ambiguousMoneyColumnsAreNotGuessed(){val e=extractPaystub("Gross earnings 566.24 4200.00\nTX Withholding Tax 26.82 184.21\nRegular hours 35.39 16.00 566.24");assertNull(e.gross);assertNull(e.regular);assertTrue(e.lines.isEmpty());assertTrue(e.warnings.any{"ambiguous" in it.lowercase()})}
    @Test fun ytdHeaderPreventsCroppedColumnMisassociation(){val e=extractPaystub("Current YTD\nGross earnings 4200.00\nRegular hours 80.00");assertNull(e.gross);assertNull(e.regular)}
    @Test fun repeatedTotalsStayUncertain(){val e=extractPaystub(PAYSTUB_FIXTURE+"\nTotal taxes 70.14");assertNull(e.taxTotal);assertNull(e.calculatedNet);assertEquals(3,e.lines.size)}
    @Test fun repeatedComponentsRequireManualChoice(){val e=extractPaystub(PAYSTUB_FIXTURE+"\nTX Withholding Tax 26.82");assertEquals(2,e.lines.size);assertNull(e.calculatedNet);assertTrue(e.warnings.any{"Repeated component" in it})}
    @Test fun multipleDepositsAreNotMistakenForTotal(){val e=extractPaystub("Direct deposit Checking 300.00\nDirect deposit Savings 196.10\nTotal deposits 496.10");assertEquals(listOf(30000L,19610L),e.deposits.map{it.cents})}
    @Test fun equalDepositsRequireReviewButMayBeRealSplit(){val e=extractPaystub("Direct deposit Checking 200.00\nDirect deposit Savings 200.00");assertEquals(2,e.deposits.size);assertTrue(e.warnings.any{"Equal deposit" in it})}
    @Test fun additionalLabelsAndAdjustmentsPreserved(){val e=extractPaystub("Deductions\n401k Contribution 12.50\nMedical Plan 20.00\nTotal deductions 32.50\nEarnings\nHoliday bonus 50.00\nTotal earnings 600.00");assertEquals(listOf("401k Contribution","Medical Plan","Holiday bonus"),e.lines.map{it.label});assertEquals(PayrollKind.EARNING,e.lines.last().kind);assertEquals(56750L,e.calculatedNet)}
    @Test fun overtimeHoursAreNotPremiumMoney(){val e=extractPaystub("Regular hours 40.00\nOvertime hours 1.25");assertEquals("1.25".toBigDecimal(),e.overtime);assertNull(e.gross);assertTrue(e.lines.isEmpty())}
    @Test fun separateTaxAndDeductionSubtotalsAreNotDoubleCounted(){
        val raw="Gross earnings 600.00\nTaxes\nFederal Tax 50.00\nTotal taxes 50.00\nDeductions\nMedical plan 20.00\nTotal deductions 20.00"
        assertEquals(53000L,extractPaystub(raw).calculatedNet)
        assertEquals(53000L,extractPaystub(raw.replace("Total deductions 20.00","Total deductions 70.00")).calculatedNet)
        assertNull(extractPaystub(raw.replace("Total deductions 20.00","Total deductions 80.00")).calculatedNet)
    }
    @Test fun unlabeledDepositAccountsWithinSectionAreDeposits(){val e=extractPaystub("Direct Deposit\nChecking 300.00\nSavings 196.10");assertEquals(2,e.deposits.size);assertTrue(e.lines.isEmpty())}
    @Test fun decimalThousandsExactAndNoOcrCharacterGuess(){assertEquals(123456L,extractPaystub("Gross earnings $1,234.56").gross);assertNull(extractPaystub("Gross earnings 566.2O").gross);assertNull(extractPaystub("Gross earnings 566,24").gross)}
    @Test fun spacedCurrencySymbolIsNotPartOfPayrollDescription(){val e=extractPaystub("Taxes\nTX Withholding Tax $ 26.82");assertEquals("TX Withholding Tax",e.lines.single().label);assertEquals(2682L,e.lines.single().cents)}
    @Test fun invalidDatesNotNormalized(){val e=extractPaystub("Pay date 02/30/2026\nPay period start 2026-09-26\nPay period end 2026-10-02");assertNull(e.payDate);assertEquals(LocalDate.of(2026,9,26),e.start)}
    @Test fun arithmeticRecomputedAfterCorrection(){val e=extractPaystub(PAYSTUB_FIXTURE);val p=e.draft(e.start!!,e.end!!).copy(netCents=49610,deductionsComplete=true);assertTrue(paystubArithmetic(p).isEmpty());assertEquals(2,paystubArithmetic(p.copy(netCents=49611)).size)}
    @Test fun subtotalMismatchFlagsMissingComponents(){val e=extractPaystub(PAYSTUB_FIXTURE.replace("TX EE Medicare Tax $8.21", ""));assertNull(e.calculatedNet);assertTrue(e.warnings.any{"Tax total does not match" in it})}
    @Test fun geometryJoinsLabelsToRightHandAmounts(){assertEquals("Gross earnings 566.24\nNet pay 496.10",ocrRows(listOf(OcrPiece("566.24",500,12,600,32),OcrPiece("Net pay",20,60,200,80),OcrPiece("Gross earnings",20,10,300,30),OcrPiece("496.10",500,60,600,80))))}
    @Test fun geometryPreservesMultipleColumnsForAmbiguityCheck(){val raw=ocrRows(listOf(OcrPiece("Gross earnings",0,0,100,20),OcrPiece("566.24",200,0,250,20),OcrPiece("4200.00",400,0,450,20)));assertNull(extractPaystub(raw).gross)}
}
