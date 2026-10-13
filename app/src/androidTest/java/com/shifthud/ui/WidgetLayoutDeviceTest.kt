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
            override suspend fun provideGlance(context:Context,id:GlanceId) { provideContent { WidgetContent(state,context,listOf(QuickFindItem(1,"Coconut milk","2",isFavorite=true,createdAt=now,updatedAt=now)),listOf(AisleGuideEntry(1,"1","Juice",now,now),AisleGuideEntry(2,"2","Cereal, Coffee",now,now),AisleGuideEntry(3,"16","Bread, Peanut Butter, Rice Cakes",now,now))) } }
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
        val cases=listOf(ShiftHudWidget.COMPACT,ShiftHudWidget.NORMAL,ShiftHudWidget.EXPANDED,ShiftHudWidget.TALL,ShiftHudWidget.LARGE).map { it to 31 } + listOf(26,60,65).map { ShiftHudWidget.LARGE to it }
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
}
