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
    private val autoLunchSettings: suspend () -> AutoLunchSettings = { AutoLunchSettings() },
) {
    private val dao = db.shifts()
    val schedule = dao.observeSchedule().map { rows -> rows.map { it.model() } }
    val latestSession = dao.observeLatest().map { it?.model() }

    suspend fun save(shift: ScheduledShift) = changed { dao.save(shift.entity()) }
    suspend fun delete(id: Long) = changed { dao.delete(id) }

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

    suspend fun correct(expected: WorkSession, event: TimeEvent, value: Instant) = changed {
        db.withTransaction {
            val current = checkNotNull(dao.session(expected.id)) { "Session no longer exists." }.model()
            check(current == expected) { "Time record changed. Reopen the event and try again." }
            dao.update(correctTime(current, event, value, clock.instant()).entity())
        }
    }

    /** Shared refresh owns the redraw; do not recursively invoke onChanged from its reconciliation. */
    suspend fun reconcileAutoLunch(now: Instant = clock.instant()): Boolean = db.withTransaction {
        val current = dao.active()?.model() ?: return@withTransaction false
        val target = current.autoLunchEndTarget ?: return@withTransaction false
        if (current.state != ShiftState.ON_LUNCH || now < target) return@withTransaction false
        dao.update(current.copy(lunchEnd = target, state = ShiftState.WORKING, lunchEndAutomatic = true).entity())
        true
    }

    private suspend fun <T> changed(block: suspend () -> T): T {
        val result = block()
        // Persistence is complete before requesting a redraw; navigation cannot cancel this handoff.
        withContext(NonCancellable) { onChanged() }
        return result
    }
}

data class ShiftSnapshot(val schedule: List<ScheduledShift>, val session: WorkSession?)
