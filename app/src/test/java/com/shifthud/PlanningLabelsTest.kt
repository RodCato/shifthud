package com.shifthud

import com.shifthud.ui.*
import com.shifthud.ui.schedule.*
import com.shifthud.domain.model.*
import com.shifthud.domain.planner.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PlanningLabelsTest {
    private val week=scheduleWeek(LocalDate.of(2026,10,17))
    private val rows=(0L..4L).map { ScheduledShift(it+1,week.start.plusDays(it),LocalTime.of(8,0),LocalTime.of(17,0),employerNetMinutes=480) }
    private fun summary(shifts:List<ScheduledShift>)=schedulePlanning(week,shifts,emptyList(),PlannerSettings(dynamicWeekly=true),Duration.ofHours(40),week.start.atStartOfDay(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC)
    @Test fun employerReportedWhenAllValid() { assertEquals("Scheduled: 40h 0m (employer-reported)",summary(rows).scheduledLabel()) }
    @Test fun fullyEstimatedDoesNotClaimEmployerVerification() { assertEquals("Scheduled: 40h 0m (estimated)",summary(rows.map{it.copy(employerNetMinutes=null)}).scheduledLabel()) }
    @Test fun partialHoursAreClearlyEstimated() { val s=summary(rows.mapIndexed { i,v -> if(i==0)v.copy(employerNetMinutes=null) else v });assertEquals("Scheduled: ~40h 0m (partially estimated)",s.scheduledLabel());assertEquals("Employer hours available for 4 of 5 saved shifts.",s.metadataLabel()) }
    @Test fun invalidMetadataIsNotVerified() { assertTrue(summary(rows.map{it.copy(employerNetMinutes=1440)}).scheduledLabel().endsWith("(estimated)")) }
    @Test fun emptyWeekRemainsUnknown() { assertEquals("Scheduled: unknown — no saved shifts",summary(emptyList()).scheduledLabel()) }
    @Test fun workedAndCountdownComplementWholeMinuteTarget() { val worked=Duration.ofHours(8).plusMinutes(4).plusSeconds(30);assertEquals("8h 4m",worked.display());assertEquals("31h 56m",Duration.ofHours(40).minus(worked).countdownLabel()) }
    @Test fun countdownNeverShowsZeroBeforeTarget() { assertEquals("1m",Duration.ofNanos(1).countdownLabel());assertEquals("0m",Duration.ZERO.countdownLabel());assertEquals("40h 0m",Duration.ofHours(40).countdownLabel()) }
    @Test fun excessDoesNotHideSubMinuteAmounts() { assertEquals("<1m",Duration.ofSeconds(30).excessLabel());assertEquals("20m",Duration.ofMinutes(20).excessLabel()) }
}
