package com.shifthud.backup

import android.app.NotificationManager
import androidx.room.Room
import androidx.datastore.preferences.core.*
import com.shifthud.ShiftHudApplication
import com.shifthud.data.local.*
import com.shifthud.data.local.entity.*
import com.shifthud.domain.model.*
import com.shifthud.domain.payroll.*
import com.shifthud.domain.pay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ShiftHudApplication::class)
class BackupRoundTripTest {
    private val app get()=RuntimeEnvironment.getApplication() as ShiftHudApplication
    private val manager get()=app.backups
    @Before fun unblock(){DataGate.blocked=false}
    @After fun cleanup(){DataGate.blocked=false;File(app.noBackupFilesDir,"restore").deleteRecursively()}
    private suspend fun seed() {
        val db=app.database
        db.shifts().save(ScheduledShiftEntity(20,LocalDate.of(2026,9,26).toEpochDay(),14400,50400,60,"Unicode café 🥥"))
        com.shifthud.domain.importing.PUBLIX_BACKFILL.drop(1).forEachIndexed{i,record->
            val session=record.input.session(ZoneId.of("America/Chicago"),Instant.parse("2026-10-10T00:00:00Z"))
            db.shifts().insert(session.copy(id=31L+i,scheduledShiftId=if(i==0)20 else null,
                clockIn=session.clockIn.plusMillis(123),clockOut=session.clockOut!!.plusMillis(123),
                lunchStart=session.lunchStart?.plusMillis(123),lunchEnd=session.lunchEnd?.plusMillis(123),
                autoLunchMinutes=60,lunchEndAutomatic=session.lunchEnd!=null,correctionRevision=7).entity())
        }
        app.payroll.save(Paycheck(periodStart=LocalDate.of(2026,9,26),periodEnd=LocalDate.of(2026,10,2),payDate=LocalDate.of(2026,10,8),
            reportedHours="35.39".toBigDecimal(),grossCents=56624,netCents=49610,deductionsComplete=true,reference="Ref Ω",notes="Keep \"quotes\"\nnewlines",
            lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="TX Withholding Tax",cents=2682),PayrollLine(kind=PayrollKind.DEDUCTION,label="TX EE Social Security Tax",cents=3511),PayrollLine(kind=PayrollKind.DEDUCTION,label="TX EE Medicare Tax",cents=821)),
            deposits=listOf(PayrollDeposit(date=LocalDate.of(2026,10,6),cents=49610))))
        db.quickFind().insert(QuickFindItemEntity(41,"Café","café","Frozen","Keep chilled","[\"coffee\"]",true,123,456,789,3))
        db.aisleGuide().insert(AisleGuideEntity(51,"16","16","Bread · Rice cakes",123,456))
        app.preferences.store.guardedEdit {
            it[intPreferencesKey("lunch_threshold_minutes")]=300
            it[intPreferencesKey("weekly_target_minutes")]=2100
            it[stringSetPreferencesKey("weekly_target_warnings")]=setOf("120","30")
            it[intPreferencesKey("lunch_snooze_minutes")]=15
            it[stringPreferencesKey("lunch_attention")]="runtime receipt"
        }
        app.payRates.save(PayRate(LocalDate.of(2026,10,3),1800))
        app.upcoming.store.save(com.shifthud.notification.upcoming.UpcomingSettings(true,LocalTime.of(19,30),true))
    }
    @Test fun completePortableRoundTripPreservesAllRowsIdsAndPreferences()=runBlocking {
        seed();val original=manager.snapshot()
        val decoded=BackupSnapshot.decode(original.bytes())
        manager.validate(decoded)
        assertEquals(canonical(original.data),canonical(decoded.data))
        assertEquals(setOf("scheduled_shifts","work_sessions","quick_find_items","aisle_guide","paychecks","payroll_lines","payroll_deposits"),original.counts.keys)
        assertEquals(3,original.counts["payroll_lines"])
        assertFalse(original.data.getJSONObject("preferences").getJSONObject("shift").has("lunch_attention"))
        val db=Room.inMemoryDatabaseBuilder(app,ShiftDatabase::class.java).build()
        try {
            replaceDatabase(db,decoded.data.getJSONObject("tables"),decoded.data.getJSONObject("sequences"))
            validateRecords(db)
            assertEquals(canonical(original.data.getJSONObject("tables")),canonical(captureDatabase(db)))
            val s=db.shifts().session(31)!!
            assertEquals(123L,s.clockIn%1000);assertTrue(db.shifts().session(32)!!.lunchEndAutomatic);assertEquals(7L,s.correctionRevision)
            val p=db.payroll().observeAll().first().single().model()
            assertEquals("35.39".toBigDecimal(),p.reportedHours);assertEquals(56624L,p.grossCents)
            assertEquals(LocalDate.of(2026,10,6),p.deposits.single().date)
            val durations=db.shifts().observeSessions().first().map{com.shifthud.domain.usecase.ShiftEngine().durations(it.model(),360,Instant.now()).paid}
            val duration=durations.fold(Duration.ZERO,Duration::plus)
            val gross=durations.sumOf{grossCents(it,1600)}
            assertEquals(Duration.ofMinutes(2123),duration)
            assertEquals(56612L,gross)
            assertEquals(24L,employerDuration(p.reportedHours!!).minus(duration).seconds)
            assertEquals(12L,p.grossCents!!-gross)
        }finally{db.close()}
    }
    @Test fun emptyBackupIsValidAndPreviewWritesNothing()=runBlocking {
        val before=manager.snapshot();manager.validate(BackupSnapshot.decode(before.bytes()))
        assertEquals(canonical(before.data),canonical(manager.snapshot().data))
        assertTrue(before.counts.values.all{it==0})
    }
    @Test fun schemaCoverageMatchesAllRoomUserTables()=runBlocking {
        val sql=app.database.openHelper.readableDatabase
        val actual=mutableSetOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use{while(it.moveToNext())actual+=it.getString(0)}
        assertEquals(BACKUP_TABLES.toSet(),actual)
    }
    @Test fun invalidRelationshipsDuplicateIdsAndTimestampsRejectedWithoutWrites()=runBlocking {
        seed();val original=manager.snapshot()
        suspend fun rejected(change:(JSONObject)->Unit){
            val copy=BackupSnapshot.decode(original.bytes());change(copy.data.getJSONObject("tables"))
            assertTrue(runCatching{manager.validate(copy)}.isFailure)
            assertEquals(canonical(original.data),canonical(manager.snapshot().data))
        }
        rejected{it.getJSONArray("work_sessions").getJSONObject(0).put("scheduledShiftId",999)}
        rejected{it.getJSONArray("work_sessions").getJSONObject(0).put("clockOut",0)}
        rejected{it.getJSONArray("work_sessions").getJSONObject(0).put("lunchEndAutomatic",3)}
        rejected{it.getJSONArray("payroll_lines").getJSONObject(1).put("id",it.getJSONArray("payroll_lines").getJSONObject(0).getLong("id"))}
        rejected{it.getJSONArray("quick_find_items").getJSONObject(0).put("unexpected","value")}
    }
    @Test fun unsupportedVersionsAndPayloadIntegrityRejected()=runBlocking {
        val source=manager.snapshot()
        val bytes=JSONObject(source.bytes().toString(Charsets.UTF_8))
        bytes.getJSONObject("manifest").put("databaseVersion",99)
        assertTrue(runCatching{BackupSnapshot.decode(bytes.toString().toByteArray())}.isFailure)
        bytes.getJSONObject("manifest").put("databaseVersion",6);bytes.put("payload","{}")
        assertTrue(runCatching{BackupSnapshot.decode(bytes.toString().toByteArray())}.isFailure)
    }
    @Test fun replaceIdempotencyAndPreferencesRoundTrip()=runBlocking {
        seed();val source=manager.snapshot()
        repeat(2){
            manager.restore(source)
            assertEquals(canonical(source.data),canonical(manager.snapshot().data))
            assertNull(app.database.shifts().active())
        }
        assertEquals(0,app.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun failureAfterEachPersistenceStageRollsBackOriginal()=runBlocking {
        seed();val original=manager.snapshot(true)
        val target=BackupSnapshot.decode(manager.snapshot().bytes())
        target.data.getJSONObject("tables").getJSONArray("scheduled_shifts").getJSONObject(0).put("notes","replacement")
        for(stage in listOf("journal","database","preferences","verified","checkpoint")){
            var reached=false
            val failing=BackupManager(app){if(it==stage){reached=true;error("Injected storage failure")}}
            assertTrue(runCatching{failing.restore(target)}.isFailure)
            assertTrue("Fault stage was exercised: $stage",reached)
            assertEquals(canonical(original.data),canonical(manager.snapshot(true).data))
        }
    }
    @Test fun anUnrecoverableDestinationIsNeverOverwritten()=runBlocking {
        val target=manager.snapshot()
        seed()
        app.database.openHelper.writableDatabase.execSQL("UPDATE work_sessions SET clockOut=0 WHERE id=31")
        val original=manager.snapshot(true)
        assertTrue(runCatching{manager.restore(target)}.isFailure)
        assertEquals(canonical(original.data),canonical(manager.snapshot(true).data))
        assertFalse(RestoreJournal(File(app.noBackupFilesDir,"restore")).pending)
    }
    @Test fun processDeathRecoveryRetainsOldDataset()=runBlocking {
        seed();val original=manager.snapshot(true)
        val target=BackupSnapshot.decode(manager.snapshot().bytes())
        target.data.getJSONObject("tables").getJSONArray("scheduled_shifts").getJSONObject(0).put("notes","interrupted")
        for(stage in listOf("journal","database","preferences","verified","checkpoint")){
            val crashing=BackupManager(app){if(it==stage)throw AssertionError("Simulated process death")}
            try{crashing.restore(target);fail()}catch(_:AssertionError){}
            assertTrue(RestoreJournal(File(app.noBackupFilesDir,"restore")).pending)
            BackupManager(app).recover()
            assertEquals(canonical(original.data),canonical(manager.snapshot(true).data))
            BackupManager(app).recover()
        }
    }
    @Test fun processDeathAfterCommitKeepsVerifiedReplacement()=runBlocking {
        seed()
        val target=BackupSnapshot.decode(manager.snapshot().bytes())
        target.data.getJSONObject("tables").getJSONArray("scheduled_shifts").getJSONObject(0).put("notes","committed replacement")
        val crashing=BackupManager(app){if(it=="committed")throw AssertionError("Simulated process death after commit")}
        try{crashing.restore(target);fail()}catch(_:AssertionError){}
        BackupManager(app).recover()
        assertEquals(canonical(target.data),canonical(manager.snapshot().data))
        assertFalse(RestoreJournal(File(app.noBackupFilesDir,"restore")).pending)
    }
    @Test fun activeShiftBlocksExportAndReplacement()=runBlocking {
        val empty=manager.snapshot()
        app.database.shifts().insert(WorkSession(clockIn=Instant.now()).entity())
        val original=manager.snapshot()
        assertTrue(runCatching{manager.export("long password!".toCharArray())}.isFailure)
        assertTrue(runCatching{manager.restore(empty)}.isFailure)
        assertTrue(runCatching{manager.validate(original)}.isFailure)
        assertEquals(canonical(original.data),canonical(manager.snapshot().data))
    }
    @Test fun defaultNotificationSoundIsPortableWithoutAFileGrant()=runBlocking {
        val s=manager.snapshot()
        s.manifest.put("soundReferences",JSONArray().put(JSONObject().put("channel","lunch").put("uri",android.provider.Settings.System.DEFAULT_NOTIFICATION_URI.toString())))
        assertTrue(manager.soundWarnings(s).isEmpty())
    }
    @Test fun missingSoundProvidesReselectionAdvice()=runBlocking {
        val s=manager.snapshot()
        s.manifest.put("soundReferences",JSONArray().put(JSONObject().put("channel","lunch").put("uri","content://missing.provider/tone")))
        assertTrue(manager.soundWarnings(s).single().contains("Reselect"))
    }
    @Test fun restoreBaselinesPastRemindersWithoutPosting()=runBlocking {
        val now=Instant.now()
        app.database.shifts().insert(WorkSession(clockIn=now.minusSeconds(7200),clockOut=now.minusSeconds(3600),state=ShiftState.COMPLETE).entity())
        app.preferences.store.guardedEdit{it[intPreferencesKey("weekly_target_minutes")]=1}
        app.upcoming.store.save(com.shifthud.notification.upcoming.UpcomingSettings(true,LocalTime.MIDNIGHT,true))
        val source=manager.snapshot()
        manager.restore(source)
        val receipt=app.upcoming.store.receipt()
        assertNotNull(receipt)
        val plan=com.shifthud.notification.upcoming.upcomingPlan(Instant.now(),ZoneId.systemDefault(),app.upcoming.store.settings(),emptyList(),receipt,false)
        assertNull(plan.content)
        val history=app.preferences.store.data.first()[stringPreferencesKey("weekly_target_delivery")]!!
        assertTrue(JSONObject(history).getJSONObject("targets").getJSONObject("1").getJSONArray("consumed").toString().contains("0"))
        assertEquals(0,app.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun missingPreferenceAndUnsupportedTypeAreRejected()=runBlocking {
        val source=manager.snapshot()
        source.data.getJSONObject("preferences").getJSONObject("shift").put("lunch_threshold_minutes",JSONObject().put("type","s").put("value","300"))
        assertTrue(runCatching{manager.validate(source)}.isFailure)
    }
    @Test fun largeSnapshotAndSaturdayWeekPreserved()=runBlocking {
        repeat(2000){i->app.database.shifts().save(ScheduledShiftEntity(i+1L,LocalDate.of(2026,9,26).plusDays(i.toLong()).toEpochDay(),14400,50400,60,"Row $i Ω"))}
        val snapshot=manager.snapshot();manager.validate(BackupSnapshot.decode(snapshot.bytes()))
        assertEquals(2000,snapshot.counts["scheduled_shifts"])
        assertEquals(LocalDate.of(2026,9,26),workWeekFor(LocalDate.of(2026,10,2)).start)
    }
}
