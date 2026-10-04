package com.shifthud

import android.app.Application
import com.shifthud.data.preferences.ShiftPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetPreferencesTest {
    @Test fun thresholdSaveRefreshesAfterPersistenceAndInvalidSaveDoesNot() = runBlocking {
        var refreshes = 0
        lateinit var preferences: ShiftPreferences
        preferences = ShiftPreferences(RuntimeEnvironment.getApplication(), onChanged = {
            assertEquals(300, preferences.lunchThresholdMinutes.first())
            refreshes++
        })
        preferences.setLunchThreshold(300)
        assertEquals(1, refreshes)
        assertTrue(runCatching { preferences.setLunchThreshold(0) }.isFailure)
        assertEquals(300, preferences.lunchThresholdMinutes.first())
        assertEquals(1, refreshes)
    }
}
