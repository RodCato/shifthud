package com.shifthud

import android.app.Application
import com.shifthud.data.preferences.ShiftPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class DecimalPreferencesTest {
    @Test fun defaultsEnabledAndPersistsWithoutChangingPlanningSettings() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val preferences=ShiftPreferences(context)
        assertTrue(preferences.showDecimalHours.first())
        val planner=preferences.weeklyPlannerSettings.first()
        val target=preferences.weeklyTargetSettings.first()
        preferences.setShowDecimalHours(false)
        assertFalse(ShiftPreferences(context).showDecimalHours.first())
        assertEquals(planner,preferences.weeklyPlannerSettings.first())
        assertEquals(target,preferences.weeklyTargetSettings.first())
        preferences.setShowDecimalHours(true)
        assertTrue(ShiftPreferences(context).showDecimalHours.first())
    }
}
