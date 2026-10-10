package com.shifthud

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.shifthud.data.local.*
import com.shifthud.data.preferences.PayRatePreferences
import com.shifthud.data.repository.QuickFindRepository
import com.shifthud.domain.pay.PayRate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class QuickFindMigrationTest {
    @Test fun versionTwoUpgradePreservesFullRecordsAndPayHistoryAndNewItemsSurviveReopen()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val name="quick-find-migration.db"
        context.deleteDatabase(name)
        val rateFile=File.createTempFile("migration-pay", ".preferences_pb").apply{delete()}
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val pay=PayRatePreferences(PreferenceDataStoreFactory.create(scope=scope,produceFile={rateFile}))
            pay.save(PayRate(LocalDate.of(2026,9,27),1650));pay.save(PayRate(LocalDate.of(2026,11,1),1700))
            val beforePay=pay.rates.first()
            scope.coroutineContext.job.cancelAndJoin()
            val schemaFile=listOf(File("schemas/com.shifthud.data.local.ShiftDatabase/2.json"),File("app/schemas/com.shifthud.data.local.ShiftDatabase/2.json")).first{it.exists()}
            val schema=JSONObject(schemaFile.readText()).getJSONObject("database")
            val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file,null).use {old ->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val e=entities.getJSONObject(i);val table=e.getString("tableName")
                    old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=e.optJSONArray("indices")?:org.json.JSONArray()
                    for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup=schema.getJSONArray("setupQueries")
                for(i in 0 until setup.length())old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO scheduled_shifts VALUES (7, 20731, 14400, 46800, 60, 'Keep schedule')")
                old.execSQL("INSERT INTO work_sessions VALUES (10, 7, 1000, 2000, 3000, 4000, 'COMPLETE', 60, 1, 3)")
                old.execSQL("INSERT INTO work_sessions VALUES (11, NULL, 5000, 6000, NULL, NULL, 'ON_LUNCH', 45, 0, 2)")
                old.version=2
            }
            var quickId=0L
            repeat(2) {pass ->
                val db=Room.databaseBuilder(context,ShiftDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
                try {
                    val schedule=db.shifts().scheduleSnapshot().single()
                    assertEquals(7L,schedule.id);assertEquals(20731L,schedule.date);assertEquals(14400,schedule.scheduledStart)
                    assertEquals(46800,schedule.scheduledEnd);assertEquals(60,schedule.plannedLunchMinutes);assertEquals("Keep schedule",schedule.notes)
                    val complete=db.shifts().session(10)!!
                    assertEquals(7L,complete.scheduledShiftId);assertEquals(1000L,complete.clockIn);assertEquals(2000L,complete.lunchStart)
                    assertEquals(3000L,complete.lunchEnd);assertEquals(4000L,complete.clockOut);assertEquals("COMPLETE",complete.state)
                    assertEquals(60,complete.autoLunchMinutes);assertTrue(complete.lunchEndAutomatic);assertEquals(3L,complete.correctionRevision)
                    val active=db.shifts().session(11)!!
                    assertNull(active.scheduledShiftId);assertEquals(5000L,active.clockIn);assertEquals(6000L,active.lunchStart)
                    assertNull(active.lunchEnd);assertNull(active.clockOut);assertEquals("ON_LUNCH",active.state)
                    assertEquals(45,active.autoLunchMinutes);assertFalse(active.lunchEndAutomatic);assertEquals(2L,active.correctionRevision)
                    val quick=QuickFindRepository(db)
                    if(pass==0) {assertTrue(quick.items.first().isEmpty());quickId=quick.save(null,"Honey","2","Near syrup","sweetener",true);quick.select(quickId)}
                    else {val item=quick.items.first().single();assertEquals(quickId,item.id);assertEquals("Honey",item.name);assertTrue(item.isFavorite);assertEquals(1L,item.useCount)}
                    assertEquals(6,db.openHelper.readableDatabase.version)
                } finally {db.close()}
            }
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val reopenedPay=PayRatePreferences(PreferenceDataStoreFactory.create(scope=scope,produceFile={rateFile}))
            assertEquals(beforePay,reopenedPay.rates.first())
        } finally {scope.coroutineContext.job.cancelAndJoin();rateFile.delete();context.deleteDatabase(name)}
        Unit
    }
}
