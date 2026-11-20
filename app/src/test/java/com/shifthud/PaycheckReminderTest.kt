package com.shifthud

import android.Manifest
import android.app.*
import android.content.Intent
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.shifthud.notification.paycheck.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PaycheckReminderTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    private var now=LocalDate.of(2026,11,5).atTime(19,0).atZone(ZoneId.systemDefault()).toInstant()
    private val clock=object:java.time.Clock(){override fun instant()=now;override fun getZone()=ZoneId.systemDefault();override fun withZone(zone:ZoneId)=this}
    private var records=emptyList<com.shifthud.domain.payroll.Paycheck>()
    private val jobs=mutableListOf<Pair<Instant?,ExistingWorkPolicy>>()
    private fun reminder()=PaycheckReminders(context,{records},clock){at,policy->jobs+=at to policy}
    @Before fun setup(){context.getSharedPreferences("paycheck_import_reminder",0).edit().clear().commit();Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)}
    @Test fun defaultsThursdaySevenAndLocalDstSchedule() {
        assertEquals(PaycheckReminderSettings(),reminder().store.settings())
        val zone=ZoneId.of("America/Chicago")
        val next=nextPaycheckReminder(Instant.parse("2026-11-04T23:00:00Z"),zone,PaycheckReminderSettings())
        assertEquals(Instant.parse("2026-11-06T01:00:00Z"),next)
        val sunday=PaycheckReminderSettings(day=DayOfWeek.SUNDAY,time=LocalTime.of(2,30))
        assertEquals(LocalTime.of(3,30),nextPaycheckReminder(Instant.parse("2026-03-07T12:00:00Z"),zone,sunday).atZone(zone).toLocalTime())
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"),nextPaycheckReminder(Instant.parse("2026-10-31T12:00:00Z"),zone,sunday.copy(time=LocalTime.of(1,30))))
    }
    @Test fun settingsPersistRescheduleAndDisableCancels()=runBlocking {
        val changed=PaycheckReminderSettings(day=DayOfWeek.MONDAY,time=LocalTime.of(8,15))
        reminder().configure(changed)
        assertEquals(changed,reminder().store.settings());assertEquals(ExistingWorkPolicy.REPLACE,jobs.last().second)
        assertEquals(DayOfWeek.MONDAY,jobs.last().first!!.atZone(ZoneId.systemDefault()).dayOfWeek)
        reminder().configure(changed.copy(enabled=false));assertNull(jobs.last().first)
        jobs.clear();reminder().deliverAndSchedule();assertTrue(jobs.isEmpty());assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun repeatedDeliveryAndRestartDoNotDuplicateAndFollowingWeekAlerts()=runBlocking {
        reminder().deliverAndSchedule();val first=manager.activeNotifications.single().notification
        repeat(4){reminder().deliverAndSchedule()};assertSame(first,manager.activeNotifications.single().notification)
        assertEquals(LocalDate.of(2026,11,5),reminder().store.receipt());assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE,jobs.last().second)
        now=now.plus(Duration.ofDays(7));reminder().deliverAndSchedule();assertNotSame(first,manager.activeNotifications.single().notification)
        assertEquals(PaycheckReminders.ID,manager.activeNotifications.single().id)
    }
    @Test fun blockedPermissionAndChannelNeverAcknowledged()=runBlocking {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        reminder().deliverAndSchedule();assertTrue(manager.activeNotifications.isEmpty());assertNull(reminder().store.receipt())
        assertEquals(now.plusSeconds(900),jobs.last().first)
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(PaycheckReminders.CHANNEL,"Blocked",0))
        reminder().deliverAndSchedule();assertNull(reminder().store.receipt());assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun suppressOnlyReliableConfirmedSameDateAndNeverMutateRecords()=runBlocking {
        records=listOf(noncashFixture());val original=records
        reminder().deliverAndSchedule();assertTrue(manager.activeNotifications.isEmpty());assertNotNull(reminder().store.receipt());assertEquals(original,records)
        assertFalse(paycheckAlreadyRecorded(records,LocalDate.of(2026,11,12)))
        assertFalse(paycheckAlreadyRecorded(listOf(records.single().copy(netCents=null)),LocalDate.of(2026,11,5)))
        assertFalse(paycheckAlreadyRecorded(listOf(records.single().copy(netCents=1)),LocalDate.of(2026,11,5)))
        assertFalse(paycheckAlreadyRecorded(records+records.single().copy(id=2),LocalDate.of(2026,11,5)))
    }
    @Test fun notificationTapTargetsPayrollAndChannelSettingsPreserved()=runBlocking {
        val sound=android.net.Uri.parse("content://media/external/audio/media/777")
        manager.createNotificationChannel(NotificationChannel(PaycheckReminders.CHANNEL,"Custom",3).apply{setSound(sound,null);enableVibration(false)})
        reminder().deliverAndSchedule();val n=manager.activeNotifications.single().notification
        assertEquals("Import Publix Paycheck",n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Paychecks",Shadows.shadowOf(n.contentIntent).savedIntent.getStringExtra(MainActivity.DESTINATION));assertTrue(n.contentIntent.isImmutable)
        assertEquals(sound,manager.getNotificationChannel(PaycheckReminders.CHANNEL).sound);assertNull(n.sound)
        assertEquals(Notification.CATEGORY_REMINDER,n.category);assertNull(Shadows.shadowOf(context).nextStartedService)
    }
    @Test fun recoveryAndClockChangesReevaluateWithDurableReceipt()=runBlocking {
        assertEquals(setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED),PaycheckReminderRecoveryReceiver.ACTIONS)
        reminder().deliverAndSchedule();val first=manager.activeNotifications.single().notification
        context.getSharedPreferences("paycheck_import_reminder",0).edit().remove("delivered").commit()
        reminder().reschedule();assertEquals(now,jobs.last().first)
        reminder().deliverAndSchedule();assertSame(first,manager.activeNotifications.single().notification);assertNotNull(reminder().store.receipt())
    }
    @Test fun actualWorkManagerReplacesAndCancelsUniqueWork()=runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        try {
            val r=PaycheckReminders(context,{emptyList()},clock)
            r.configure(PaycheckReminderSettings());r.configure(PaycheckReminderSettings(day=DayOfWeek.FRIDAY))
            val wm=WorkManager.getInstance(context)
            assertEquals(1,wm.getWorkInfosForUniqueWork(PaycheckReminders.WORK).get().count{!it.state.isFinished})
            r.configure(PaycheckReminderSettings(enabled=false))
            assertTrue(wm.getWorkInfosForUniqueWork(PaycheckReminders.WORK).get().all{it.state.isFinished})
        }finally{WorkManagerTestInitHelper.closeWorkDatabase()}
    }
}
