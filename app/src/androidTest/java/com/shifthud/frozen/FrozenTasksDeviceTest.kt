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
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun taskStatesNotesCarryoverAndLargeFont() {
        var tasks by mutableStateOf(mondayFrozenTemplate().mapIndexed { index,t -> if(index<7)t.copy(state=FrozenState.COMPLETED) else t })
        var font by mutableStateOf(1f)
        lateinit var inputMode: androidx.compose.ui.input.InputModeManager
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(ui)
        restoration.setContent {
            inputMode=androidx.compose.ui.platform.LocalInputModeManager.current
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                ShiftHudTheme { Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
                    FrozenTasksContent(tasks,false,null,{}, {},{id,state -> tasks=tasks.map { if(it.id==id)it.copy(state=state,history=it.history+FrozenEvent(state,Instant.now())) else it }},
                        {id,title,reference,notes,done -> tasks=tasks.map { if(it.id==id)it.copy(title=title,description=reference,notes=notes) else it };done() })
                } }
            }
        }
        fun show(text:String) { ui.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
        fun click(text:String) { show(text);ui.onNodeWithText(text).performClick() }
        fun capture(name:String) {
            ui.waitForIdle()
            ui.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().filter { it.boundsInRoot.width>0 && it.boundsInRoot.height>0 }.forEach { node ->
                node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult){null}?.action?.let { action ->
                    val layouts=mutableListOf<TextLayoutResult>();action(layouts)
                    assertTrue("Text overflow: $name ${layouts.filter { it.hasVisualOverflow }.map { it.layoutInput.text }}",layouts.none { it.hasVisualOverflow })
                }
            }
            val i=InstrumentationRegistry.getInstrumentation()
            java.io.File(i.targetContext.cacheDir,"frozen-d2-$name.png").outputStream().use { i.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        ui.onNodeWithText("7 of 10 completed · 3 remaining · 0 blocked").assertExists()
        show("ACTIVE TASKS (3)");capture("active-first")
        show("COMPLETED (7)")
        ui.onNodeWithText("COMPLETED (7)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
        ui.onNodeWithContentDescription("Reopen ${tasks.first().title}").assertDoesNotExist()
        capture("completed-collapsed")
        click("COMPLETED (7)")
        restoration.emulateSavedInstanceStateRestore()
        show("COMPLETED (7)")
        ui.onNodeWithText("COMPLETED (7)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        val first=tasks.first().title
        show(first);capture("completed-expanded")
        ui.onNodeWithContentDescription("Reopen $first").performClick()
        show("ACTIVE TASKS (4)")
        ui.onNodeWithText("6 of 10 completed · 4 remaining · 0 blocked").assertExists()
        click(first)
        click("Mark blocked")
        click("Edit task / notes")
        ui.onNodeWithText("Notes / handoff").performTextInput("Scanner unavailable. Finish Tuesday.")
        ui.onNodeWithText("Save task").performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        click("Carry to next Frozen shift")
        show("CARRYOVERS (1)");capture("carryover")
        show(first)
        ui.onNodeWithText("From Mon, Oct 19, 2026").assertExists()
        // Group moves preserve the same task. Expand it if its saved row state is closed.
        if(ui.onAllNodesWithText("Remove from carryover").fetchSemanticsNodes().isEmpty())click(first)
        show("Scanner unavailable. Finish Tuesday.")
        click("Remove from carryover")
        show("ACTIVE TASKS (4)")
        show(first)
        if(ui.onAllNodesWithText("Carry to next Frozen shift").fetchSemanticsNodes().isEmpty())click(first)
        click("Carry to next Frozen shift")
        show(first)
        ui.onNodeWithContentDescription("Complete $first").performClick()
        show("COMPLETED (7)")
        ui.onNodeWithText("COMPLETED (7)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        show(first)
        ui.onNodeWithContentDescription("Reopen $first").performClick()
        ui.runOnIdle { assertEquals(10,tasks.size);assertEquals(10,tasks.map { it.id }.distinct().size);assertEquals("Scanner unavailable. Finish Tuesday.",tasks.first().notes);font=2f }
        ui.onNode(hasScrollAction()).performScrollToIndex(0);capture("large-header")
        show("ACTIVE TASKS (4)");capture("large-active")
        show("COMPLETED (6)")
        ui.onNodeWithText("COMPLETED (6)").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Button))
        ui.runOnIdle { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        ui.onNodeWithText("COMPLETED (6)").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithText("COMPLETED (6)").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        ui.onNodeWithText("COMPLETED (6)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
        capture("large-completed")
    }
}
