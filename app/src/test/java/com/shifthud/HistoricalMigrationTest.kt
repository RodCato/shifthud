package com.shifthud

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.shifthud.data.local.*
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.*
import kotlinx.coroutines.runBlocking
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
class HistoricalMigrationTest {
    @Test fun versionFourPreservesAllTablesAndManualProvenanceSurvivesReopen()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val name="historical-migration.db"
        context.deleteDatabase(name)
        val schema=JSONObject(listOf(File("schemas/com.shifthud.data.local.ShiftDatabase/4.json"),File("app/schemas/com.shifthud.data.local.ShiftDatabase/4.json")).first{it.exists()}.readText()).getJSONObject("database")
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        val expected=mutableMapOf<String,List<List<String?>>>()
        val columns=mutableMapOf<String,List<String>>()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);val table=e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val fields=e.getJSONArray("fields")
                columns[table]=(0 until fields.length()).map{fields.getJSONObject(it).getString("columnName")}
                val indices=e.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO scheduled_shifts VALUES (7,20731,14400,46800,60,'Keep schedule')")
            old.execSQL("INSERT INTO work_sessions VALUES (10,7,1000,2000,3000,4000,'COMPLETE',60,1,3)")
            old.execSQL("INSERT INTO work_sessions VALUES (11,NULL,5000,6000,NULL,NULL,'ON_LUNCH',45,0,2)")
            old.execSQL("INSERT INTO quick_find_items VALUES (21,'Honey','honey','2','Near syrup','[\"sweetener\"]',1,1000,2000,3000,7)")
            old.execSQL("INSERT INTO aisle_guide VALUES (1,'5','5','Pasta, Rice',1000,2000)")
            columns.forEach { (table,fields) -> old.rawQuery("SELECT ${fields.joinToString()} FROM $table ORDER BY id",null).use { c ->
                expected[table]=buildList {while(c.moveToNext())add(fields.indices.map {if(c.isNull(it))null else c.getString(it)})}
            } }
            old.version=4
        }
        var manualId=0L
        try {
            repeat(2) { pass ->
                val db=Room.databaseBuilder(context,ShiftDatabase::class.java,name).addMigrations(MIGRATION_4_5).build()
                try {
                    columns.forEach { (table,fields) ->
                        db.openHelper.readableDatabase.query("SELECT ${fields.joinToString()} FROM $table ${if(table=="work_sessions") "WHERE id IN (10,11)" else ""} ORDER BY id").use { c ->
                            val actual=buildList {while(c.moveToNext())add(fields.indices.map{if(c.isNull(it))null else c.getString(it)})}
                            assertEquals(table,expected[table],actual)
                        }
                    }
                    assertFalse(db.shifts().session(10)!!.manuallyEntered);assertFalse(db.shifts().session(11)!!.manuallyEntered)
                    if(pass==0) {
                        // Insert before the ancient active session to avoid intentionally overlapping it.
                        val clock=Clock.fixed(Instant.parse("2026-10-06T18:00:00Z"),ZoneOffset.UTC)
                        val repo=ShiftRepository(db,ShiftEngine(clock),clock)
                        manualId=repo.addHistorical(HistoricalShiftInput(LocalDate.of(1969,12,30),LocalTime.of(4,0),LocalTime.of(13,0)),ZoneOffset.UTC)
                    } else assertTrue(db.shifts().session(manualId)!!.manuallyEntered)
                    assertEquals(5,db.openHelper.readableDatabase.version)
                } finally {db.close()}
            }
        } finally {context.deleteDatabase(name)}
    }
}
