package com.shifthud

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shifthud.data.preferences.EmployeeNumberPreferences
import com.shifthud.widget.employeeNumberForHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class EmployeeNumberTest {
    private val file=Files.createTempDirectory("employee-test").resolve("employee.preferences_pb").toFile()
    private var job=SupervisorJob()
    private var refreshes=0
    private fun create()=EmployeeNumberPreferences(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})) {refreshes++}
    private var preferences=create()
    @After fun close()=runBlocking {job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit}
    @Test fun savesEditsClearsAndPreservesTextAcrossRestart()=runBlocking {
        assertEquals("",preferences.value.first())
        preferences.set("  0012345  ")
        assertEquals("0012345",preferences.value.first());assertEquals(1,refreshes)
        preferences.set("0012345");assertEquals(1,refreshes)
        job.cancelAndJoin();job=SupervisorJob();preferences=create()
        assertEquals("0012345",preferences.value.first())
        preferences.set("00009");assertEquals("00009",preferences.value.first())
        preferences.set(" ");assertEquals("",preferences.value.first());assertEquals(3,refreshes)
        job.cancelAndJoin();job=SupervisorJob();preferences=create()
        assertEquals("",preferences.value.first())
    }
    @Test fun noNumericConversionOrArbitraryLengthLimit()=runBlocking {
        val long="0".repeat(200)+"12"
        preferences.set(long);assertEquals(long,preferences.value.first())
        preferences.set("A001");assertEquals("A001",preferences.value.first())
    }
    @Test fun employeeRowIsRestrictedToHomeScreenHosts() {
        val home=AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        val lock=AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
        assertEquals("0012345",employeeNumberForHost("0012345",home))
        assertEquals("",employeeNumberForHost("0012345",lock))
        assertEquals("",employeeNumberForHost("0012345",home or lock))
        assertEquals("",employeeNumberForHost("0012345",0))
        assertEquals("",employeeNumberForHost("",home))
    }
    @Test fun settingsIntegrationRefreshesWidgetWithoutNotificationSettingsOrExportableData()=runBlocking {
        var employeeRefresh=0;var generalRefresh=0
        val settings=com.shifthud.data.preferences.ShiftPreferences(org.robolectric.RuntimeEnvironment.getApplication(),
            onEmployeeChanged={employeeRefresh++},onChanged={generalRefresh++})
        settings.setEmployeeNumber(" 00042 ")
        assertEquals("00042",settings.employeeNumber.first())
        assertEquals(1,employeeRefresh);assertEquals(0,generalRefresh)
        assertFalse(settings.store.data.first().asMap().keys.any {it.name.contains("employee")})
        settings.setLunchThreshold(settings.lunchThresholdMinutes.first());assertEquals(1,generalRefresh)
    }
}
