package com.shifthud.data.local.dao
import androidx.room.*
import com.shifthud.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {
    @Query("SELECT * FROM work_sessions ORDER BY id") fun observeSessions(): Flow<List<WorkSessionEntity>>
    @Query("SELECT * FROM scheduled_shifts ORDER BY date, scheduledStart, id") fun observeSchedule(): Flow<List<ScheduledShiftEntity>>
    @Query("SELECT * FROM scheduled_shifts WHERE date = :date ORDER BY scheduledStart, id LIMIT 1") suspend fun today(date: Long): ScheduledShiftEntity?
    @Query("SELECT * FROM scheduled_shifts ORDER BY date, scheduledStart, id") suspend fun scheduleSnapshot(): List<ScheduledShiftEntity>
    @Query("SELECT * FROM work_sessions ORDER BY CASE WHEN state != 'COMPLETE' THEN 0 ELSE 1 END, COALESCE(clockOut, clockIn) DESC, clockIn DESC, id DESC LIMIT 1") suspend fun latest(): WorkSessionEntity?
    @Upsert suspend fun save(shift: ScheduledShiftEntity)
    @Query("DELETE FROM scheduled_shifts WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM work_sessions ORDER BY CASE WHEN state != 'COMPLETE' THEN 0 ELSE 1 END, COALESCE(clockOut, clockIn) DESC, clockIn DESC, id DESC LIMIT 1") fun observeLatest(): Flow<WorkSessionEntity?>
    @Query("SELECT * FROM work_sessions WHERE state != 'COMPLETE' LIMIT 1") suspend fun active(): WorkSessionEntity?
    @Insert suspend fun insert(session: WorkSessionEntity): Long
    @Query("SELECT * FROM work_sessions WHERE id = :id") suspend fun session(id: Long): WorkSessionEntity?
    @Query("SELECT * FROM work_sessions WHERE id != :excluding AND clockIn < :end AND (clockOut IS NULL OR clockOut > :start) ORDER BY clockIn LIMIT 1")
    suspend fun overlapping(start: Long, end: Long, excluding: Long = 0): WorkSessionEntity?
    @Query("DELETE FROM work_sessions WHERE id = :id AND manuallyEntered = 1 AND state = 'COMPLETE'")
    suspend fun deleteHistorical(id: Long): Int
    @Update suspend fun update(session: WorkSessionEntity)
}
