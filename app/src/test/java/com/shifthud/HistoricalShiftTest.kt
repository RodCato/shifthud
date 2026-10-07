package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HistoricalShiftTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-06T18:00:00Z")
    private fun input(start: String = "04:00", end: String = "13:00", lunchIn: String? = null, lunchOut: String? = null) =
        HistoricalShiftInput(LocalDate.of(2026,10,3), LocalTime.parse(start), LocalTime.parse(end), lunchIn?.let(LocalTime::parse), lunchOut?.let(LocalTime::parse))
    private val engine = ShiftEngine(Clock.fixed(now, zone))
    @Test fun noLunchUsesAllStoreTimeAndManualCompleteProvenance() {
        val s = input().session(zone,now)
        assertEquals(ShiftState.COMPLETE,s.state); assertTrue(s.manuallyEntered)
        assertNull(s.lunchStart); assertNull(s.lunchEnd); assertNull(s.autoLunchMinutes)
        assertFalse(s.lunchEndAutomatic)
        val d = engine.durations(s,360)
        assertEquals(Duration.ofHours(9),d.store); assertEquals(d.store,d.paid); assertEquals(Duration.ZERO,d.lunch)
    }
    @Test fun lunchExcludedFromPaidButIncludedInStore() {
        val s=input(lunchIn="09:30",lunchOut="10:24").session(zone,now)
        val d=engine.durations(s,360)
        assertEquals(Duration.ofMinutes(540),d.store);assertEquals(Duration.ofMinutes(54),d.lunch);assertEquals(Duration.ofMinutes(486),d.paid)
        assertEquals(12960L,PayEstimator(engine).session(s,DEFAULT_PAY_RATES,now,zone).grossCents)
    }
    @Test fun effectiveHistoricalRateOverridesTodaysRate() {
        val s=input().session(zone,now)
        val rates=listOf(PayRate(LocalDate.MIN,1500),PayRate(LocalDate.of(2026,10,5),2000))
        assertEquals(13500L,PayEstimator(engine).session(s,rates,now,zone).grossCents)
    }
    @Test fun partialLunchStartRejected() { assertThrows(IllegalArgumentException::class.java){input(lunchIn="09:30").session(zone,now)} }
    @Test fun partialLunchEndRejected() { assertThrows(IllegalArgumentException::class.java){input(lunchOut="10:30").session(zone,now)} }
    @Test fun equalClockPunchesRejected() { assertThrows(IllegalArgumentException::class.java){input(end="04:00").session(zone,now)} }
    @Test fun reversedLunchRejected() { assertThrows(IllegalArgumentException::class.java){input(lunchIn="10:30",lunchOut="09:30").session(zone,now)} }
    @Test fun lunchOutsideShiftRejected() { assertThrows(IllegalArgumentException::class.java){input(lunchIn="13:00",lunchOut="14:00").session(zone,now)} }
    @Test fun futureEndRejected() { assertThrows(IllegalArgumentException::class.java){input().copy(date=LocalDate.of(2026,10,7)).session(zone,now)} }
    @Test fun overnightResolvesLunchOnFollowingDate() {
        val s=input("23:00","07:00","02:00","02:30").session(zone,now)
        assertEquals(Instant.parse("2026-10-04T02:00:00Z"),s.lunchStart)
        assertEquals(Instant.parse("2026-10-04T07:00:00Z"),s.clockOut)
        assertEquals(Duration.ofMinutes(450),engine.durations(s,360).paid)
    }
    @Test fun lunchMayCrossMidnight() {
        val s=input("20:00","04:00","23:45","00:15").session(zone,now)
        assertEquals(Duration.ofMinutes(30),engine.durations(s,360).lunch)
    }
    @Test fun nonexistentDstPunchRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            input("02:30","08:00").copy(date=LocalDate.of(2026,3,8)).session(ZoneId.of("America/Chicago"),now)
        }
    }
    @Test fun dstElapsedTimeUsesInstants() {
        val s=input("00:00","04:00").copy(date=LocalDate.of(2026,3,8)).session(ZoneId.of("America/Chicago"),now)
        assertEquals(Duration.ofHours(3),engine.durations(s,360).paid)
    }
    @Test fun priorSaturdayDoesNotContributeToTuesdayMondayBasedWeek() {
        val total=PayEstimator(engine).week(listOf(input().session(zone,now)),DEFAULT_PAY_RATES,now,zone)
        assertEquals(Duration.ZERO,total.paid);assertEquals(0L,total.grossCents)
    }
    @Test fun scheduleAssociationRequiresOneClearCandidate() {
        val s=input().session(zone,now)
        val matching=ScheduledShift(7,LocalDate.of(2026,10,3),LocalTime.of(4,0),LocalTime.of(13,0))
        assertEquals(7L,historicalSchedule(s,listOf(matching),zone))
        assertNull(historicalSchedule(s,listOf(matching,matching.copy(id=8)),zone))
        assertNull(historicalSchedule(s,listOf(matching.copy(scheduledStart=LocalTime.of(17,0),scheduledEnd=LocalTime.of(20,0))),zone))
        assertNull(historicalSchedule(s,emptyList(),zone))
    }
}
