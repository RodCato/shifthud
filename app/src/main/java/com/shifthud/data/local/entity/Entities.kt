package com.shifthud.data.local.entity

import androidx.room.*
import com.shifthud.domain.model.*
import java.time.*

@Entity(tableName = "scheduled_shifts")
data class ScheduledShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long, val scheduledStart: Int, val scheduledEnd: Int,
    val plannedLunchMinutes: Int?, val notes: String?,
) {
    fun model() = ScheduledShift(id, LocalDate.ofEpochDay(date), LocalTime.ofSecondOfDay(scheduledStart.toLong()), LocalTime.ofSecondOfDay(scheduledEnd.toLong()), plannedLunchMinutes, notes)
}
fun ScheduledShift.entity() = ScheduledShiftEntity(id, date.toEpochDay(), scheduledStart.toSecondOfDay(), scheduledEnd.toSecondOfDay(), plannedLunchMinutes, notes)
@Entity(tableName = "work_sessions", foreignKeys = [ForeignKey(entity = ScheduledShiftEntity::class, parentColumns = ["id"], childColumns = ["scheduledShiftId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("scheduledShiftId")])
data class WorkSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val scheduledShiftId: Long?,
    val clockIn: Long, val lunchStart: Long?, val lunchEnd: Long?, val clockOut: Long?, val state: String,
    val autoLunchMinutes: Int? = null,
    @ColumnInfo(defaultValue = "0") val lunchEndAutomatic: Boolean = false,
    @ColumnInfo(defaultValue = "0") val correctionRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val manuallyEntered: Boolean = false,
) {
    fun model() = WorkSession(id, scheduledShiftId, Instant.ofEpochMilli(clockIn), lunchStart?.let(Instant::ofEpochMilli), lunchEnd?.let(Instant::ofEpochMilli), clockOut?.let(Instant::ofEpochMilli), ShiftState.valueOf(state), autoLunchMinutes, lunchEndAutomatic, correctionRevision, manuallyEntered)
}
fun WorkSession.entity() = WorkSessionEntity(id, scheduledShiftId, clockIn.toEpochMilli(), lunchStart?.toEpochMilli(), lunchEnd?.toEpochMilli(), clockOut?.toEpochMilli(), state.name, autoLunchMinutes, lunchEndAutomatic, correctionRevision, manuallyEntered)
