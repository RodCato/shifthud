package com.shifthud.data.repository

import androidx.room.withTransaction
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.*
import com.shifthud.domain.model.*
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

    suspend fun save(shift: ScheduledShift) = changed { dao.save(shift.entity()) }.also { withContext(NonCancellable) { onScheduleChanged() } }
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
            val session = input.session(zone, clock.instant())
            checkOverlap(session)
            val linked = historicalSchedule(session, dao.scheduleSnapshot().map { it.model() }, zone)
            dao.insert(session.copy(scheduledShiftId = linked).entity())
        }
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
        val result = block()
        // Persistence is complete before requesting a redraw; navigation cannot cancel this handoff.
        withContext(NonCancellable) { if (historical) onHistoricalChanged() else onChanged() }
        return result
    }
}

data class ShiftSnapshot(val schedule: List<ScheduledShift>, val session: WorkSession?)
