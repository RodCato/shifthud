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
    private suspend fun passportDocument():com.shifthud.ui.paystub.OcrDocument {
        val bitmap=Bitmap.createBitmap(1100,2050,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=38f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
        canvas.drawText("10/17/2026",340f,70f,paint)
        canvas.drawText("Weekly total: 37 hours",340f,130f,paint)
        listOf("SAT","SUN","MON","TUE","WED","THU","FRI").forEachIndexed { i,day ->
            val y=230f+i*250f
            canvas.drawText(day,50f,y+20f,paint);canvas.drawText("${17+i}",75f,y+65f,paint)
            if(i<5) {
                canvas.drawText(when(i){0->"4 PM - 11 PM";1->"10 AM - 6 PM";else->"8 AM - 5 PM"},300f,y,paint)
                canvas.drawText("Grocery",300f,y+50f,paint);canvas.drawText("Store #1234",300f,y+100f,paint)
                canvas.drawText("${if(i==0)6 else if(i==1)7 else 8} net hours",300f,y+150f,paint)
                canvas.drawText(">",1000f,y+65f,paint)
            } else if(i==5)canvas.drawText("Not scheduled",300f,y+20f,paint)
        }
        val file=File(app.cacheDir,"passport-geometry-fixture.png")
        try {
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            return PaystubOcr(app).readDocument(Uri.fromFile(file))
        } finally {
            if(InstrumentationRegistry.getArguments().getString("exportPassportFixture")!="true")file.delete()
            bitmap.recycle()
        }
    }
    @Test fun passportImageGeometryPrefillsFiveCompleteReviewRows()=runBlocking {
        val before=app.backups.snapshot().data.toString()
        val document=passportDocument()
        val parsed=extractPassportSchedule(document)
        val shifts=parsed.rows.filter{!it.off}
        assertEquals("Recognized: $document",5,shifts.size)
        assertEquals(LocalDate.of(2026,10,17),parsed.week)
        assertEquals(LocalTime.of(16,0),shifts.first().start)
        assertTrue(shifts.all{it.confidence==Confidence.HIGH && it.lunch==60})
        assertEquals(37*60,shifts.sumOf{it.netMinutes!!})
        assertEquals(1,parsed.rows.count{it.off})
        val state=SavedStateHandle(mapOf("scheduleRaw" to com.shifthud.ui.paystub.ocrRows(document.lines.map{it.bounds}),
            "scheduleGeometry" to document.encodeGeometry(),"scheduleWeek" to parsed.week.toString(),"scheduleWeekConfirmed" to true,
            "scheduleRows" to JSONArray().apply{parsed.rows.forEach{put(ReviewRow(it).json())}}.toString()))
        val vm=ScheduleImportViewModel(app,state)
        ui.setContent{ShiftHudTheme{ScheduleImportScreen(vm){}}}
        ui.onNodeWithText("Review projection: 37h 0m paid").assertExists()
        ui.onNodeWithText("Recognized hours match the total. This does not prove all days are visible.").assertExists()
        ui.onNodeWithText("SELECT ALL VALID SHIFTS").performScrollTo().performClick()
        ui.onNodeWithText("REVIEW IMPORT (5)").performScrollTo().performClick()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(before,app.backups.snapshot().data.toString())
    }
    /** Opt-in only on an empty disposable emulator; ordinary suite is read-only. */
    @Test fun confirmedImportPersistenceAcceptance()=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("phase")!="schedule_import")return@runBlocking
        check(app.repository.schedule.first().isEmpty())
        val before=app.repository.sessions.first();val pay=app.payroll.paychecks.first()
        val parsed=extractPassportSchedule(passportDocument())
        val choices=parsed.rows.filter{!it.off}.map{ScheduleImportChoice(it.shift()!!)}
        val result=app.repository.importSchedule(choices,emptyList(),parsed.week!!)
        assertEquals(5,result.imported)
        assertEquals(5,app.repository.importSchedule(choices,app.repository.schedule.first(),parsed.week).duplicates)
        assertEquals(before,app.repository.sessions.first());assertEquals(pay,app.payroll.paychecks.first())
    }
}
