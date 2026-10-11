package com.shifthud.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shifthud.ShiftHudApplication
import com.shifthud.data.local.entity.*
import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.payroll.*
import com.shifthud.domain.importing.PUBLIX_BACKFILL
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.io.File

/** Explicit phases are driven on disposable emulator datasets; default execution is read-only. */
@RunWith(AndroidJUnit4::class)
class BackupDeviceAcceptanceTest {
    @Test fun acceptance()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiftHudApplication
        val phase=InstrumentationRegistry.getArguments().getString("phase")?:"read_only"
        val manager=app.backups
        when(phase) {
            "seed" -> {
                check(app.database.shifts().observeSessions().first().isEmpty())
                PUBLIX_BACKFILL.forEachIndexed{i,r->
                    val s=r.input.session(ZoneId.of("America/Chicago"),Instant.parse("2026-10-10T00:00:00Z"))
                    app.database.shifts().insert(s.copy(id=100L+i,autoLunchMinutes=60,lunchEndAutomatic=s.lunchEnd!=null,correctionRevision=2).entity())
                }
                repeat(2){i->app.database.shifts().save(ScheduledShiftEntity(200L+i,LocalDate.of(2026,10,20).plusDays(i.toLong()).toEpochDay(),14400,50400,60,"Future schedule Ω"))}
                app.payroll.save(Paycheck(periodStart=LocalDate.of(2026,9,26),periodEnd=LocalDate.of(2026,10,2),payDate=LocalDate.of(2026,10,8),
                    reportedHours="35.39".toBigDecimal(),grossCents=56624,netCents=49610,deductionsComplete=true,reference="Acceptance fixture",notes="Unicode café 🥥",
                    lines=listOf(PayrollLine(kind=PayrollKind.DEDUCTION,label="TX Withholding Tax",cents=2682),PayrollLine(kind=PayrollKind.DEDUCTION,label="TX EE Social Security Tax",cents=3511),PayrollLine(kind=PayrollKind.DEDUCTION,label="TX EE Medicare Tax",cents=821)),
                    deposits=listOf(PayrollDeposit(date=LocalDate.of(2026,10,6),cents=49610))))
                repeat(2000){i->app.database.quickFind().insert(QuickFindItemEntity(300L+i,"Fixture item $i","fixture item $i",if(i%2==0)"Frozen" else "16","Unicode café","[\"reference $i\"]",i<3,123,456,789,2))}
                app.database.aisleGuide().insert(AisleGuideEntity(2400,"16","16","Bread · Rice cakes",123,456))
                app.payRates.save(PayRate(LocalDate.of(2026,10,3),1800))
                app.preferences.store.guardedEdit{p->
                    p[intPreferencesKey("lunch_threshold_minutes")]=300
                    p[intPreferencesKey("weekly_target_minutes")]=2100
                    p[intPreferencesKey("lunch_snooze_minutes")]=15
                    p[booleanPreferencesKey("auto_lunch_enabled")]=true
                    p[intPreferencesKey("auto_lunch_minutes")]=45
                }
                app.upcoming.store.save(com.shifthud.notification.upcoming.UpcomingSettings(true,LocalTime.of(19,30),true))
                File(app.filesDir,"backup-acceptance-expected.json").writeBytes(manager.snapshot().bytes())
            }
            "verify" -> {
                val expected=BackupSnapshot.decode(File(app.filesDir,"backup-acceptance-expected.json").readBytes())
                assertEquals(canonical(expected.data),canonical(manager.snapshot().data))
                val rows=app.database.shifts().observeSessions().first().map{it.model()}.filter{it.clockIn.atZone(ZoneId.of("America/Chicago")).toLocalDate()>=LocalDate.of(2026,9,26)}
                val durations=rows.map{app.engine.durations(it,300,Instant.now()).paid}
                assertEquals(Duration.ofMinutes(2123),durations.fold(Duration.ZERO,Duration::plus))
                assertEquals(56612L,durations.sumOf{grossCents(it,1600)})
                val p=app.payroll.paychecks.first().single()
                assertEquals(24L,employerDuration(p.reportedHours!!).minus(durations.fold(Duration.ZERO,Duration::plus)).seconds)
                assertEquals(12L,p.grossCents!!-durations.sumOf{grossCents(it,1600)})
                assertEquals(LocalDate.of(2026,10,6),p.deposits.single().date)
                assertEquals(0,app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.size)
            }
            "restore_file" -> {
                val file=File(app.filesDir,"backup-acceptance.shifthud").readBytes()
                val preview=manager.preview(file,"backup-test-password".toCharArray())
                manager.restore(preview)
                assertEquals(canonical(preview.data),canonical(manager.snapshot().data))
            }
            "interrupt_after_checkpoint" -> {
                val preview=manager.preview(File(app.filesDir,"backup-acceptance.shifthud").readBytes(),"backup-test-password".toCharArray())
                BackupManager(app){if(it=="checkpoint")android.os.Process.killProcess(android.os.Process.myPid())}.restore(preview)
                error("Expected interruption was not reached")
            }
            "mark_recovery" -> {
                val row=app.database.shifts().scheduleSnapshot().first()
                app.database.shifts().save(row.copy(notes="Destination-only recovery marker"))
                app.preferences.store.guardedEdit{it[intPreferencesKey("weekly_target_minutes")]=2345}
                app.payRates.save(PayRate(LocalDate.of(2026,10,4),1750))
                app.upcoming.store.save(com.shifthud.notification.upcoming.UpcomingSettings(false,LocalTime.of(20,34),false))
                File(app.filesDir,"backup-before-interrupt.json").writeBytes(manager.snapshot().bytes())
            }
            "verify_recovery" -> {
                val expected=BackupSnapshot.decode(File(app.filesDir,"backup-before-interrupt.json").readBytes())
                assertEquals(canonical(expected.data),canonical(manager.snapshot().data))
                assertFalse(RestoreJournal(File(app.noBackupFilesDir,"restore")).pending)
            }
            "reject" -> {
                val original=canonical(manager.snapshot().data)
                val file=File(app.filesDir,"backup-acceptance.shifthud").readBytes()
                assertEquals(original,canonical(manager.preview(file,"backup-test-password".toCharArray()).data))
                assertTrue(runCatching{manager.preview(file,"wrong password".toCharArray())}.isFailure)
                file[file.lastIndex]=(file.last().toInt() xor 1).toByte()
                assertTrue(runCatching{manager.preview(file,"backup-test-password".toCharArray())}.isFailure)
                assertEquals(original,canonical(manager.snapshot().data))
            }
            "read_only" -> {
                val source=manager.snapshot()
                val encrypted=BackupCrypto.encrypt(source.bytes(),"test-only-password".toCharArray())
                val restored=BackupSnapshot.decode(BackupCrypto.decrypt(encrypted,"test-only-password".toCharArray()))
                assertEquals(canonical(source.data),canonical(restored.data))
            }
            else -> error("Unknown explicit acceptance phase.")
        }
    }
}
