package com.shifthud

import android.app.Application
import android.content.pm.ApplicationInfo
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.domain.usecase.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AutoLunchPreferencesTest {
    @Test fun enabledSixtyDefaultAndNormalChoicesPersist() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val p=ShiftPreferences(context)
        assertEquals(AutoLunchSettings(),p.autoLunchSettings.first())
        try {
            AUTO_LUNCH_CHOICES.forEach { n ->
                p.setAutoLunch(true,n)
                assertEquals(AutoLunchSettings(true,n),ShiftPreferences(context).autoLunchSettings.first())
            }
            p.setAutoLunch(false,45);assertFalse(p.autoLunchSettings.first().enabled)
        } finally {p.setAutoLunch(true,60)}
    }
    @Test fun twoMinuteChoiceIsDebugOnly() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val original=context.applicationInfo.flags
        try {
            context.applicationInfo.flags=original or ApplicationInfo.FLAG_DEBUGGABLE
            val debug=ShiftPreferences(context);debug.setAutoLunch(true,2)
            assertEquals(2,debug.autoLunchSettings.first().minutes)
            context.applicationInfo.flags=original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            val release=ShiftPreferences(context)
            assertEquals(60,release.autoLunchSettings.first().minutes)
            assertTrue(runCatching {release.setAutoLunch(true,2)}.isFailure)
            assertTrue(runCatching {release.setAutoLunch(true,0)}.isFailure)
        } finally {
            context.applicationInfo.flags=original
            ShiftPreferences(context).setAutoLunch(true,60)
        }
    }
}
