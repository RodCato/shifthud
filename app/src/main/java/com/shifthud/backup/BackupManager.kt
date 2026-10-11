package com.shifthud.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import com.shifthud.ShiftHudApplication
import com.shifthud.data.local.ShiftDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.*

/** Durable commit marker is written before deleting the original; cleanup can be retried. */
class RestoreJournal(private val root:File) {
    private val file=File(root,"restore-original.json")
    private val committed=File(root,"restore-committed")
    val pending get()=file.exists() || committed.exists()
    val committedDigest:String? get()=if(committed.exists())committed.readText().also{require(it.matches(Regex("[0-9a-f]{64}")))} else null
    fun original()=BackupSnapshot.decode(file.readBytes())
    fun prepare(snapshot:BackupSnapshot) {
        check(!pending){"A previous restore needs recovery first."}
        root.mkdirs();syncDirectory(root.parentFile!!)
        writeAtomic(file,snapshot.bytes())
    }
    fun markCommitted(digest:String)=writeAtomic(committed,digest.toByteArray())
    fun revokeCommit() {if(committed.exists())check(committed.delete());syncDirectory()}
    fun finishRollback() {revokeCommit();if(file.exists())check(file.delete());syncDirectory()}
    fun finishCommitted() {
        check(committed.exists())
        if(file.exists())check(file.delete())
        syncDirectory()
        check(committed.delete());syncDirectory()
    }
    private fun writeAtomic(destination:File,bytes:ByteArray) {
        val temporary=File(root,destination.name+".tmp")
        FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()}
        check(temporary.renameTo(destination));syncDirectory()
    }
    private fun syncDirectory(directory:File=root) {
        java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
    }
}
class BackupManager(private val app:ShiftHudApplication,private val fault:(String)->Unit = {}) {
    private val preferences get()=BackupPreferences(app,app.preferences,app.payRates)
    private val journal=RestoreJournal(File(app.noBackupFilesDir,"restore"))
    val busy=MutableStateFlow(false)
    private val db get()=app.database
    suspend fun snapshot(runtime:Boolean=false):BackupSnapshot=DataGate.with {
        val tables=captureDatabase(db)
        val prefs=preferences.snapshot(runtime)
        val counts=JSONObject().apply{BACKUP_TABLES.forEach{put(it,tables.getJSONArray(it).length())}}
        val manifest=JSONObject().put("format",1).put("payloadVersion",1).put("databaseVersion",6)
            .put("appVersion",app.packageManager.getPackageInfo(app.packageName,0).versionName?:"unknown")
            .put("preferenceCounts",JSONObject().apply{prefs.namesSet().forEach{put(it,prefs.getJSONObject(it).length())}})
            .put("soundReferences",org.json.JSONArray().apply{
                app.getSystemService(android.app.NotificationManager::class.java).notificationChannels.forEach{channel->
                    channel.sound?.let{put(JSONObject().put("channel",channel.id).put("uri",it.toString()))}
                }
            })
            .put("created",Instant.now().toString()).put("zone",ZoneId.systemDefault().id).put("counts",counts)
        BackupSnapshot(manifest,JSONObject().put("tables",tables).put("preferences",prefs).put("sequences",captureSequences(db)))
    }
    suspend fun export(password:CharArray):ByteArray=withContext(Dispatchers.IO) {
        val snapshot=DataGate.with {
            check(db.shifts().active()==null){"A shift is active. Tracking continues; create a backup after clocking out."}
            snapshot()
        }
        validate(snapshot)
        BackupCrypto.encrypt(snapshot.bytes(),password)
    }
    suspend fun preview(file:ByteArray,password:CharArray):BackupSnapshot=withContext(Dispatchers.IO) {
        val plain=BackupCrypto.decrypt(file,password)
        try{BackupSnapshot.decode(plain).also{validate(it)}}finally{plain.fill(0)}
    }
    suspend fun validate(snapshot:BackupSnapshot,runtime:Boolean=false)=withContext(Dispatchers.IO) {
        require(snapshot.bytes().size<=BackupCrypto.MAX_BYTES){"Dataset exceeds the supported recovery size; no records were changed."}
        require(snapshot.manifest.getInt("format")==1 && snapshot.manifest.getInt("payloadVersion")==1 && snapshot.manifest.getInt("databaseVersion")==6)
        require(snapshot.data.namesSet()==setOf("tables","preferences","sequences"))
        BackupPreferences.validate(snapshot.data.getJSONObject("preferences"),runtime)
        val preferenceCounts=snapshot.manifest.getJSONObject("preferenceCounts")
        require(preferenceCounts.namesSet()==setOf("shift","rates","upcoming"))
        preferenceCounts.namesSet().forEach{require(preferenceCounts.getInt(it)==snapshot.data.getJSONObject("preferences").getJSONObject(it).length()){"Preference count mismatch."}}
        require(snapshot.manifest.getJSONObject("counts").namesSet()==BACKUP_TABLES.toSet())
        snapshot.counts.forEach{(key,n)->require(snapshot.manifest.getJSONObject("counts").getInt(key)==n){"Record count mismatch."}}
        val staging=Room.inMemoryDatabaseBuilder(app,ShiftDatabase::class.java).build()
        try {
            replaceDatabase(staging,snapshot.data.getJSONObject("tables"),snapshot.data.getJSONObject("sequences"))
            validateRecords(staging)
            check(staging.shifts().active()==null){"This backup contains an active shift. It cannot be resumed or silently discarded. Finish/correct the shift on the source installation, then create a new backup. This file and existing records are unchanged."}
        }finally{staging.close()}
    }
    private suspend fun apply(snapshot:BackupSnapshot,inject:Boolean=false) {
        replaceDatabase(db,snapshot.data.getJSONObject("tables"),snapshot.data.getJSONObject("sequences"))
        if(inject)fault("database")
        preferences.replace(snapshot.data.getJSONObject("preferences"))
        if(inject)fault("preferences")
        db.invalidationTracker.refreshVersionsAsync()
    }
    /** Called before any UI/service/worker can access the application dataset. Retry is idempotent. */
    suspend fun recover() {
        if(!journal.pending)return
        DataGate.with {
            val committed=journal.committedDigest
            if(committed!=null) {
                val current=snapshot()
                check(BackupSnapshot.digest(canonical(current.data).toByteArray())==committed){"Committed restore verification failed. Original recovery data is retained."}
                journal.finishCommitted()
            } else {
                val old=journal.original()
                validate(old,runtime=true)
                apply(old)
                verify(old,true)
                durableDatabase()
                journal.finishRollback()
            }
        }
    }
    private suspend fun verify(expected:BackupSnapshot,runtime:Boolean) {
        val actual=snapshot(runtime)
        // JSONObject key order is not a data property.
        check(canonical(actual.data)==canonical(expected.data)){"Restore verification failed."}
    }
    suspend fun restore(snapshot:BackupSnapshot)=withContext(Dispatchers.IO+NonCancellable) {
        busy.value=true
        try {
            validate(snapshot)
            DataGate.replace {
                check(db.shifts().active()==null){"Cannot replace data during an active shift. Clock out first."}
                val original=snapshot(true)
                validate(original,runtime=true)
                var prepared=false
                var committed=false
                try {
                    journal.prepare(original)
                    prepared=true
                    fault("journal")
                    apply(snapshot,true)
                    verify(snapshot,false)
                    fault("verified")
                    primeReminders()
                    durableDatabase()
                    fault("checkpoint")
                    journal.markCommitted(BackupSnapshot.digest(canonical(snapshot.data).toByteArray()))
                    committed=true
                    fault("committed")
                    journal.finishCommitted()
                }catch(e:Exception) {
                    if(committed) {
                        app.blockForRecovery()
                        throw IllegalStateException("Restore is committed and verified, but cleanup needs recovery. Force-stop and reopen ShiftHUD; do not clear data.",e)
                    }
                    if(!prepared && !journal.pending)throw IllegalStateException("Could not stage recovery data. Existing records are unchanged; free storage and retry.",e)
                    try{
                        journal.revokeCommit()
                        apply(original);verify(original,true);durableDatabase();journal.finishRollback()
                    }
                    catch(recovery:Exception){app.blockForRecovery();throw IllegalStateException("Recovery is pending. Close and reopen ShiftHUD to retry recovery. Do not clear app data.",recovery)}
                    throw IllegalStateException("Restore failed; your original dataset was restored. Try another backup or destination.",e)
                }
            }
            val refreshed=runCatching {
                app.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                check(app.widgetRefresh.refresh(mayStartService=false))
                app.upcoming.reschedule()
            }.isSuccess
            if(refreshed)"Restore completed and verified. Review Android notification settings and widget placement."
            else "Restore completed and verified. Runtime refresh is pending; reopen ShiftHUD to rebuild widgets and reminders."
        }finally{busy.value=false}
    }
    /** Room may use NORMAL WAL sync: flush it before the cross-store commit marker is durable. */
    private fun durableDatabase() {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor->
            check(cursor.moveToFirst() && cursor.getInt(0)==0 && cursor.getLong(1)==cursor.getLong(2)){"Database checkpoint is busy; recovery data is retained."}
        }
        val databaseFile=app.getDatabasePath("shifthud.db")
        listOf(databaseFile,File(databaseFile.path+"-wal")).filter{it.exists()}.forEach{file->
            java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.WRITE).use{it.force(true)}
        }
        java.nio.channels.FileChannel.open(databaseFile.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
    }
    private suspend fun primeReminders() {
        val now=Instant.now();val zone=ZoneId.systemDefault()
        val schedule=app.repository.snapshot().schedule
        // Establish the current boundary baseline without posting historical alerts.
        app.preferences.deliverWeeklyWarning(app.repository.weeklySessions(now,zone),schedule,now,zone){_,_->true}
        val settings=app.upcoming.store.settings()
        val plan=com.shifthud.notification.upcoming.upcomingPlan(now,zone,settings,schedule,null,android.text.format.DateFormat.is24HourFormat(app))
        if(plan.content!=null)app.upcoming.store.acknowledge(com.shifthud.notification.upcoming.UpcomingReceipt(plan.date,plan.fingerprint,now))
    }
    suspend fun soundWarnings(snapshot:BackupSnapshot):List<String> = withContext(Dispatchers.IO) {
        val sounds=snapshot.manifest.optJSONArray("soundReferences")?:return@withContext emptyList()
        (0 until sounds.length()).mapNotNull { i->
            val row=sounds.getJSONObject(i);val uri=row.getString("uri").toUri()
            val available=android.media.RingtoneManager.isDefault(uri) || uri.scheme in setOf("content","android.resource") && runCatching{
                app.contentResolver.openAssetFileDescriptor(uri,"r")?.use{true}?:false
            }.getOrDefault(false)
            if(available)null else "Source sound for ${row.getString("channel")} is unavailable here. Reselect it in Android notification settings."
        }
    }
    suspend fun writeVerified(uri:Uri,encrypted:ByteArray)=withContext(Dispatchers.IO) {
        writeAndVerify(encrypted,{app.contentResolver.openOutputStream(uri,"wt")},{app.contentResolver.openInputStream(uri)})
    }
    suspend fun readFile(uri:Uri):ByteArray=withContext(Dispatchers.IO) {
        val bytes=app.contentResolver.openInputStream(uri)?.use{it.readBytesBounded()}?:error("Cannot open backup.")
        bytes
    }
}
internal fun java.io.InputStream.readBytesBounded():ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=BackupCrypto.MAX_BYTES){"Backup exceeds 32 MiB."};out.write(buffer,0,n)}
    return out.toByteArray()
}
internal fun canonical(value:Any?):String=when(value){
    is JSONObject->value.namesSet().sorted().joinToString(prefix="{",postfix="}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
    is org.json.JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]"){canonical(value.get(it))}
    is String->JSONObject.quote(value)
    null, JSONObject.NULL->"null"
    else->value.toString()
}

internal fun writeAndVerify(encrypted:ByteArray,output:()->java.io.OutputStream?,input:()->java.io.InputStream?) {
    output()?.use{it.write(encrypted);it.flush()}?:error("Cannot open destination.")
    val read=input()?.use{it.readBytesBounded()}?:error("Cannot reopen destination to verify it.")
    try{check(read.contentEquals(encrypted)){"File verification failed. Choose another destination; this file is not a verified backup."}}
    finally{read.fill(0)}
}
