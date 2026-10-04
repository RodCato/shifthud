package com.shifthud.data.repository
import androidx.room.withTransaction
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.*
import com.shifthud.domain.model.*
import com.shifthud.domain.usecase.ShiftEngine
import kotlinx.coroutines.flow.map
import java.time.*

class ShiftRepository(private val db: ShiftDatabase, private val engine: ShiftEngine, private val clock: Clock = Clock.systemDefaultZone()) {
    private val dao = db.shifts()
    val schedule = dao.observeSchedule().map { rows -> rows.map { it.model() } }
    val latestSession = dao.observeLatest().map { it?.model() }
    suspend fun save(shift: ScheduledShift) = dao.save(shift.entity())
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clockIn() = db.withTransaction {
        check(dao.active() == null) { "A shift is already active." }
        dao.insert(engine.clockIn(dao.today(LocalDate.now(clock).toEpochDay())?.id).entity())
    }
    suspend fun transition(expectedId: Long, expectedState: ShiftState, change: (WorkSession) -> WorkSession) = db.withTransaction {
        val current = checkNotNull(dao.active()) { "No active shift." }.model()
        check(current.id == expectedId && current.state == expectedState) { "Shift changed. Try again." }
        dao.update(change(current).entity())
    }
}
