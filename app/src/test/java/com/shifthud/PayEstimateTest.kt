package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class PayEstimateTest {
    private val start = Instant.parse("2026-10-05T04:00:00Z")
    private val zone = ZoneOffset.UTC
    private val estimator = PayEstimator(ShiftEngine())
    private val rates = DEFAULT_PAY_RATES
    private fun at(minutes: Long) = start.plusSeconds(minutes * 60)
    private fun completed(minutes: Long) = WorkSession(clockIn = start, clockOut = at(minutes), state = ShiftState.COMPLETE)
    private fun estimate(s: WorkSession, now: Instant = at(600)) = estimator.session(s, rates, now, zone)
    private val lunchSession get() = completed(521).copy(lunchStart = at(300), lunchEnd = at(356))

    @Test fun oneHourIsSixteenDollars() { assertEquals(1600L, grossCents(Duration.ofHours(1), 1600)) }
    @Test fun sevenHoursFortyFiveMinutesIs124Dollars() { assertEquals(12400L, grossCents(Duration.ofMinutes(465),1600)) }
    @Test fun completedLunchIsExcludedFromStoreTime() {
        assertEquals(Duration.ofMinutes(465), estimate(lunchSession).paid)
        assertEquals(12400L, estimate(lunchSession).grossCents)
    }
    @Test fun onLunchFreezesGross() {
        val s = WorkSession(clockIn=start,lunchStart=at(60),state=ShiftState.ON_LUNCH)
        assertEquals(estimate(s,at(65)),estimate(s,at(115)))
        assertEquals(1600L,estimate(s,at(115)).grossCents)
    }
    @Test fun endLunchResumesGross() {
        val lunch = WorkSession(clockIn=start,lunchStart=at(60),state=ShiftState.ON_LUNCH)
        val ended = ShiftEngine(Clock.fixed(at(90),zone)).endLunch(lunch)
        assertEquals(1600L,estimate(ended,at(90)).grossCents)
        assertEquals(2000L,estimate(ended,at(105)).grossCents)
    }
    @Test fun clockInCorrectionRecalculates() {
        val edited=correctTime(lunchSession,TimeEvent.CLOCK_IN,start.minusSeconds(900),at(600))
        assertEquals(12800L,estimate(edited).grossCents)
    }
    @Test fun lunchStartCorrectionRecalculates() {
        assertEquals(12800L,estimate(correctTime(lunchSession,TimeEvent.LUNCH_START,at(315),at(600))).grossCents)
    }
    @Test fun lunchEndCorrectionRecalculates() {
        assertEquals(12000L,estimate(correctTime(lunchSession,TimeEvent.LUNCH_END,at(371),at(600))).grossCents)
    }
    @Test fun clockOutCorrectionRecalculates() {
        assertEquals(12800L,estimate(correctTime(lunchSession,TimeEvent.CLOCK_OUT,at(536),at(600))).grossCents)
    }
    @Test fun activeSessionUsesCurrentInstant() { assertEquals(7387L,estimate(WorkSession(clockIn=start),at(277)).grossCents) }
    @Test fun completeSessionStopsAtClockOut() { assertEquals(estimate(lunchSession,at(600)),estimate(lunchSession,at(900))) }
    @Test fun weekSumsCompletedAndActivePaidAndGross() {
        val active = WorkSession(id=2,clockIn=at(540))
        val week = estimator.week(listOf(lunchSession,active),rates,at(600),zone)
        assertEquals(Duration.ofMinutes(525),week.paid)
        assertEquals(14000L,week.grossCents)
    }
    @Test fun scheduleCannotContributeAndNoSessionsIsZero() {
        val future = ScheduledShift(date=LocalDate.of(2026,10,6),scheduledStart=LocalTime.of(4,0),scheduledEnd=LocalTime.of(13,0))
        assertTrue(future.date > start.atZone(zone).toLocalDate())
        // PayEstimator accepts only actual sessions; schedule has no input path.
        assertEquals(PayEstimate(Duration.ZERO,0),estimator.week(emptyList(),rates,at(600),zone))
    }
    @Test fun sessionsOutsideWeekAndFutureActualTimestampsExcluded() {
        val previous=WorkSession(clockIn=start.minusSeconds(259200),clockOut=start.minusSeconds(255600),state=ShiftState.COMPLETE)
        val future=WorkSession(clockIn=at(900))
        assertEquals(PayEstimate(Duration.ZERO,0),estimator.week(listOf(previous,future),rates,at(600),zone))
    }
    @Test fun saturdayAndFridayBelongToSameWeekNextSaturdayStartsNewWeek() {
        val saturday=Instant.parse("2026-10-03T00:00:00Z")
        val friday=Instant.parse("2026-10-09T23:59:59Z")
        assertEquals(currentWeek(saturday,zone),currentWeek(friday,zone))
        assertEquals(Instant.parse("2026-10-10T00:00:00Z"),currentWeek(friday,zone).endExclusive)
        assertEquals(currentWeek(friday,zone).endExclusive,currentWeek(friday.plusSeconds(1),zone).start)
    }
    @Test fun overnightWeekBoundaryKeepsWholeSessionInClockInWeek() {
        val saturday=Instant.parse("2026-10-03T00:00:00Z")
        val overnight=WorkSession(clockIn=saturday.minusSeconds(3600),lunchStart=saturday.minusSeconds(900),lunchEnd=saturday.plusSeconds(900),clockOut=saturday.plusSeconds(3600),state=ShiftState.COMPLETE)
        assertEquals(PayEstimate(Duration.ZERO,0),estimator.week(listOf(overnight),rates,at(600),zone))
        // Before rollover, only elapsed paid time counts; the open lunch is clipped to now.
        val before=estimator.week(listOf(overnight),rates,saturday.minusSeconds(1),zone)
        assertEquals(Duration.ofMinutes(45),before.paid);assertEquals(1200L,before.grossCents)
    }
    @Test fun weekDuringLunchFreezesAndResumesAfterwards() {
        val lunch=WorkSession(clockIn=start,lunchStart=at(60),state=ShiftState.ON_LUNCH)
        assertEquals(estimator.week(listOf(lunch),rates,at(70),zone),estimator.week(listOf(lunch),rates,at(90),zone))
        assertEquals(2000L,estimator.week(listOf(lunch.copy(lunchEnd=at(90),state=ShiftState.WORKING)),rates,at(105),zone).grossCents)
    }
    @Test fun localDstWeekUsesCalendarBoundaries() {
        val chicago=ZoneId.of("America/Chicago")
        val spring=currentWeek(Instant.parse("2026-03-08T18:00:00Z"),chicago)
        val fall=currentWeek(Instant.parse("2026-11-01T18:00:00Z"),chicago)
        assertEquals(167L,Duration.between(spring.start,spring.endExclusive).toHours())
        assertEquals(169L,Duration.between(fall.start,fall.endExclusive).toHours())
    }
    @Test fun roundingIsHalfUpAtExactHalfCent() {
        assertEquals(0L,grossCents(Duration.ofMillis(1124),1600))
        assertEquals(1L,grossCents(Duration.ofMillis(1125),1600))
        repeat(100) { assertEquals(27L,grossCents(Duration.ofMinutes(1),1600)) }
    }
    @Test fun largeExactAmountsAvoidFloatingPointDriftAndMultiplicationOverflow() {
        assertEquals(100000000000000L,grossCents(Duration.ofHours(1_000_000_000),100000))
        assertEquals("$124.00",money(12400,Locale.US))
    }
    @Test fun historicalRateIsChosenAtLocalClockInEvenAcrossRaise() {
        val history=rates+PayRate(LocalDate.of(2026,10,6),1650)
        val crossing=WorkSession(clockIn=Instant.parse("2026-10-05T23:00:00Z"),clockOut=Instant.parse("2026-10-06T02:00:00Z"),state=ShiftState.COMPLETE)
        assertEquals(4800L,estimator.session(crossing,history,at(2000),zone).grossCents)
        assertEquals(1600L,applicableRate(history,Instant.parse("2026-10-06T02:00:00Z"),ZoneId.of("America/Chicago")).centsPerHour)
        assertEquals(1650L,applicableRate(history,Instant.parse("2026-10-06T00:00:00Z"),zone).centsPerHour)
    }
    @Test fun laterRaisePreservesEarlierShiftAndWeekUsesBothRates() {
        val raised=rates+PayRate(LocalDate.of(2026,10,6),1650)
        val earlier=completed(60)
        val later=WorkSession(clockIn=start.plusSeconds(86400),clockOut=start.plusSeconds(90000),state=ShiftState.COMPLETE)
        assertEquals(estimate(earlier),estimator.session(earlier,raised,at(2000),zone))
        assertEquals(3250L,estimator.week(listOf(earlier,later),raised,at(2000),zone).grossCents)
    }
    @Test fun clockInCorrectionAcrossRateBoundaryReevaluatesApplicableRate() {
        val history=rates+PayRate(LocalDate.of(2026,10,5),2000)
        val edited=correctTime(completed(60),TimeEvent.CLOCK_IN,Instant.parse("2026-10-04T23:00:00Z"),at(600))
        assertEquals(9600L,estimator.session(edited,history,at(600),zone).grossCents)
    }
    @Test fun decimalInputHasExactCentsAndStrictValidation() {
        assertEquals(1600L,parseRateCents("16"));assertEquals(1650L,parseRateCents("16.50"))
        assertEquals(1650L,parseRateCents("16,50"));assertEquals(1L,parseRateCents("0.01"))
        assertEquals(100000L,parseRateCents("1000.00"))
        listOf("0","-1","1.001","1000.01","1e2","NaN","","1,000.00").forEach { assertTrue(it,runCatching {parseRateCents(it)}.isFailure) }
    }
}
