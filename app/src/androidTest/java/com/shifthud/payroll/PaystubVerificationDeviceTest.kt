package com.shifthud.payroll

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.shifthud.domain.payroll.*
import com.shifthud.domain.paystub.extractPaystub
import com.shifthud.ui.payroll.PaycheckEditor
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class PaystubVerificationDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val extraction=extractPaystub("""
        Pay Date 11/19/2026
        Pay Period 11/07/2026 - 11/13/2026
        Pay Amount $537.51
        Regular Hours 38.61
        Gross Earnings $617.76
        Total Taxes $80.25
    """.trimIndent())
    private val initial=extraction.draft(LocalDate.MIN,LocalDate.MIN)
    @Test fun oneCheckboxAndOneSaveWithoutTypingNetAgain() {
        val saved=mutableListOf<Paycheck>()
        ui.setContent {ShiftHudTheme {PaycheckEditor(initial,false,{p,done->saved+=p;done(null)},review=extraction,dismiss={})}}
        ui.onNodeWithText("Save Verified Paycheck").assertIsNotEnabled()
        ui.onNodeWithText("Reconciled — $537.51 net pay").assertExists()
        ui.onNodeWithContentDescription("Verify paycheck details").assertIsOff().performClick()
        ui.onNodeWithText("Save Verified Paycheck").assertIsEnabled().performClick()
        assertEquals(1,saved.size);assertEquals(53751L,saved.single().netCents)
        assertTrue(saved.single().deductionsComplete);assertTrue(saved.single().notes.startsWith("Paystub image reviewed."))
        assertEquals(0L,cashNetDifference(saved.single()))
    }
    @Test fun unverifiedDraftRemainsAvailableWithoutConfirmation() {
        var saved:Paycheck?=null
        ui.setContent {ShiftHudTheme {PaycheckEditor(initial.copy(netCents=null),false,{p,done->saved=p;done(null)},review=extraction,dismiss={})}}
        ui.onNodeWithText("Save Verified Paycheck").assertIsNotEnabled()
        ui.onNodeWithText("Save draft").performClick()
        assertNotNull(saved);assertNull(saved!!.netCents);assertFalse(saved!!.deductionsComplete)
        assertFalse(saved!!.notes.contains("Paystub image reviewed."))
    }
    @Test fun editingAndInterruptedReviewRequireFreshConfirmation() {
        val restoration=StateRestorationTester(ui)
        var saves=0
        restoration.setContent {ShiftHudTheme {PaycheckEditor(initial,false,{_,_->saves++},review=extraction,dismiss={})}}
        ui.onNodeWithContentDescription("Verify paycheck details").performClick()
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Net cash paycheck ($)"))
        ui.onNode(hasText("Net cash paycheck ($)") and hasSetTextAction()).performTextReplacement("536.51")
        ui.onNodeWithContentDescription("Verify paycheck details").assertIsOff()
        ui.onNodeWithText("Save Verified Paycheck").assertIsNotEnabled()
        ui.onNode(hasText("Net cash paycheck ($)") and hasSetTextAction()).performTextReplacement("537.51")
        // Returning to original values must not resurrect an earlier confirmation.
        ui.onNodeWithContentDescription("Verify paycheck details").assertIsOff()
        ui.onNodeWithContentDescription("Verify paycheck details").performClick()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithContentDescription("Verify paycheck details").assertIsOff()
        ui.onNodeWithText("Save Verified Paycheck").assertIsNotEnabled()
        assertEquals(0,saves)
    }
}
