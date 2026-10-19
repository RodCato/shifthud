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
import com.shifthud.ui.components.ExpandableSection
import com.shifthud.ui.schedule.*
import com.shifthud.ui.theme.ShiftHudTheme
import com.shifthud.ui.time.LocalDecimalHours
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class SchedulePunchesDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun actualScheduleDiscrepancyStatesAndAccessibleRendering() {
        val day=LocalDate.of(2026,10,12)
        fun at(time:String)=day.atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC)
        val base=ScheduledShift(1,day,LocalTime.of(4,0),LocalTime.of(13,0))
        val completed=WorkSession(1,1,at("04:00"),at("09:50"),at("10:47"),at("13:00"),ShiftState.COMPLETE)
        var shift by mutableStateOf(base)
        var records by mutableStateOf(listOf(completed))
        var font by mutableStateOf(1f)
        var decimals by mutableStateOf(true)
        ui.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,font),LocalDecimalHours provides decimals) {
                ShiftHudTheme { Surface { Column(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("Schedule",Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineMedium)
                    val punches=schedulePunches(shift,records,ZoneOffset.UTC,Locale.US,false)
                    val now=if(records.isEmpty()) at("03:00") else at("13:00")
                    val plan=schedulePlanning(scheduleWeek(day),listOf(shift),records,com.shifthud.domain.planner.PlannerSettings().withWeek(day,35),Duration.ofHours(40),now,ZoneOffset.UTC)
                    val actual=scheduleActuals(shift,records,35,now,ZoneOffset.UTC,Locale.US,false,plan.daily[1])
                    val status=scheduleCompletion(listOf(shift),records).states.getValue(1)
                    ExpandableSection("Mon, Oct 12",status.label,
                        leading={ScheduleCompletionIndicator(status)},
                        titleContent={ScheduleRowHeading("Mon, Oct 12",punches.primary,punches.scheduled)},
                        subtitleContent={ScheduleActualSummary(actual)}) {
                        PunchDiagnosticsView(punchDiagnostics(shift,records,now,ZoneOffset.UTC,Locale.US))
                        punches.sessions.forEach { Text(it,Modifier.fillMaxWidth()) }
                    }
                } } }
            }
        }
        fun capture(name:String) {
            ui.waitForIdle()
            ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().forEach { node ->
                node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let { action ->
                    val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                    assertTrue("Overflow in $name: ${layouts.filter { it.hasVisualOverflow }.map { it.layoutInput.text }}",layouts.none { it.hasVisualOverflow })
                }
            }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            java.io.File(instrumentation.targetContext.cacheDir,"schedule-punches-$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        ui.onNodeWithText("CLOCKED: 4:00 AM – 1:00 PM").assertIsDisplayed()
        ui.onNodeWithText("Scheduled:",substring=true).assertDoesNotExist()
        capture("matching")
        ui.runOnIdle { shift=base.copy(scheduledStart=LocalTime.of(4,2)) }
        ui.onNodeWithText("CLOCKED: 4:00 AM – 1:00 PM").assertIsDisplayed()
        ui.onNodeWithText("Scheduled: 4:02 AM – 1:00 PM").assertIsDisplayed()
        ui.onNodeWithText("Completed · 8h 3m actual paid").assertIsDisplayed()
        ui.onNodeWithText("8.05 decimal hours").assertExists()
        ui.onNodeWithText("Lunch: 9:50 AM – 10:47 AM (57m)").assertExists()
        capture("different-with-lunch")
        ui.onNodeWithText("CLOCKED: 4:00 AM – 1:00 PM").performClick()
        ui.onNodeWithText("Punch Diagnostics").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
        ui.onNodeWithText("Clock in: 04:00:00").assertDoesNotExist()
        ui.onNodeWithText("Punch Diagnostics").performClick()
        ui.onNodeWithText("Clock in: 04:00:00").performScrollTo().assertIsDisplayed()
        capture("diagnostics-punches")
        ui.onNodeWithText("Actual paid duration: 08:03:00").performScrollTo().assertIsDisplayed()
        capture("diagnostics-durations")
        ui.runOnIdle { font=2f }
        ui.onNodeWithText("Clock out: 13:00:00").performScrollTo().assertIsDisplayed()
        capture("diagnostics-large-font")
        ui.runOnIdle { font=1f }
        ui.onNodeWithText("Punch Diagnostics").performScrollTo().performClick()
        ui.onNodeWithText("CLOCKED: 4:00 AM – 1:00 PM").performScrollTo().performClick()
        ui.runOnIdle { decimals=false }
        ui.onNodeWithText("8.05 decimal hours").assertDoesNotExist()
        ui.runOnIdle { decimals=true; font=2f }
        ui.onNodeWithText("CLOCKED: 4:00 AM – 1:00 PM").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("CLOCKED: 4:00 AM – 1:00 PM")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        capture("large-font")
        ui.runOnIdle { font=1f; records=listOf(completed.copy(clockOut=null,lunchStart=null,lunchEnd=null,state=ShiftState.WORKING)) }
        ui.onNodeWithText("CLOCKED IN: 4:00 AM · WORKING").assertExists()
        capture("working")
        ui.runOnIdle { records=listOf(records.single().copy(lunchStart=at("09:50"),state=ShiftState.ON_LUNCH)) }
        ui.onNodeWithText("CLOCKED IN: 4:00 AM · ON LUNCH").assertExists()
        capture("on-lunch")
        ui.runOnIdle { records=emptyList() }
        ui.onNodeWithText("Scheduled: 4:02 AM – 1:00 PM").assertExists()
        ui.onAllNodes(hasText("CLOCKED",substring=true)).assertCountEquals(0)
        capture("scheduled")
        ui.runOnIdle { records=listOf(completed.copy(clockOut=at("11:00")),WorkSession(2,1,at("12:00"),lunchStart=at("12:15"),lunchEnd=at("12:30"),clockOut=at("13:00"),state=ShiftState.COMPLETE)) }
        ui.onNodeWithText("CLOCKED: 2 sessions · Expand for times").performScrollTo().performClick()
        ui.onNodeWithText("Session 1 · CLOCKED: 4:00 AM – 11:00 AM").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Session 2 · CLOCKED: 12:00 PM – 1:00 PM").performScrollTo().assertIsDisplayed()
        capture("multiple")
        ui.onNodeWithText("Punch Diagnostics").performScrollTo().performClick()
        ui.onNodeWithText("Lunch 1: 09:50:00 – 10:47:00").performScrollTo().assertIsDisplayed()
        capture("diagnostics-lunch-one")
        ui.onNodeWithText("Lunch 2: 12:15:00 – 12:30:00").performScrollTo().assertIsDisplayed()
        capture("diagnostics-lunch-two")
        ui.onNodeWithText("Linked sessions total").performScrollTo().assertIsDisplayed()
        capture("diagnostics-multiple-total")
    }
}
