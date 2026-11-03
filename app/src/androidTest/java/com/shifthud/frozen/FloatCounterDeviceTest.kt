package com.shifthud.frozen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ui.theme.ShiftHudTheme
import com.shifthud.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*

class FloatCounterDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val job=SupervisorJob()
    private val file=File(context.cacheDir,"float-counter-isolated.preferences_pb")
    private val day=LocalDate.of(2026,11,2)
    private val store=FrozenTaskStore(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}),
        Clock.fixed(Instant.parse("2026-11-02T18:00:00Z"),ZoneOffset.UTC),{ZoneOffset.UTC})
    @After fun close()=runBlocking {job.cancelAndJoin();file.delete();Unit}
    @Test fun appAndWidgetExecutorShareCountAndRapidTapsPersist()=runBlocking {
        val actions=FloatActionExecutor(store)
        repeat(3){assertTrue(actions.execute(FloatCommand(1)))}
        ui.setContent {ShiftHudTheme {Surface(Modifier.width(320.dp).fillMaxHeight().systemBarsPadding()) {
            val counts by store.floats.collectAsState(initial=FloatCounts())
            val scope=rememberCoroutineScope()
            FloatCounter(counts,day,day) {delta->scope.launch {store.adjustFloat(delta)}}
        }}}
        ui.waitUntil(10000) {ui.onAllNodesWithContentDescription("Floats completed today: 3").fetchSemanticsNodes().isNotEmpty()}
        repeat(5){ui.onNodeWithText("+1 FLOAT").performClick()}
        ui.waitUntil(10000) {runBlocking {store.floats.first()[day]==8L}}
        ui.onNodeWithContentDescription("Subtract one float").performClick()
        ui.waitUntil(10000) {runBlocking {store.floats.first()[day]==7L}}
        assertEquals(7L,widgetFrozenState(store.tasks.first(),store.floats.first(),day).floatCount)
        assertTrue(store.tasks.first().isEmpty())
        ui.waitForIdle()
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(context.cacheDir,"float-counter.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
        Unit
    }
}
