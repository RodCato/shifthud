package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.MainActivity
import com.shifthud.ui.theme.ShiftHudTheme
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class FrozenTasksDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun dashboardEntryAndWeekNavigationAreIndependent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.onNodeWithText("FROZEN TASKS").performScrollTo().performClick()
            ui.onNodeWithText(FROZEN_TITLE).assertIsDisplayed()
            ui.onNodeWithContentDescription("Back to Dashboard").performClick()
            ui.onNodeWithText("Schedule",useUnmergedTree=true).performClick()
            ui.onNodeWithContentDescription("Next week").performClick()
            ui.onNodeWithContentDescription("Previous week").performClick()
            ui.onNodeWithText("Dashboard",useUnmergedTree=true).performClick()
            ui.onNodeWithText("FROZEN TASKS").performScrollTo().performClick()
            ui.onNodeWithText(FROZEN_TITLE).assertIsDisplayed()
        }
    }
    @Test fun taskStatesNotesCarryoverAndLargeFont() {
        var tasks by mutableStateOf(mondayFrozenTemplate())
        var font by mutableStateOf(1f)
        ui.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                ShiftHudTheme { Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                    FrozenTasksContent(tasks,false,null,{}, {},{id,state -> tasks=tasks.map { if(it.id==id)it.copy(state=state,history=it.history+FrozenEvent(state,Instant.now())) else it }},
                        {id,title,reference,notes,done -> tasks=tasks.map { if(it.id==id)it.copy(title=title,description=reference,notes=notes) else it };done() })
                } }
            }
        }
        fun capture(name:String) {
            ui.waitForIdle()
            val shot=InstrumentationRegistry.getInstrumentation()
            java.io.File(shot.targetContext.cacheDir,"frozen-$name.png").outputStream().use { shot.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().filter { it.boundsInRoot.width>0 && it.boundsInRoot.height>0 }.forEach { node ->
                node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let { action ->
                    val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                    assertTrue("Text overflow: $name ${layouts.filter { it.hasVisualOverflow }.map { "${it.layoutInput.text}: ${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" }}",layouts.none { it.hasVisualOverflow })
                }
            }
            val i=InstrumentationRegistry.getInstrumentation()
            java.io.File(i.targetContext.cacheDir,"frozen-$name.png").outputStream().use { i.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        capture("checklist")
        val title=tasks.first().title
        ui.onNodeWithContentDescription("Complete $title").performClick()
        ui.onNodeWithContentDescription("Reopen $title").assertIsOn().performClick()
        ui.onNodeWithText(title).performClick()
        ui.onNodeWithText("Start task").performScrollTo().performClick()
        ui.onNodeWithText("Mark blocked").performScrollTo().performClick()
        ui.onNodeWithText("Edit task / notes").performScrollTo().performClick()
        ui.onNodeWithText("Notes / handoff").performTextInput("Scanner unavailable. Finish Tuesday.")
        ui.onNodeWithText("Save task").performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Scanner unavailable. Finish Tuesday.").performScrollTo().assertIsDisplayed()
        capture("blocked-notes")
        ui.onNodeWithText("Carry to next shift").performScrollTo().performClick()
        ui.onNodeWithText("Task history").performScrollTo().performClick()
        ui.onNodeWithText("Completed ·",substring=true).assertExists()
        capture("history")
        ui.onNodeWithText("Carryovers (1)").performScrollTo().performClick()
        ui.onNodeWithText(title).assertExists()
        ui.runOnIdle { assertEquals(10,tasks.size);assertEquals(FrozenState.CARRY_OVER,tasks.first().state);font=2f }
        ui.onNode(hasScrollAction()).performScrollToIndex(0)
        capture("large-font-header")
        ui.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        ui.onNodeWithText(title).assertIsDisplayed()
        capture("large-font-carryover")
        ui.onNodeWithText(title).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button))
    }
}
