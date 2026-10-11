package com.shifthud.backup

import android.database.Cursor
import androidx.room.withTransaction
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.domain.payroll.validatePaycheck
import kotlinx.coroutines.flow.first
import org.json.*
import java.security.MessageDigest
import java.time.*

val BACKUP_TABLES=listOf("scheduled_shifts","work_sessions","quick_find_items","aisle_guide","paychecks","payroll_lines","payroll_deposits")

data class BackupSnapshot(val manifest:JSONObject,val data:JSONObject) {
    val counts:Map<String,Int> get()=BACKUP_TABLES.associateWith{data.getJSONObject("tables").getJSONArray(it).length()}
    fun bytes():ByteArray {
        val raw=data.toString()
        return JSONObject().put("manifest",JSONObject(manifest.toString()).put("sha256",digest(raw.toByteArray())))
            .put("payload",raw).toString().toByteArray(Charsets.UTF_8)
    }
    companion object {
        fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun decode(bytes:ByteArray):BackupSnapshot {
            require(bytes.size<=BackupCrypto.MAX_BYTES)
            val envelope=JSONObject(bytes.toString(Charsets.UTF_8))
            require(envelope.namesSet()==setOf("manifest","payload"))
            val manifest=envelope.getJSONObject("manifest");val raw=envelope.getString("payload")
            require(manifest.getInt("format")==1 && manifest.getInt("payloadVersion")==1){"Unsupported backup version. Update ShiftHUD."}
            require(manifest.getInt("databaseVersion")==6){"Unsupported database schema. No data was changed."}
            require(manifest.getString("sha256")==digest(raw.toByteArray())){"Backup integrity check failed."}
            Instant.parse(manifest.getString("created"));ZoneId.of(manifest.getString("zone"))
            return BackupSnapshot(manifest,JSONObject(raw))
        }
    }
}

/** Portable typed rows, read inside a Room transaction (including WAL-visible committed data). */
internal suspend fun captureDatabase(db:ShiftDatabase):JSONObject=db.withTransaction {
    JSONObject().apply {BACKUP_TABLES.forEach{table->
        put(table,JSONArray().apply{db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { c->
            while(c.moveToNext())put(JSONObject().apply{c.columnNames.forEachIndexed{i,name->
                put(name,when(c.getType(i)){Cursor.FIELD_TYPE_NULL->JSONObject.NULL;Cursor.FIELD_TYPE_INTEGER->c.getLong(i);Cursor.FIELD_TYPE_STRING->c.getString(i);else->error("Unsupported database type.")})
            }})
        }})
    }}
}
internal suspend fun replaceDatabase(db:ShiftDatabase,tables:JSONObject,sequences:JSONObject?=null)=db.withTransaction {
    require(tables.namesSet()==BACKUP_TABLES.toSet()){"Missing or unsupported tables."}
    val sql=db.openHelper.writableDatabase
    BACKUP_TABLES.reversed().forEach{sql.execSQL("DELETE FROM $it")}
    BACKUP_TABLES.forEach { table->
        val columns=linkedMapOf<String,Pair<String,Boolean>>()
        sql.query("PRAGMA table_info($table)").use{c->while(c.moveToNext())columns[c.getString(1)]=c.getString(2) to (c.getInt(3)!=0)}
        val rows=tables.getJSONArray(table)
        require(rows.length()<=100000){"Too many $table records."}
        val ids=hashSetOf<Long>()
        for(i in 0 until rows.length()){
            val row=rows.getJSONObject(i);require(row.namesSet()==columns.keys){"Unsupported or missing fields in $table."}
            val args: Array<Any?> =columns.map{(name,info)->
                val v=row.get(name)
                if(v==JSONObject.NULL){require(!info.second);null}
                else when(info.first) {
                    "INTEGER"->{require(v is Int || v is Long);(v as Number).toLong()}
                    "TEXT"->{require(v is String && v.length<=100000);v}
                    else->error("Unsupported column.")
                }
            }.toTypedArray<Any?>()
            listOf("isFavorite","deductionsComplete","lunchEndAutomatic","manuallyEntered").filter{row.has(it)}.forEach{require(row.getLong(it) in 0L..1L)}
            val id=row.getLong("id");require(id>0 && ids.add(id)){"Duplicate or invalid record identifier."}
            sql.execSQL("INSERT INTO $table (${columns.keys.joinToString()}) VALUES (${columns.keys.joinToString{"?"}})",args)
        }
    }
    sql.query("PRAGMA foreign_key_check").use{require(!it.moveToFirst()){"Broken record relationships."}}
    // Keep future generated IDs clear of all restored IDs; no renumbering of restored relationships.
    if(sequences!=null)require(sequences.namesSet()==BACKUP_TABLES.toSet())
    BACKUP_TABLES.forEach{table->
        val maximum=sql.query("SELECT COALESCE(MAX(id),0) FROM $table").use{it.moveToFirst();it.getLong(0)}
        val seq=sequences?.get(table)?.let{require(it is Long || it is Int);(it as Number).toLong()}?:maximum
        require(seq>=maximum && seq<Long.MAX_VALUE){"Invalid ID sequence."}
        sql.execSQL("DELETE FROM sqlite_sequence WHERE name=?",arrayOf(table))
        sql.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)",arrayOf<Any>(table,seq))
    }
}

/** Run against an isolated staging database, never the user's database. */
internal suspend fun validateRecords(db:ShiftDatabase) {
    db.shifts().observeSessions().first().forEach { row->
        val s=row.model()
        require(row.correctionRevision>=0 && row.autoLunchMinutes?.let{it in 1..1440}!=false)
        require((s.clockOut==null)==(s.state!=com.shifthud.domain.model.ShiftState.COMPLETE))
        require(s.clockOut==null || s.clockOut>=s.clockIn)
        require(s.lunchStart==null || s.lunchStart>=s.clockIn)
        require(s.lunchEnd==null || s.lunchStart!=null && s.lunchEnd>=s.lunchStart)
        require(s.clockOut==null || (s.lunchStart==null || s.lunchStart<=s.clockOut) && (s.lunchEnd==null || s.lunchEnd<=s.clockOut))
        require(!row.lunchEndAutomatic || row.lunchEnd!=null)
    }
    db.shifts().scheduleSnapshot().forEach{r->r.model().end;require(r.plannedLunchMinutes?.let{it>=0}!=false)}
    db.payroll().observeAll().first().forEach{validatePaycheck(it.model());require(it.paycheck.revision>=0)}
    db.quickFind().observeItems().first().forEach{r->r.model();require(r.name.isNotBlank() && r.aisle.isNotBlank() && r.useCount>=0 && r.normalizedName==com.shifthud.domain.model.normalizeQuickFind(r.name))}
    db.aisleGuide().observe().first().forEach{r->r.model();require(r.aisle.isNotBlank() && r.normalizedAisle==com.shifthud.domain.model.normalizeQuickFind(r.aisle))}
}


internal suspend fun captureSequences(db:ShiftDatabase):JSONObject=db.withTransaction {
    JSONObject().apply{BACKUP_TABLES.forEach{table->
        val sequence=db.openHelper.readableDatabase.query("SELECT seq FROM sqlite_sequence WHERE name=?",arrayOf(table)).use{if(it.moveToFirst())it.getLong(0) else 0L}
        put(table,sequence)
    }}
}
