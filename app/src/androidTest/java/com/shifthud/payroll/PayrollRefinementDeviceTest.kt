package com.shifthud.payroll

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.domain.payroll.*
import com.shifthud.notification.paycheck.*
import com.shifthud.ui.payroll.PaycheckEditor
import com.shifthud.ui.settings.PaycheckReminderSettingsSection
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import java.time.*

class PayrollRefinementDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun existingPaycheckCanBeClassifiedAndConfirmedWithoutChangingGrossOrDeposit() {
        val date=LocalDate.of(2026,10,24)
        val original=Paycheck(id=1,periodStart=date,periodEnd=date.plusDays(6),payDate=date.plusDays(12),grossCents=67904,netCents=48675,deductionsComplete=true,
            lines=listOf(PayrollLine(2,PayrollKind.DEDUCTION,"Taxes withheld",9229),PayrollLine(3,PayrollKind.EARNING,"Gift card",10000)),deposits=listOf(PayrollDeposit(4,date.plusDays(12),48675)))
        var saved:Paycheck?=null
        ui.setContent { ShiftHudTheme { PaycheckEditor(original,false,{p,done->saved=p;done(null)},dismiss={}) } }
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("CLASSIFY AS NONCASH"))
        ui.onNodeWithText("CLASSIFY AS NONCASH").performClick()
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Taxable"))
        ui.onNodeWithText("Taxable",useUnmergedTree=true).performClick()
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Already included in reported gross"))
        ui.onNodeWithContentDescription("Already included in reported gross").performClick()
        assertNull(saved)
        ui.onNodeWithText("SAVE PAYCHECK").performClick()
        ui.runOnIdle {
            val p=checkNotNull(saved)
            assertEquals(original.grossCents,p.grossCents);assertEquals(original.deposits,p.deposits)
            assertEquals(PayrollKind.NONCASH,p.lines.last().kind);assertEquals(true,p.lines.last().taxable)
            assertEquals(0L,cashNetDifference(p));assertEquals(1L,p.id)
        }
    }
    @Test fun reminderSettingsSaveDayAndDisableWithoutOpeningAnotherScreen() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="paycheck-ui-isolated-${System.nanoTime()}"
        val context=object:ContextWrapper(base){override fun getSharedPreferences(n:String,mode:Int)=base.getSharedPreferences(name,mode)}
        val reminders=PaycheckReminders(context,{emptyList()},enqueueWork={_,_->})
        try {
            ui.setContent { ShiftHudTheme { Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                Column(Modifier.verticalScroll(rememberScrollState())){ PaycheckReminderSettingsSection(reminders) }
            } } }
            ui.onNodeWithText("Thursday").performClick();ui.onNodeWithText("Friday").performClick()
            ui.waitUntil(5000){reminders.store.settings().day==DayOfWeek.FRIDAY}
            ui.onNodeWithText("Every Friday at 7:00 PM · Device local time").assertExists()
            ui.onNode(isToggleable()).performClick();ui.waitUntil(5000){!reminders.store.settings().enabled}
            ui.onNodeWithText("Reminder disabled").assertExists()
        }finally{base.deleteSharedPreferences(name)}
    }
}
