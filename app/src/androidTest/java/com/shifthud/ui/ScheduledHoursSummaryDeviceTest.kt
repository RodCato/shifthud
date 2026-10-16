package com.shifthud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import com.shifthud.ui.schedule.*
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class ScheduledHoursSummaryDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val week=scheduleWeek(LocalDate.of(2026,10,17))
    private val now=week.start.atStartOfDay(ZoneOffset.UTC).toInstant()
    private val shifts=(0L..4L).map { i -> ScheduledShift(i+1,week.start.plusDays(i),LocalTime.of(if(i==0L)16 else if(i==1L)10 else 8,0),LocalTime.of(if(i==0L)23 else if(i==1L)18 else 17,0),employerNetMinutes=if(i==0L)360 else if(i==1L)420 else 480) }
    private fun summary(rows:List<ScheduledShift> = shifts,target:Duration=Duration.ofHours(40)) = schedulePlanning(week,rows,emptyList(),PlannerSettings().withWeek(week.start,35),target,now,ZoneOffset.UTC)
    @Test fun prominentScheduledHoursAndCollapsedDetailsFitNarrowAndLargeFonts() {
        var scale by mutableStateOf(1f)
        ui.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                ShiftHudTheme { Surface { Column(Modifier.width(320.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("Oct 17–23 · 5 shifts",modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium)
                    SchedulePlanningSummary(summary(),35)
                    SchedulePlanningDetails(summary(),Locale.US)
                } } }
            }
        }
        fun layout(text:String):TextLayoutResult {
            val result=mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single()
        }
        for(font in listOf(1f,2f)) {
            ui.runOnIdle { scale=font }
            ui.onNodeWithTag("schedule-employer").assertTextEquals("Scheduled paid","37h 0m")
            ui.onNodeWithTag("schedule-projected").assertTextEquals("Projected paid: 39h 5m")
            ui.onNodeWithTag("schedule-target").performScrollTo().assertTextEquals("Target: 40h 0m · Shortfall: 55m")
            ui.onNodeWithTag("schedule-progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(2345f/2400f,0f..1f)))
            assertTrue(layout("37h 0m").layoutInput.style.fontSize>layout("Projected paid: 39h 5m").layoutInput.style.fontSize)
            listOf("37h 0m","Scheduled paid","Projected paid: 39h 5m","35m planned lunch per shift","Target: 40h 0m · Shortfall: 55m").forEach { assertFalse("Overflow: $it at $font",layout(it).hasVisualOverflow) }
            ui.onNodeWithText("Schedule Planning Details").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
            ui.onNodeWithText("Actual paid:",substring=true).assertDoesNotExist()
            ui.onNodeWithTag("schedule-employer").performScrollTo()
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            java.io.File(instrumentation.targetContext.cacheDir,"scheduled-hours-${font.toInt()}x.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        ui.onNodeWithText("Schedule Planning Details").performScrollTo().performClick()
        ui.onNodeWithText("Actual paid: 0m · Projected remaining: 39h 5m").performScrollTo().assertIsDisplayed()
    }
    @Test fun sourceAndTargetLabelsNeverPresentEstimatesAsEmployerFacts() {
        var model by mutableStateOf(summary())
        ui.setContent { ShiftHudTheme { Column(Modifier.verticalScroll(rememberScrollState())) { SchedulePlanningSummary(model,35) } } }
        ui.onNodeWithTag("schedule-hours-source").assertTextEquals("Employer-reported schedule hours")
        ui.runOnIdle { model=summary(shifts.mapIndexed { index,s -> if(index==0)s.copy(employerNetMinutes=null) else s }) }
        ui.onNodeWithTag("schedule-employer").assertTextEquals("Scheduled paid estimate","37h 0m")
        ui.onNodeWithTag("schedule-hours-source").assertTextEquals("Estimate · Employer hours for 4 of 5 shifts")
        ui.runOnIdle { model=summary(target=Duration.ofHours(38)) }
        ui.onNodeWithTag("schedule-target").assertTextEquals("Target: 38h 0m · Excess: 1h 5m")
        ui.runOnIdle { model=summary(target=Duration.ofMinutes(2345)) }
        ui.onNodeWithTag("schedule-target").assertTextEquals("Target: 39h 5m · At weekly target")
        ui.runOnIdle { model=summary(emptyList()) }
        ui.onNodeWithTag("schedule-employer").assertTextEquals("Scheduled paid","Unknown — no saved shifts")
        ui.onNodeWithTag("schedule-hours-source").assertTextEquals("No employer hours available")
        ui.onNodeWithTag("schedule-projected").assertTextEquals("Projected paid: 0m")
    }
}
