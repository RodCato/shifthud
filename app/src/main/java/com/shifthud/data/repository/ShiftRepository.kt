package com.shifthud.data.repository

import com.shifthud.backup.guardedTransaction as withTransaction
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.*
import com.shifthud.domain.model.*
import com.shifthud.domain.importing.*
import com.shifthud.domain.usecase.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.*

class ShiftRepository(
    private val db: ShiftDatabase,
    private val engine: ShiftEngine,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val onChanged: suspend () -> Unit = {},
    private val onHistoricalChanged: suspend () -> Unit = {},
    private val autoLunchSettings: suspend () -> AutoLunchSettings = { AutoLunchSettings() },
    private val onScheduleChanged: suspend () -> Unit = {},
    private val plannerSettings: suspend () -> com.shifthud.domain.planner.PlannerSettings = { com.shifthud.domain.planner.PlannerSettings() },
    private val onSessionDeleted: suspend (Long) -> Unit = {},
) {
    private val dao = db.shifts()
    fun sessionsBetween(start: LocalDate, endExclusive: LocalDate, zone: ZoneId) =
        dao.observeSessionsBetween(start.atStartOfDay(zone).toInstant().toEpochMilli(), endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()).map { rows -> rows.map { it.model() } }
    fun scheduleBetween(start: LocalDate, endExclusive: LocalDate) =
        dao.observeScheduleBetween(start.toEpochDay(), endExclusive.toEpochDay()).map { rows -> rows.map { it.model() } }
    fun session(id: Long) = dao.observeSession(id).map { it?.model() }
    val schedule = dao.observeSchedule().map { rows -> rows.map { it.model() } }
    val sessions = dao.observeSessions().map { rows -> rows.map { it.model() } }
    val latestSession = dao.observeLatest().map { it?.model() }

    suspend fun save(shift: ScheduledShift) = changed { db.withTransaction {
        val settings=plannerSettings()
        val saved=if(shift.plannedLunchMinutes==null) shift.copy(plannedLunchMinutes=settings.plannedMinutes,usesGlobalLunch=true) else shift
        dao.save(saved.entity())
    } }.also { withContext(NonCancellable) { onScheduleChanged() } }
    /** Confirmed screenshot choices only. Recheck the preview and commit the batch atomically. */
    suspend fun importSchedule(choices: List<com.shifthud.domain.scheduleimport.ScheduleImportChoice>,
                               expected: List<ScheduledShift>, week: LocalDate): com.shifthud.domain.scheduleimport.ScheduleImportSummary = changed(historical = true) {
        val summary = db.withTransaction {
            require(week.dayOfWeek == DayOfWeek.SATURDAY) { "Select a Saturday–Friday workweek." }
            require(choices.isNotEmpty() && choices.size <= 50) { "Select at least one valid shift." }
            val current = dao.scheduleSnapshot().map { it.model() }
            require(current == expected) { "Schedules changed. Review the refreshed conflicts before importing." }
            val minimum = plannerSettings().minimumMinutes
            val records = dao.allSessions().map { it.model() }
            val working = current.toMutableList()
            var imported=0; var updated=0; var duplicates=0
            val replaced=mutableSetOf<Long>()
            choices.forEach { choice ->
                val proposed=choice.shift
                require(proposed.id==0L && proposed.date in week..week.plusDays(6)) { "Every selected date must be in the confirmed week." }
                val old=choice.replacing
                require(proposed.plannedLunchMinutes != null && proposed.plannedLunchMinutes in minimum..240) { "Review lunch duration against the current employer minimum." }
                if(old!=null) {
                    require(old in current && replaced.add(old.id)) { "Replacement changed or was selected twice." }
                    require(records.none { it.scheduledShiftId==old.id }) { "A worked or active schedule cannot be replaced by screenshot import." }
                    require(old.date==proposed.date) { "Replacement must be on the same date." }
                }
                val saved=if(old==null) proposed else proposed.copy(id=old.id,notes=old.notes,
                    plannedLunchMinutes=old.plannedLunchMinutes,usesGlobalLunch=old.usesGlobalLunch,lunchPlanApplied=old.lunchPlanApplied)
                val others=working.filter{old==null || it.id!=old.id}
                val match=com.shifthud.domain.scheduleimport.classify(saved,others)
                if(match.kind==com.shifthud.domain.scheduleimport.ImportKind.EXACT_DUPLICATE) { duplicates++ }
                else {
                    require(others.none { com.shifthud.domain.scheduleimport.overlaps(saved,it) }) { "Selected shifts overlap. Nothing was imported." }
                    require(old!=null || match.kind==com.shifthud.domain.scheduleimport.ImportKind.NEW) { "Review the existing schedule and explicitly confirm replacement." }
                    require(saved.plannedLunchMinutes==null || saved.plannedLunchMinutes < java.time.Duration.between(saved.start,saved.end).toMinutes()) { "Lunch must be shorter than the shift." }
                    dao.save(saved.entity())
                    if(old!=null) { working.remove(old);updated++ } else imported++
                    working.add(saved)
                }
            }
            com.shifthud.domain.scheduleimport.ScheduleImportSummary(imported,updated,duplicates)
        }
        // Redraw-only hook above avoids auto-lunch reconciliation and live alert delivery.
        val refreshDeferred=withContext(NonCancellable) { runCatching { onScheduleChanged() }.isFailure }
        summary.copy(refreshDeferred=refreshDeferred)
    }
    /** Freeze legacy implicit defaults before changing preferences; effective plans are unchanged. */
    suspend fun preserveLegacyLunchPlans(settings: com.shifthud.domain.planner.PlannerSettings) = db.withTransaction {
        val shifts=dao.scheduleSnapshot().map { it.model() }
        shifts.filter { it.plannedLunchMinutes==null }.forEach { shift ->
            val week=com.shifthud.domain.pay.workWeekFor(shift.date)
            val last=shifts.filter { it.date in week }.maxOfOrNull { it.date }
            dao.save(shift.copy(plannedLunchMinutes=settings.lunch(shift,last).toMinutes().toInt(),usesGlobalLunch=true,lunchPlanApplied=true).entity())
        }
    }
    suspend fun applyLunchPlan(expected: List<ScheduledShift>, minutes: Int, replaceOverrides: Boolean,
                               weekStart: LocalDate, zone: ZoneId) = changed {
        db.withTransaction {
            val now=clock.instant()
            require(com.shifthud.domain.pay.workWeekFor(now.atZone(zone).toLocalDate()).start==weekStart) { "The workweek changed. Review a new preview." }
            require(minutes in plannerSettings().minimumMinutes..240) { "Lunch is below the current minimum or above 240 minutes." }
            val all=dao.scheduleSnapshot().map { it.model() }
            val records=dao.allSessions().map { it.model() }
            val current=com.shifthud.domain.planner.lunchPlanCandidates(all,records,now,zone,replaceOverrides)
            require(current==expected) { "Schedules or work records changed. Review a new preview before applying." }
            current.forEach { dao.save(it.copy(plannedLunchMinutes=minutes,usesGlobalLunch=true,lunchPlanApplied=true).entity()) }
        }
        withContext(NonCancellable) { onScheduleChanged() }
    }
    suspend fun delete(id: Long) = changed { dao.delete(id) }.also { withContext(NonCancellable) { onScheduleChanged() } }

    suspend fun weeklySessions(now: Instant, zone: ZoneId): List<WorkSession> {
        val window = com.shifthud.domain.pay.currentWeek(now, zone)
        return dao.sessionsBetween(window.start.toEpochMilli(), window.endExclusive.toEpochMilli()).map { it.model() }
    }

    suspend fun snapshot(): ShiftSnapshot = db.withTransaction {
        ShiftSnapshot(dao.scheduleSnapshot().map { it.model() }, (dao.active() ?: dao.latest())?.model())
    }

    // Optional widget preconditions are checked in the same transaction as the existing clock-in.
    suspend fun clockIn(expectedPreviousSessionId: Long? = null, expectedDate: LocalDate? = null) = changed {
        db.withTransaction {
            if (expectedPreviousSessionId != null) {
                check((dao.latest()?.id ?: 0L) == expectedPreviousSessionId) { "Shift changed. Refresh and try again." }
            }
            if (expectedDate != null) {
                check(LocalDate.now(clock) == expectedDate) { "Date changed. Refresh and try again." }
            }
            check(dao.active() == null) { "A shift is already active." }
            dao.insert(engine.clockIn(dao.today(LocalDate.now(clock).toEpochDay())?.id).entity())
        }
    }

    suspend fun transition(expectedId: Long, expectedState: ShiftState, change: (WorkSession) -> WorkSession) = changed {
        val settings = autoLunchSettings() // Read before Room transaction: no Room/DataStore lock inversion.
        db.withTransaction {
            val current = checkNotNull(dao.active()) { "No active shift." }.model()
            check(current.id == expectedId && current.state == expectedState) { "Shift changed. Try again." }
            var next = change(current)
            if (current.lunchStart == null && next.state == ShiftState.ON_LUNCH) {
                next = next.copy(autoLunchMinutes = settings.minutes.takeIf { settings.enabled })
            }
            if (current.lunchEnd == null && next.lunchEnd != null) next = next.copy(lunchEndAutomatic = false)
            dao.update(next.entity())
        }
    }

    suspend fun correct(expected: WorkSession, event: TimeEvent, value: Instant) = changed(historical = expected.manuallyEntered) {
        db.withTransaction {
            val current = checkNotNull(dao.session(expected.id)) { "Session no longer exists." }.model()
            check(current == expected) { "Time record changed. Reopen the event and try again." }
            val corrected = correctTime(current, event, value, clock.instant())
            if (corrected.manuallyEntered) {
                require(corrected.clockOut!! > corrected.clockIn) { "Clock out must be after clock in." }
                checkOverlap(corrected, current.id)
            }
            dao.update(corrected.entity())
        }
    }

    suspend fun addHistorical(input: HistoricalShiftInput, zone: ZoneId): Long = changed(historical = true) {
        db.withTransaction {
            insertHistorical(input.session(zone, clock.instant()), zone)
        }
    }

    private suspend fun insertHistorical(session: WorkSession, zone: ZoneId): Long {
        checkOverlap(session)
        val linked = historicalSchedule(session, dao.scheduleSnapshot().map { it.model() }, zone)
        return dao.insert(session.copy(scheduledShiftId = linked).entity())
    }

    /** Recheck the fixed dataset inside one transaction; preview never authorizes replacement. */
    suspend fun importBackfill(selected: Set<LocalDate>, zone: ZoneId): BackfillResult {
        require(selected.all { date -> PUBLIX_BACKFILL.any { it.date == date } }) { "Unknown historical import date." }
        val result = db.withTransaction {
            val now = clock.instant()
            val candidates = PUBLIX_BACKFILL.filter { it.date in selected }
            // Validate the entire selection before writing, using the same manual-entry rules.
            candidates.forEach { it.input.session(zone, now) }
            val start = PUBLIX_BACKFILL.first().date.atStartOfDay(zone).toInstant()
            val end = PUBLIX_BACKFILL.last().date.plusDays(1).atStartOfDay(zone).toInstant()
            val existing = dao.importWindow(start.toEpochMilli(), end.toEpochMilli()).map { it.model() }.toMutableList()
            val imported = mutableListOf<Long>()
            val skipped = mutableListOf<BackfillReview>()
            candidates.forEach { candidate ->
                val review = reviewBackfill(candidate, existing, zone, now)
                if (review.status != BackfillStatus.READY) skipped += review
                else {
                    val id = insertHistorical(review.session, zone)
                    imported += id
                    existing += review.session.copy(id = id)
                }
            }
            BackfillResult(imported, skipped)
        }
        // No reminders or active-shift transitions; only the existing historical redraw path.
        if (result.imported.isNotEmpty()) withContext(NonCancellable) { onHistoricalChanged() }
        return result
    }

    suspend fun deleteHistorical(expected: WorkSession) {
        db.withTransaction {
            val current = checkNotNull(dao.session(expected.id)) { "Session no longer exists." }.model()
            check(current == expected) { "Time record changed. Reopen it and try again." }
            check(current.state == ShiftState.COMPLETE) { "Only completed shift records can be deleted. End the active shift first." }
            check(dao.deleteHistorical(current.id) == 1)
        }
        // Room commits before taking the notification/DataStore locks (same order as refresh).
        // Navigation cannot cancel cleanup; redraw still happens if cleanup reports an error.
        withContext(NonCancellable) {
            try { onSessionDeleted(expected.id) } finally { onHistoricalChanged() }
        }
    }

    private suspend fun checkOverlap(session: WorkSession, excluding: Long = 0) {
        dao.overlapping(session.clockIn.toEpochMilli(), requireNotNull(session.clockOut).toEpochMilli(), excluding)
            ?.let { throw SessionOverlapException(it.model()) }
    }

    /** Shared refresh owns the redraw; do not recursively invoke onChanged from its reconciliation. */
    suspend fun reconcileAutoLunch(now: Instant = clock.instant()): Boolean = db.withTransaction {
        val current = dao.active()?.model() ?: return@withTransaction false
        val target = current.autoLunchEndTarget ?: return@withTransaction false
        if (current.state != ShiftState.ON_LUNCH || now < target) return@withTransaction false
        dao.update(current.copy(lunchEnd = target, state = ShiftState.WORKING, lunchEndAutomatic = true).entity())
        true
    }

    private suspend fun <T> changed(historical: Boolean = false, block: suspend () -> T): T {
        val result = com.shifthud.backup.DataGate.with { block() }
        // Persistence is complete before requesting a redraw; navigation cannot cancel this handoff.
        withContext(NonCancellable) { if (historical) onHistoricalChanged() else onChanged() }
        return result
    }
}

data class ShiftSnapshot(val schedule: List<ScheduledShift>, val session: WorkSession?)
