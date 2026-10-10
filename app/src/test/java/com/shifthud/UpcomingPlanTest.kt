package com.shifthud

import com.shifthud.domain.model.ScheduledShift
import com.shifthud.notification.upcoming.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class UpcomingPlanTest {
    private val date = LocalDate.of(2026,10,9) // Friday
    private val zone = ZoneId.of("America/Chicago")
    private val now = date.atTime(20,0).atZone(zone).toInstant()
    private fun shift(hour: Int = 5, day: LocalDate = date.plusDays(1)) = ScheduledShift(date=day,scheduledStart=LocalTime.of(hour,0),scheduledEnd=LocalTime.of(14,0))
    private fun plan(settings: UpcomingSettings = UpcomingSettings(), shifts: List<ScheduledShift> = listOf(shift()), at: Instant = now, receipt: UpcomingReceipt? = null) = upcomingPlan(at,zone,settings,shifts,receipt)
    @Test fun enabledByDefault() { assertTrue(UpcomingSettings().enabled) }
    @Test fun defaultEightPmAndDaysOffFalse() { assertEquals(LocalTime.of(20,0),UpcomingSettings().time);assertFalse(UpcomingSettings().daysOff) }
    @Test fun customTimeDefersDelivery() { val p=plan(UpcomingSettings(time=LocalTime.of(21,0)));assertNull(p.content);assertEquals(now.plusSeconds(3600),p.next) }
    @Test fun fridayLooksUpSaturdayAndSaturdayLooksUpSunday() {
        for (today in listOf(date,date.plusDays(1))) { val rows=listOf(shift(day=today),shift(day=today.plusDays(1)));assertEquals(listOf(rows[1]),tomorrowShifts(rows,today)) }
    }
    @Test fun singleShiftConciseText() { val c=upcomingContent(date.plusDays(1),listOf(shift()),locale=Locale.US);assertEquals("ShiftHUD · Tomorrow at 5:00 AM",c.title);assertEquals("Saturday · 5:00 AM–2:00 PM",c.text) }
    @Test fun multipleShiftsSortedAndExpanded() { val rows=tomorrowShifts(listOf(shift(9),shift(4)),date);val c=upcomingContent(date.plusDays(1),rows,locale=Locale.US);assertTrue(c.title.endsWith("4:00 AM"));assertEquals("2 shifts scheduled",c.text);assertEquals(2,c.expanded.lines().size) }
    @Test fun dayOffSuppressedByDefault() { assertNull(plan(shifts=emptyList()).content) }
    @Test fun optionalDayOff() { assertEquals("No shift scheduled tomorrow.",plan(UpcomingSettings(daysOff=true),emptyList()).content!!.text) }
    @Test fun disabledNeverDelivers() { assertNull(plan(UpcomingSettings(enabled=false)).content) }
    @Test fun duplicateStateSuppressed() { assertNull(plan(receipt=UpcomingReceipt(date.plusDays(1),"05:00/14:00",now.minusSeconds(600))).content) }
    @Test fun editsProduceUpdate() { val c=plan(receipt=UpcomingReceipt(date.plusDays(1),"04:00/14:00",now.minusSeconds(600))).content!!;assertEquals("ShiftHUD · Schedule updated",c.title);assertTrue(c.text.contains("5:00")) }
    @Test fun deletionAnnouncedEvenWithDaysOffDisabled() { assertTrue(plan(shifts=emptyList(),receipt=UpcomingReceipt(date.plusDays(1),"05:00/14:00",now.minusSeconds(600))).content!!.text.contains("removed")) }
    @Test fun unchangedNotesDoNotGenerateUpdate() { assertEquals(scheduleFingerprint(listOf(shift())),scheduleFingerprint(listOf(shift().copy(notes="changed",plannedLunchMinutes=30)))) }
    @Test fun updateBurstWaitsTwoMinutes() { val p=plan(receipt=UpcomingReceipt(date.plusDays(1),"04:00/14:00",now.minusSeconds(30)));assertNull(p.content);assertEquals(now.plusSeconds(90),p.next) }
    @Test fun movingTimeAfterDeliveryDoesNotRepeat() { assertNull(plan(UpcomingSettings(time=LocalTime.of(19,0)),receipt=UpcomingReceipt(date.plusDays(1),"05:00/14:00",now.minusSeconds(600))).content) }
    @Test fun midnightNeverAnnouncesPreviousTomorrow() { val p=plan(at=date.plusDays(1).atStartOfDay(zone).toInstant());assertEquals(date.plusDays(2),p.date);assertNull(p.content) }
    @Test fun timezoneChangesRecalculateWallTime() { val utc=upcomingPlan(now,ZoneOffset.UTC,UpcomingSettings(),listOf(shift()),null);assertEquals(LocalDate.of(2026,10,11),utc.date);assertNull(utc.content) }
    @Test fun dstNextEveningUsesLocalTime() { val day=LocalDate.of(2026,10,31);val p=upcomingPlan(day.atTime(20,0).atZone(zone).toInstant(),zone,UpcomingSettings(),emptyList(),null);assertEquals(25,Duration.between(day.atTime(20,0).atZone(zone).toInstant(),p.next).toHours().toInt()) }
    @Test fun backwardsClockChangeDoesNotDelayUpdatesForHours() { assertNotNull(plan(receipt=UpcomingReceipt(date.plusDays(1),"04:00/14:00",now.plusSeconds(3600))).content) }
    @Test fun twentyFourHourAndOvernightText() { val c=upcomingContent(date.plusDays(1),listOf(shift(23)),is24Hour=true,locale=Locale.US);assertTrue(c.text.contains("23:00–14:00 (+1 day)")) }
}
