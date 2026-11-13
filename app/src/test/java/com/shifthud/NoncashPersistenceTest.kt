package com.shifthud

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.shifthud.data.local.*
import com.shifthud.data.repository.PayrollRepository
import com.shifthud.domain.payroll.*
import com.shifthud.ui.payroll.PayrollDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class NoncashPersistenceTest {
    @Test fun additiveMigrationPreservesPayrollAndUserCanCorrectWithoutReimport()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="noncash-migration.db"
        context.deleteDatabase(name)
        val schema=JSONObject(listOf(File("schemas/com.shifthud.data.local.ShiftDatabase/8.json"),File("app/schemas/com.shifthud.data.local.ShiftDatabase/8.json")).first{it.exists()}.readText()).getJSONObject("database")
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);val table=e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val indexes=e.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indexes.length())old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            val p=noncashFixture()
            old.execSQL("INSERT INTO paychecks VALUES (1,?,?,?,'36.19',67904,48675,1,'Preserve notes','Original',3)",arrayOf(p.periodStart.toEpochDay(),p.periodEnd.toEpochDay(),p.payDate!!.toEpochDay()))
            old.execSQL("INSERT INTO payroll_lines VALUES (8,1,'DEDUCTION','Taxes withheld',9229)")
            old.execSQL("INSERT INTO payroll_deposits VALUES (9,1,?,48675)",arrayOf(p.payDate.toEpochDay()))
            old.version=8
        }
        try {
            var corrected:Paycheck?=null
            repeat(2) { pass ->
                val db=Room.databaseBuilder(context,ShiftDatabase::class.java,name).addMigrations(MIGRATION_8_9).build()
                try {
                    val repo=PayrollRepository(db);val saved=repo.paychecks.first().single()
                    if(pass==0) {
                        assertEquals(1L,saved.id);assertEquals(3L,saved.revision);assertEquals("Preserve notes",saved.notes)
                        assertEquals(67904L,saved.grossCents);assertEquals(48675L,saved.netCents)
                        assertNull(saved.lines.single().taxable);assertFalse(saved.lines.single().includedInGross)
                        assertEquals(10000L,cashNetDifference(saved))
                        val draft=PayrollDraft.from(saved.copy(lines=saved.lines+noncashFixture().lines.last()))
                        val model=PayrollDraft.restore(draft.serialize()).model()
                        assertEquals(draft.model(),model)
                        repo.save(model);corrected=repo.paychecks.first().single()
                        assertEquals(saved.deposits,corrected!!.deposits);assertEquals(saved.lines.single(),corrected!!.lines.first())
                    } else { assertEquals(corrected,saved);assertEquals(0L,cashNetDifference(saved));assertEquals("Gift Card",saved.lines.last().category) }
                    assertEquals(9,db.openHelper.readableDatabase.version)
                    assertTrue(db.shifts().observeSessions().first().isEmpty())
                } finally {db.close()}
            }
        } finally {context.deleteDatabase(name)}
    }
}
