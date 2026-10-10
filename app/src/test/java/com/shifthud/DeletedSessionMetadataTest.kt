package com.shifthud

import android.app.NotificationManager
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.notification.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ShiftHudApplication::class)
class DeletedSessionMetadataTest {
    private val start=Instant.parse("2026-10-04T04:00:00Z")
    private val engine=ShiftEngine()
    @Test fun deletingRecordCleansItsWarningSnoozeReceiptAndKeepsPayHistory()=runBlocking {
        val app=RuntimeEnvironment.getApplication() as ShiftHudApplication
        val db=Room.inMemoryDatabaseBuilder(app,ShiftDatabase::class.java).build()
        try {
            val prefs=app.preferences
            val active=WorkSession(id=7,clockIn=start)
            prefs.deliverWarning(active,start,engine,true){true}
            prefs.deliverWarning(active,start.plusSeconds(330*60),engine,true){true}
            val attention=prefs.deliverAttention(active,start.plusSeconds(360*60),engine,true){_,_->true}.state!!
            assertTrue(prefs.snooze(7,attention.receipt,{active},start.plusSeconds(360*60),engine))
            app.payRates.save(PayRate(LocalDate.of(2026,10,1),1650));val rates=app.payRates.rates.first()
            val settings=prefs.warningSettings.first();val snooze=prefs.snoozeMinutes.first();val auto=prefs.autoLunchSettings.first()
            app.notifications.createChannels()
            val manager=app.getSystemService(NotificationManager::class.java)
            manager.notify(ShiftNotifications.WARNING_ID,app.notifications.attentionReminder(7,Duration.ofHours(6),attention,10))
            db.shifts().insert(active.copy(clockOut=start.plusSeconds(8*3600),state=ShiftState.COMPLETE,autoLunchMinutes=60,correctionRevision=2).entity())
            val repo=ShiftRepository(db,engine,onSessionDeleted={app.notifications.forgetSession(it)})
            repo.deleteHistorical(db.shifts().session(7)!!.model())
            assertNull(db.shifts().session(7));assertTrue(manager.activeNotifications.isEmpty())
            assertEquals(rates,app.payRates.rates.first());assertEquals(settings,prefs.warningSettings.first());assertEquals(snooze,prefs.snoozeMinutes.first());assertEquals(auto,prefs.autoLunchSettings.first())
            // A removed ledger is a fresh baseline; removed snooze has no target/generation.
            assertEquals("baseline",prefs.deliverWarning(active,start.plusSeconds(331*60),engine,false){false}.reason)
            val fresh=prefs.deliverAttention(active,start.plusSeconds(361*60),engine,false){_,_->false}.state!!
            assertEquals(0L,fresh.generation);assertNull(fresh.targetActiveMillis)
        } finally {db.close()}
    }
    @Test fun unrelatedSessionMetadataAndNotificationSurviveIdPrefixCollision()=runBlocking {
        val app=RuntimeEnvironment.getApplication() as ShiftHudApplication
        val prefs=app.preferences
        val active=WorkSession(id=10,clockIn=start)
        prefs.deliverWarning(active,start,engine,true){true}
        val before=prefs.deliverWarning(active,start.plusSeconds(330*60),engine,true){true}.ledger
        val attention=prefs.deliverAttention(active,start.plusSeconds(360*60),engine,true){_,_->true}.state!!
        assertTrue(prefs.snooze(10,attention.receipt,{active},start.plusSeconds(360*60),engine))
        app.notifications.createChannels();val manager=app.getSystemService(NotificationManager::class.java)
        manager.notify(ShiftNotifications.WARNING_ID,app.notifications.attentionReminder(10,Duration.ofHours(6),attention,10))
        app.notifications.forgetSession(1)
        assertEquals(1,manager.activeNotifications.size)
        assertEquals(before,prefs.deliverWarning(active,start.plusSeconds(331*60),engine,false){false}.ledger)
        val after=prefs.deliverAttention(active,start.plusSeconds(361*60),engine,false){_,_->false}.state!!
        assertEquals(1L,after.generation);assertNotNull(after.targetActiveMillis)
    }
}
