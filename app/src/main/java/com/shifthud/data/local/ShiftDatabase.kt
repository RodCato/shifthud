package com.shifthud.data.local
import androidx.room.*
import com.shifthud.data.local.dao.ShiftDao
import com.shifthud.data.local.entity.*

@Database(entities = [ScheduledShiftEntity::class, WorkSessionEntity::class], version = 1, exportSchema = true)
abstract class ShiftDatabase : RoomDatabase() { abstract fun shifts(): ShiftDao }
