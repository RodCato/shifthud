package com.shifthud.ui

import androidx.compose.foundation.background
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
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class ScheduleActualsDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun collapsedProjectedHoursWrapAtNarrowWidthAndLargeFont() = checkRow(true)
    @Test fun collapsedActualsWrapAtNarrowWidthAndLargeFont() = checkRow(false)
    private fun checkRow(projected: Boolean) {
        val shift=ScheduledShift(1,LocalDate.of(2026,10,11),LocalTime.of(7,0),LocalTime.of(16,0))
        val record=WorkSession(1,1,Instant.parse("2026-10-11T07:00:00Z"),Instant.parse("2026-10-11T12:36:00Z"),Instant.parse("2026-10-11T13:33:00Z"),Instant.parse("2026-10-11T16:00:00Z"),ShiftState.COMPLETE)
        val actual=if(projected) scheduleActuals(shift,emptyList(),35,record.clockIn.minusSeconds(3600),ZoneOffset.UTC,Locale.US,false,
            DailySchedulePlan(Duration.ofMinutes(505),Duration.ofMinutes(35)))
        else scheduleActuals(shift,listOf(record),56,record.clockOut!!,ZoneOffset.UTC,Locale.US,false)
        var scale by mutableStateOf(1f)
        ui.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                ShiftHudTheme {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("Schedule",modifier=Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onBackground)
                        Surface {
                            ExpandableSection("Sun, Oct 11\n7:00 AM – 4:00 PM",actual.summary,
                                leading={ScheduleCompletionIndicator(if(projected) ScheduleCompletionStatus.SCHEDULED else ScheduleCompletionStatus.COMPLETED)},
                                subtitleContent={ScheduleActualSummary(actual)}) {
                                ExpandableSection("Advanced schedule details","Employer metadata and retained lunch values") { Text("Legacy lunch value: 99 minutes") }
                            }
                        }
                    }
                }
            }
        }
        for(font in listOf(1f,2f)) {
            ui.runOnIdle { scale=font }
            ui.onNodeWithText(actual.summary).assertIsDisplayed()
            ui.onNodeWithText(actual.lunch!!).assertIsDisplayed()
            ui.onNodeWithText("Advanced schedule details").assertDoesNotExist()
            ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().forEach { node ->
                node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action?.let { action ->
                    val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                    assertTrue("Text must wrap without clipping at $font: ${layouts.filter { it.hasVisualOverflow }.map { it.layoutInput.text.text to it.size }}",layouts.none { it.hasVisualOverflow })
                }
            }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val image=instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.cacheDir,"schedule-${if(projected) "projected" else "actuals"}-${font.toInt()}x.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        ui.onNodeWithText(actual.summary).performClick()
        ui.onNodeWithText("Advanced schedule details").assertIsDisplayed()
        ui.onNodeWithText("Legacy lunch value: 99 minutes").assertDoesNotExist()
    }
}
