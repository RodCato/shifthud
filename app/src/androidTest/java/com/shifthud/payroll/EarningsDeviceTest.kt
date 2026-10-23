package com.shifthud.payroll

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.domain.payroll.*
import com.shifthud.ui.payroll.EarningsOverviewScreen
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

class EarningsDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val day=LocalDate.of(2026,10,3)
    private val paycheck=Paycheck(1,day,day.plusDays(6),day.plusDays(12),BigDecimal("30.25"),50000,40000,true,
        lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="Federal withholding",cents=10000)))
    private fun show(text:String) {ui.onNode(hasScrollAction()).performScrollToNode(hasText(text))}
    private fun capture(name:String) {
        ui.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(250)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"earnings-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
        ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().filter {it.boundsInRoot.width>0 && it.boundsInRoot.height>0}.forEach {node ->
            node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let {action ->
                val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                // Button text can retain the parent paragraph width after intrinsic sizing.
                // Check actual glyph-line bounds instead of that false-positive width flag.
                assertTrue("Clipped text: ${layouts.map { it.layoutInput.text }}", layouts.all { layout ->
                    !layout.didOverflowHeight && (0 until layout.lineCount).all { line ->
                        !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f
                    }
                })
            }
        }
    }
    @Test fun narrowScreenNavigationFiltersAndReactiveFacts() {
        val data=mutableStateOf(listOf(paycheck));var opened:Long?=null
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {EarningsOverviewScreen(data.value,emptyList(),{}, {opened=it.id})}}}
        show("Total Take-Home Pay");capture("overview")
        ui.onNode(hasScrollAction()).performScrollToIndex(0)
        ui.onNodeWithText("MONTH").performClick();ui.onNodeWithContentDescription("Previous period").assertHasClickAction()
        ui.onNodeWithText("ALL TIME").performClick()
        show("TAXES WITHHELD");ui.onNodeWithText("TAXES WITHHELD").performClick()
        show("Effective Withholding Percentage: 20.0%");ui.onNodeWithText("Effective Withholding Percentage: 20.0%").assertIsDisplayed()
        show("October 2026 · $400.00 net");ui.onNodeWithText("October 2026 · $400.00 net").performClick()
        show("Hours: 30.25");ui.onNodeWithText("Hours: 30.25").performClick();assertEquals(1L,opened)
        ui.runOnIdle {data.value=listOf(paycheck.copy(netCents=39000))}
        show("Total Take-Home Pay");ui.onNodeWithText("$390.00").assertIsDisplayed()
        ui.runOnIdle {data.value=emptyList()}
        show("No confirmed paychecks in this period. Import a paystub or enter reviewed payroll facts in Paychecks.")
    }
    @Test fun largeFontsAndAccessibilitySemantics() {
        ui.setContent {ShiftHudTheme {val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                EarningsOverviewScreen(listOf(paycheck),emptyList(),{}, {})
            }}
        }}
        ui.onNodeWithText("Earnings Overview").assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading,Unit))
        capture("large-top");show("Total Take-Home Pay");capture("large-totals")
        show("WORKED — NOT YET PAID · ALL RECORDED WORK");capture("large-pending")
    }
}
