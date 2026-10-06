package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.*
import androidx.room.Room
import com.shifthud.data.preferences.PayRatePreferences
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PayRatePersistenceTest {
    @Test fun historyIsAtomicSurvivesReopenAndDoesNotTouchSessions() = runBlocking {
        val file=File.createTempFile("pay-history", ".preferences_pb").apply { delete() }
        val context=RuntimeEnvironment.getApplication()
        val db=Room.inMemoryDatabaseBuilder(context,ShiftDatabase::class.java).build()
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val session=WorkSession(id=7,clockIn=Instant.parse("2026-10-05T04:00:00Z"),clockOut=Instant.parse("2026-10-05T12:00:00Z"),state=ShiftState.COMPLETE)
            db.shifts().insert(session.entity())
            val before=db.shifts().session(7)
            var store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            var preferences=PayRatePreferences(store)
            assertEquals(DEFAULT_PAY_RATES,preferences.rates.first())
            coroutineScope {
                launch { preferences.save(PayRate(LocalDate.of(2026,10,6),1650)) }
                launch { preferences.save(PayRate(LocalDate.of(2026,11,1),1700)) }
            }
            preferences.save(PayRate(LocalDate.of(2026,10,6),1660))
            assertEquals(3,preferences.rates.first().size)
            scope.coroutineContext.job.cancelAndJoin()
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            preferences=PayRatePreferences(store)
            val history=preferences.rates.first()
            assertEquals(listOf(1600L,1660L,1700L),history.map {it.centsPerHour})
            assertEquals(1600L,applicableRate(history,session.clockIn,ZoneOffset.UTC).centsPerHour)
            assertEquals(before,db.shifts().session(7))
            assertEquals(listOf(before),db.shifts().observeSessions().first())
            assertEquals(2,db.openHelper.readableDatabase.version)
        } finally { scope.coroutineContext.job.cancelAndJoin();db.close();file.delete() }
        Unit
    }
    @Test fun corruptHistoryIsNotSilentlyDefaultedOrOverwritten() = runBlocking {
        val file=File.createTempFile("pay-invalid", ".preferences_pb").apply {delete()}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            store.edit {it[stringPreferencesKey("history_v1")]="invalid"}
            val preferences=PayRatePreferences(store)
            assertTrue(runCatching {preferences.rates.first()}.isFailure)
            assertTrue(runCatching {preferences.save(PayRate(LocalDate.of(2026,10,6),1650))}.isFailure)
            assertEquals("invalid",store.data.first()[stringPreferencesKey("history_v1")])
        } finally {scope.coroutineContext.job.cancelAndJoin();file.delete()}
        Unit
    }
}
