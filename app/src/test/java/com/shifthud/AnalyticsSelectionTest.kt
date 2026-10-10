package com.shifthud

import androidx.lifecycle.SavedStateHandle
import com.shifthud.ui.analytics.AnalyticsViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ShiftHudApplication::class)
class AnalyticsSelectionTest {
    @Test fun weekAndMonthNavigationRemainIndependentAndRestore() {
        val saved=SavedStateHandle(mapOf("weekly" to true,"week" to "2026-10-03","month" to "2024-02"))
        val app=RuntimeEnvironment.getApplication() as ShiftHudApplication
        val vm=AnalyticsViewModel(app,saved)
        vm.move(-1);assertEquals("2026-09-26",vm.week.value)
        vm.mode(false);vm.move(1);assertEquals("2024-03",vm.month.value);assertEquals("2026-09-26",vm.week.value)
        val restored=AnalyticsViewModel(app,saved);assertFalse(restored.weekly.value);assertEquals("2024-03",restored.month.value)
        restored.mode(true);restored.move(1);assertEquals("2026-10-03",restored.week.value);assertEquals("2024-03",restored.month.value)
    }
}
