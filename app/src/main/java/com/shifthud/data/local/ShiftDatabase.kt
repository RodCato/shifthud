package com.shifthud.data.local
import androidx.room.*
import com.shifthud.data.local.dao.ShiftDao
import com.shifthud.data.local.entity.*

@Database(entities = [ScheduledShiftEntity::class, WorkSessionEntity::class], version = 2, exportSchema = true)
abstract class ShiftDatabase : RoomDatabase() { abstract fun shifts(): ShiftDao }

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN autoLunchMinutes INTEGER")
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN lunchEndAutomatic INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN correctionRevision INTEGER NOT NULL DEFAULT 0")
    }
}
