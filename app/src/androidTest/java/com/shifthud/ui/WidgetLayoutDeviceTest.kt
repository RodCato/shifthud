package com.shifthud.ui

import android.content.Context
import android.widget.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.glance.*
import androidx.glance.appwidget.*
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.widget.*
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.domain.planner.PlannerSettings
import com.shifthud.domain.weekly.WeeklyTargetSettings
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class WidgetLayoutDeviceTest {
    @get:Rule val ui=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @OptIn(ExperimentalGlanceApi::class)
    @Test fun realRemoteViewsKeepLunchTimerAndActionVisibleAcrossSizes()=runBlocking {
        val now=Instant.now()
        val session=WorkSession(9966,clockIn=now.minusSeconds(5*3600),lunchStart=now.minusSeconds(31*60),state=ShiftState.ON_LUNCH)
        var state=WidgetStateFactory(ShiftEngine()).create(emptyList(),session,360,now,ZoneId.systemDefault(),Locale.US)
            .withMetrics(listOf(session),emptyList(),PlannerSettings(),WeeklyTargetSettings(),now,ZoneId.systemDefault())
        val widget=object:GlanceAppWidget() {
            override val sizeMode=SizeMode.Exact
            override suspend fun provideGlance(context:Context,id:GlanceId) { provideContent { WidgetContent(state,context,WidgetFrozenState(LocalDate.now(),7,11,(1..11).map {WidgetFrozenTask("$it","Persisted responsibility $it",it>4)},true,7)) } }
        }
        var chosen by mutableStateOf(ShiftHudWidget.COMPACT)
        var remote by mutableStateOf<android.widget.RemoteViews?>(null)
        var root:android.appwidget.AppWidgetHostView?=null
        ui.setContent { AndroidView(modifier=Modifier.size(chosen),factory={android.appwidget.AppWidgetHostView(it).also { host ->
            root=host
            host.setAppWidget(0,android.appwidget.AppWidgetManager.getInstance(context).installedProviders.single { info -> info.provider.className=="com.shifthud.widget.ShiftHudWidgetReceiver" })
            host.setPadding(0,0,0,0)
        }},update={ frame ->
            remote?.let { frame.updateAppWidget(it) }
        }) }
        fun texts(view:android.view.View):List<TextView> = if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else listOfNotNull(view as? TextView)
        val cases=listOf(ShiftHudWidget.COMPACT,ShiftHudWidget.NORMAL,ShiftHudWidget.EXPANDED,ShiftHudWidget.TALL,ShiftHudWidget.LARGE,DpSize(320.dp,1100.dp)).map { it to 31 } + listOf(26,60,65).map { ShiftHudWidget.LARGE to it }
        for((size,minutes) in cases) {
            val sample=session.copy(lunchStart=now.minusSeconds(minutes*60L))
            state=WidgetStateFactory(ShiftEngine()).create(emptyList(),sample,360,now,ZoneId.systemDefault(),Locale.US)
                .withMetrics(listOf(sample),emptyList(),PlannerSettings(),WeeklyTargetSettings(),now,ZoneId.systemDefault())
            val result=widget.compose(context,size=size)
            ui.runOnIdle { chosen=size;remote=result }
            ui.waitForIdle()
            ui.waitUntil(5000) {
                var ready=false
                ui.runOnUiThread { ready=texts(root!!).any { it.text.toString()=="END LUNCH" && it.height>0 } }
                ready
            }
            if(size.width>=320.dp && size.height>=300.dp) ui.waitUntil(5000) {
                var ready=false
                ui.runOnUiThread { ready=texts(root!!).any { it.text.toString().startsWith("WEEKLY ·") } }
                ready
            }
            ui.runOnIdle {
                val all=texts(root!!)
                assertFalse(all.any {it.text.toString().contains("FAVORITES") || it.text.toString().contains("AISLE GUIDE")})
                if(size.width>=320.dp && size.height>=300.dp) {
                    listOf("OPEN APP","TASKS","QUICK FIND").forEach {label->
                        val link=all.single {it.text.toString()==label}
                        val bounds=android.graphics.Rect()
                        assertTrue("$label visible",link.getGlobalVisibleRect(bounds))
                        val clickable=generateSequence(link as android.view.View) {it.parent as? android.view.View}
                            .takeWhile {it!==root}.any {it.hasOnClickListeners()}
                        assertTrue("$label has a clickable Glance container",clickable)
                    }
                }
                if(size.width>=320.dp) {
                    val add=all.single {it.text.toString()=="+1 FLOAT"}
                    val bounds=android.graphics.Rect()
                    assertTrue("Float action directly visible",add.getGlobalVisibleRect(bounds))
                    assertEquals("Float action not clipped",add.height,bounds.height());assertTrue(add.hasOnClickListeners())
                }
                if(size.height>=1100.dp && context.resources.configuration.fontScale<=1.3f) {
                    val rows=all.filter {it.text.toString().contains("Persisted responsibility")}
                    assertEquals("All eleven tasks fit",11,rows.size)
                    assertTrue(all.filterIsInstance<android.widget.CheckBox>().take(4).all {!it.isChecked})
                    assertTrue(all.filterIsInstance<android.widget.CheckBox>().drop(4).all {it.isChecked})
                    assertNotEquals(rows.first().currentTextColor,rows.last().currentTextColor)
                    rows.forEach {row->
                        val target=generateSequence(row as android.view.View) {it.parent as? android.view.View}
                            .takeWhile {it!==root}.firstOrNull {it.isClickable && it.height+1>=48*context.resources.displayMetrics.density}
                        assertNotNull("Checkbox has a 48dp clickable row",target)
                        val targetBounds=android.graphics.Rect()
                        assertTrue(target!!.getGlobalVisibleRect(targetBounds))
                        assertEquals(target.height,targetBounds.height())
                        assertTrue("Native interactive checkbox",row is android.widget.CheckBox && row.isClickable)
                        val bounds=android.graphics.Rect()
                        assertTrue(row.getGlobalVisibleRect(bounds))
                        assertEquals("Task fully visible",row.height,bounds.height())
                    }
                    assertFalse(all.any {it.text.contains("more tasks")})
                } else if(size.width>=320.dp) assertTrue(all.any {it.text.contains("more tasks — Open Tasks")})
                val end=all.single { it.text.toString()=="END LUNCH" }
                val clock=all.filterIsInstance<Chronometer>().single()
                assertEquals(state.lunchMetric!!.tone.argb.toInt(),clock.currentTextColor)
                assertTrue("Lunch clock is prominent",clock.textSize>all.single { it.text.toString()=="ON LUNCH" }.textSize)
                val bitmap=android.graphics.Bitmap.createBitmap(root!!.width,root!!.height,android.graphics.Bitmap.Config.ARGB_8888)
                root!!.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.cacheDir,"widget-${size.width.value.toInt()}-${size.height.value.toInt()}-${minutes}m.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                val rect=android.graphics.Rect()
                assertTrue("END LUNCH visible at $size: root=${root!!.width}x${root!!.height}, end=${end.width}x${end.height}, y=${end.y}",end.getGlobalVisibleRect(rect))
                assertEquals("No clipped action at $size",end.height,rect.height())
                assertTrue(end.hasOnClickListeners())
                all.firstOrNull { it.text.toString()=="QUICK FIND" }?.let { quickFind ->
                    assertTrue("Quick Find label fits its touch target",quickFind.layout.getLineBottom(quickFind.lineCount-1)<=quickFind.height)
                }
                assertTrue(all.any { it.contentDescription?.toString()==state.lunchMetric!!.label })
            }
        }
    }
    @OptIn(ExperimentalGlanceApi::class)
    @Test fun everyShiftStateKeepsItsDirectControlsVisible()=runBlocking {
        val now=Instant.now()
        val working=WorkSession(777,clockIn=now.minusSeconds(18000))
        val lunch=working.copy(state=ShiftState.ON_LUNCH,lunchStart=now.minusSeconds(1800))
        val after=lunch.copy(state=ShiftState.WORKING,lunchEnd=now.minusSeconds(600))
        val complete=after.copy(state=ShiftState.COMPLETE,clockOut=now.minusSeconds(300))
        val factory=WidgetStateFactory(ShiftEngine())
        val samples=listOf(null,working,lunch,after,complete)
        var state=factory.create(emptyList(),null,360,now,ZoneId.systemDefault(),Locale.US)
        val widget=object:GlanceAppWidget() {
            override val sizeMode=SizeMode.Exact
            override suspend fun provideGlance(context:Context,id:GlanceId) {provideContent {
                WidgetContent(state,context,WidgetFrozenState(LocalDate.now(),floatCount=3))
            }}
        }
        var chosen by mutableStateOf(ShiftHudWidget.COMPACT)
        var remote by mutableStateOf<RemoteViews?>(null)
        var root:android.appwidget.AppWidgetHostView?=null
        ui.setContent {AndroidView(modifier=Modifier.size(chosen),factory={android.appwidget.AppWidgetHostView(it).also {host->
            root=host;host.setPadding(0,0,0,0)
            host.setAppWidget(0,android.appwidget.AppWidgetManager.getInstance(context).installedProviders.single {info->info.provider.className=="com.shifthud.widget.ShiftHudWidgetReceiver"})
        }},update={host->remote?.let {host.updateAppWidget(it)}})}
        fun texts(view:android.view.View):List<TextView> = if(view is android.view.ViewGroup) (0 until view.childCount).flatMap {texts(view.getChildAt(it))} else listOfNotNull(view as? TextView)
        for(size in listOf(ShiftHudWidget.COMPACT,ShiftHudWidget.NORMAL,ShiftHudWidget.EXPANDED,ShiftHudWidget.LARGE)) {
            for(sample in samples) {
                state=factory.create(emptyList(),sample,360,now,ZoneId.systemDefault(),Locale.US)
                    .withMetrics(listOfNotNull(sample),emptyList(),PlannerSettings(),WeeklyTargetSettings(),now,ZoneId.systemDefault())
                val rendered=widget.compose(context,size=size)
                ui.runOnIdle {chosen=size;remote=rendered}
                val required=listOf(state.actionLabel)+listOfNotNull(state.clockOutCommand?.let {"CLOCK OUT"})
                ui.waitUntil(5000) {var ready=false;ui.runOnUiThread {ready=required.all {label->texts(root!!).any {it.text.toString()==label}}};ready}
                ui.runOnIdle {
                    required.forEach {label->
                        val button=texts(root!!).single {it.text.toString()==label}
                        val bounds=android.graphics.Rect()
                        assertTrue("$label visible at $size",button.getGlobalVisibleRect(bounds))
                        assertEquals("$label unclipped at $size",button.height,bounds.height())
                        assertTrue(button.hasOnClickListeners())
                        assertTrue("$label text fits",button.layout.getLineBottom(button.lineCount-1)<=button.height)
                    }
                    if(size.width>=320.dp) listOf("−1","+1 FLOAT","OPEN APP","TASKS","QUICK FIND").forEach {label->
                        val control=texts(root!!).single {it.text.toString()==label}
                        val bounds=android.graphics.Rect();assertTrue(control.getGlobalVisibleRect(bounds));assertEquals(control.height,bounds.height())
                    }
                }
            }
        }
    }
}
