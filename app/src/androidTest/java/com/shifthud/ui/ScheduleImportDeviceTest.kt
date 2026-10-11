package com.shifthud.ui

import android.graphics.*
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.domain.scheduleimport.*
import com.shifthud.ui.paystub.PaystubOcr
import com.shifthud.ui.scheduleimport.*
import com.shifthud.ui.theme.ShiftHudTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*
import org.json.JSONArray

class ScheduleImportDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
    private val fixture="""Week October 17-23, 2026
Saturday Oct 17 4:00 PM - 11:00 PM 6h
Sunday Oct 18 10:00 AM - 6:00 PM 7h
Monday Oct 19 8:00 AM - 5:00 PM 8h
Tuesday Oct 20 8:00 AM - 5:00 PM 8h
Wednesday Oct 21 8:00 AM - 5:00 PM 8h
Displayed weekly net: 37 hours"""
    @Test fun onDeviceOcrPopulatesActualImageWithoutSaving()=runBlocking {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.packageManager.checkPermission(android.Manifest.permission.INTERNET,app.packageName))
        val before=app.backups.snapshot().data.toString()
        val bitmap=Bitmap.createBitmap(1800,900,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=44f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
        fixture.lines().forEachIndexed{i,line->canvas.drawText(line,50f,90f+i*100f,paint)}
        val file=File(app.cacheDir,"schedule-acceptance-fixture.png")
        try {
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            val text=PaystubOcr(app).read(Uri.fromFile(file),::scheduleOcrRows)
            val parsed=extractSchedule(text)
            assertEquals("Recognized: $text",5,parsed.rows.size)
            assertEquals(LocalTime.of(16,0),parsed.rows.first().start)
            assertEquals(37*60,parsed.weeklyNetMinutes)
            assertTrue(parsed.rows.all{it.lunch==60 && it.shift()!=null})
            assertEquals(before,app.backups.snapshot().data.toString())
        } finally{if(InstrumentationRegistry.getArguments().getString("phase")!="export_fixture")file.delete();bitmap.recycle()}
    }
    @Test fun reviewPrefillsSelectsAndCancelsWithoutSaving()=runBlocking {
        val parsed=extractSchedule(fixture)
        val state=SavedStateHandle(mapOf("scheduleRaw" to fixture,"scheduleWeek" to parsed.week.toString(),"scheduleWeekConfirmed" to true,
            "scheduleRows" to JSONArray().apply{parsed.rows.forEach{put(ReviewRow(it).json())}}.toString()))
        val vm=ScheduleImportViewModel(app,state)
        val before=app.backups.snapshot().data.toString()
        ui.setContent{ShiftHudTheme{ScheduleImportScreen(vm){}}}
        ui.onNodeWithText("Screenshot weekly net: 37h 0m").assertExists()
        ui.onNodeWithText("SELECT ALL VALID SHIFTS").performScrollTo().performClick()
        ui.onNodeWithText("REVIEW IMPORT (5)").performScrollTo().performClick()
        ui.onNodeWithText("Import confirmed shifts?").assertIsDisplayed()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(before,app.backups.snapshot().data.toString())
    }
    /** Opt-in only on an empty disposable emulator; ordinary suite is read-only. */
    @Test fun confirmedImportPersistenceAcceptance()=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("phase")!="schedule_import")return@runBlocking
        check(app.repository.schedule.first().isEmpty())
        val before=app.repository.sessions.first();val pay=app.payroll.paychecks.first()
        val parsed=extractSchedule(fixture)
        val choices=parsed.rows.map{ScheduleImportChoice(it.shift()!!)}
        val result=app.repository.importSchedule(choices,emptyList(),parsed.week!!)
        assertEquals(5,result.imported)
        assertEquals(5,app.repository.importSchedule(choices,app.repository.schedule.first(),parsed.week).duplicates)
        assertEquals(before,app.repository.sessions.first());assertEquals(pay,app.payroll.paychecks.first())
    }
}
