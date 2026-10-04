package com.shifthud.domain.usecase

import com.shifthud.domain.model.*
import java.time.*

class ShiftEngine(private val clock: Clock = Clock.systemUTC()) {
    fun state(session: WorkSession?) = session?.state ?: ShiftState.NOT_STARTED
    fun clockIn(scheduledShiftId: Long? = null): WorkSession = WorkSession(scheduledShiftId = scheduledShiftId, clockIn = clock.instant())
    fun startLunch(session: WorkSession): WorkSession {
        require(session.state == ShiftState.WORKING && session.lunchStart == null) { "Only one lunch is supported per shift." }
        return session.copy(lunchStart = nowAfter(session.clockIn), state = ShiftState.ON_LUNCH)
    }
    fun endLunch(session: WorkSession): WorkSession {
        require(session.state == ShiftState.ON_LUNCH) { "Lunch is not in progress." }
        return session.copy(lunchEnd = nowAfter(requireNotNull(session.lunchStart)), state = ShiftState.WORKING)
    }
    fun clockOut(session: WorkSession): WorkSession {
        require(session.state == ShiftState.WORKING) { "End lunch before clocking out." }
        return session.copy(clockOut = nowAfter(session.lunchEnd ?: session.clockIn), state = ShiftState.COMPLETE)
    }
    private fun nowAfter(previous: Instant): Instant = clock.instant().also { require(it >= previous) { "Device time is earlier than the last event. Check your clock." } }
    fun durations(session: WorkSession, thresholdMinutes: Int, now: Instant = clock.instant()): ShiftDurations {
        require(thresholdMinutes > 0)
        val end = session.clockOut ?: maxOf(now, session.lunchEnd ?: session.lunchStart ?: session.clockIn)
        val store = Duration.between(session.clockIn, end)
        val lunch = session.lunchStart?.let { Duration.between(it, session.lunchEnd ?: end) } ?: Duration.ZERO
        val active = store.minus(lunch)
        return ShiftDurations(store, active, active, lunch, Duration.ofMinutes(thresholdMinutes.toLong()).minus(active))
    }
}
