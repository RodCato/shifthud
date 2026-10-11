package com.shifthud

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.shifthud.data.local.*
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.ShiftEngine
import kotlinx.coroutines.runBlocking
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
class WeeklyLunchMigrationTest {
    @Test fun versionSevenPreservesLegacyLunchAndActualRecordsAcrossReopen() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val name="migration-weekly-lunch.db"
        context.deleteDatabase(name)
        val schemaFile=listOf(File("schemas/com.shifthud.data.local.ShiftDatabase/7.json"),File("app/schemas/com.shifthud.data.local.ShiftDatabase/7.json")).first {it.exists()}
        val schema=JSONObject(schemaFile.readText()).getJSONObject("database")
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val queries=schema.getJSONArray("setupQueries")
            for(i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO scheduled_shifts VALUES (7, 20731, 14400, 46800, 45, 'Preserve me', 0, 1)")
            old.execSQL("INSERT INTO work_sessions VALUES (10, 7, 1000000, 2000000, NULL, NULL, 'ON_LUNCH', NULL, 0, 0, 0)")
            old.execSQL("INSERT INTO work_sessions VALUES (11, NULL, 1000, 2000, 3000, 4000, 'COMPLETE', NULL, 0, 0, 0)")
            old.version=7
        }
        repeat(2) {
            val db=Room.databaseBuilder(context,ShiftDatabase::class.java,name).addMigrations(MIGRATION_7_8).build()
            try {
                // Opening Room performs migration and validates the complete version-5 schema.
                val active=db.shifts().session(10)!!.model()
                assertEquals(Instant.ofEpochMilli(1000000),active.clockIn)
                assertEquals(Instant.ofEpochMilli(2000000),active.lunchStart)
                assertNull(active.autoLunchMinutes);assertFalse(active.lunchEndAutomatic);assertEquals(0,active.correctionRevision)
                assertEquals("Preserve me",db.shifts().scheduleSnapshot().single().notes)
                val schedule=db.shifts().scheduleSnapshot().single()
                assertEquals(45,schedule.plannedLunchMinutes);assertTrue(schedule.lunchPlanApplied)
                assertFalse(schedule.usesGlobalLunch);assertNull(schedule.employerNetMinutes);assertNull(schedule.employerLunchMinutes)
                val completed=db.shifts().session(11)!!.model()
                assertEquals(Instant.ofEpochMilli(3000),completed.lunchEnd)
                assertEquals(Instant.ofEpochMilli(4000),completed.clockOut)
                val repo=ShiftRepository(db,ShiftEngine())
                assertFalse(repo.reconcileAutoLunch(Instant.parse("2026-10-05T12:00:00Z")))
                assertEquals(8,db.openHelper.readableDatabase.version)
            } finally {db.close()}
        }
        context.deleteDatabase(name)
        Unit
    }
}
