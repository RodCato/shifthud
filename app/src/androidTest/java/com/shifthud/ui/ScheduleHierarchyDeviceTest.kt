package com.shifthud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import com.shifthud.ui.components.ExpandableSection
import com.shifthud.ui.schedule.*
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class ScheduleHierarchyDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun liveLunchStylingActualsContrastAndScreenshots() {
        val week=scheduleWeek(LocalDate.of(2026,10,17))
        val start=week.start.atStartOfDay(ZoneOffset.UTC).toInstant()
        val shifts=(0L..4L).map { i -> ScheduledShift(i+1,week.start.plusDays(i),LocalTime.of(if(i==0L)16 else if(i==1L)10 else 8,0),LocalTime.of(if(i==0L)23 else if(i==1L)18 else 17,0),employerNetMinutes=if(i==0L)360 else if(i==1L)420 else 480) }
        var minutes by mutableStateOf(60)
        var complete by mutableStateOf(false)
        var scale by mutableStateOf(1f)
        var target by mutableStateOf(Duration.ofHours(40))
        lateinit var accents: ScheduleColors
        ui.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                ShiftHudTheme {
                    val colors=scheduleColors()
                    val scheme=MaterialTheme.colorScheme
                    SideEffect {
                        accents=colors
                        for(background in listOf(scheme.surface,scheme.surfaceContainer)) {
                            for(foreground in listOf(colors.paid,colors.lunch,colors.attention,colors.excess,scheme.onSurface,scheme.onSurfaceVariant)) {
                                val a=foreground.luminance();val b=background.luminance()
                                assertTrue("Text contrast must be >= 4.5:1",(maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f)>=4.5f)
                            }
                        }
                    }
                    val records=if(complete) shifts.map { s ->
                        val clockIn=s.start.toInstant(ZoneOffset.UTC)
                        WorkSession(s.id,s.id,clockIn,clockIn.plusSeconds(3*3600),clockIn.plusSeconds(4*3600),s.end.toInstant(ZoneOffset.UTC),ShiftState.COMPLETE)
                    } else emptyList()
                    val now=if(complete) start.plusSeconds(7*86400) else start
                    val model=schedulePlanning(week,shifts,records,PlannerSettings().withWeek(week.start,minutes),target,now,ZoneOffset.UTC)
                    Surface { Column(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text("Oct 17–23 · ${if(complete) "5 completed" else "5 scheduled"}",modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium)
                        SchedulePlanningSummary(model,minutes)
                        TextButton(onClick={minutes=35}) { Text("EDIT WEEKLY LUNCH PLAN",modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center) }
                        shifts.forEach { s ->
                            val actual=scheduleActuals(s,records,minutes,now,ZoneOffset.UTC,Locale.US,false,model.daily[s.id])
                            val date=s.date.toString()
                            val time="${s.scheduledStart} – ${s.scheduledEnd}"
                            ExpandableSection(date,actual.summary,
                                leading={ScheduleCompletionIndicator(if(complete) ScheduleCompletionStatus.COMPLETED else ScheduleCompletionStatus.SCHEDULED)},
                                titleContent={ScheduleRowHeading(date,time)},subtitleContent={ScheduleActualSummary(actual)}) { Text("Details") }
                        }
                    } }
                }
            }
        }
        fun textColor(text:String): androidx.compose.ui.graphics.Color {
            val layouts=mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        fun capture(name:String) {
            ui.waitForIdle()
            ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().forEach { node ->
                node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let { action ->
                    val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                    assertTrue("No clipped text in $name: ${layouts.filter { it.hasVisualOverflow }.map { it.layoutInput.text.text to it.size }}",layouts.none { it.hasVisualOverflow })
                }
            }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            java.io.File(instrumentation.targetContext.cacheDir,"schedule-hierarchy-$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        ui.onNodeWithText("6h 0m projected paid").assertIsDisplayed()
        ui.onNodeWithText("7h 0m projected paid").assertIsDisplayed()
        capture("60m-narrow")
        ui.onNodeWithText("EDIT WEEKLY LUNCH PLAN").performClick()
        ui.onNodeWithText("6h 25m projected paid").assertIsDisplayed()
        ui.onNodeWithText("7h 25m projected paid").assertIsDisplayed()
        ui.onNodeWithTag("schedule-projected").assertTextEquals("Projected paid: 39h 5m")
        assertEquals(accents.paid,textColor("6h 25m projected paid"))
        assertEquals(accents.lunch,textColor("35m planned lunch per shift"))
        assertEquals(accents.attention,textColor("Target: 40h 0m · Shortfall: 55m"))
        capture("35m-narrow")
        ui.runOnIdle { target=Duration.ofHours(38) }
        ui.onNodeWithTag("schedule-target").assertTextEquals("Target: 38h 0m · Excess: 1h 5m")
        assertEquals(accents.excess,textColor("Target: 38h 0m · Excess: 1h 5m"))
        ui.runOnIdle { target=Duration.ofHours(40) }
        ui.runOnIdle { complete=true }
        ui.onNodeWithText("Completed · 6h 0m actual paid").assertIsDisplayed()
        capture("completed")
        ui.runOnIdle { scale=2f }
        ui.onNodeWithText("Completed · 6h 0m actual paid").performScrollTo().assertIsDisplayed()
        capture("large-font")
    }
}
