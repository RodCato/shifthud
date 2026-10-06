package com.shifthud.data.local
import androidx.room.*
import com.shifthud.data.local.dao.ShiftDao
import com.shifthud.data.local.entity.*

@Database(entities = [ScheduledShiftEntity::class, WorkSessionEntity::class, QuickFindItemEntity::class, AisleGuideEntity::class], version = 4, exportSchema = true)
abstract class ShiftDatabase : RoomDatabase() { abstract fun shifts(): ShiftDao
    abstract fun aisleGuide(): com.shifthud.data.local.dao.AisleGuideDao
    abstract fun quickFind(): com.shifthud.data.local.dao.QuickFindDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN autoLunchMinutes INTEGER")
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN lunchEndAutomatic INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN correctionRevision INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS quick_find_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, normalizedName TEXT NOT NULL, aisle TEXT NOT NULL, locationNote TEXT NOT NULL, aliases TEXT NOT NULL, isFavorite INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastUsedAt INTEGER, useCount INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_quick_find_items_normalizedName ON quick_find_items (normalizedName)")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS aisle_guide (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, aisle TEXT NOT NULL, normalizedAisle TEXT NOT NULL, categories TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_aisle_guide_normalizedAisle ON aisle_guide (normalizedAisle)")
    }
}
