package com.shifthud.data.local
import androidx.room.*
import com.shifthud.data.local.dao.ShiftDao
import com.shifthud.data.local.entity.*

@Database(entities = [ScheduledShiftEntity::class, WorkSessionEntity::class, QuickFindItemEntity::class, AisleGuideEntity::class, PaycheckEntity::class, PayrollLineEntity::class, PayrollDepositEntity::class], version = 6, exportSchema = true)
abstract class ShiftDatabase : RoomDatabase() { abstract fun shifts(): ShiftDao
    abstract fun payroll(): com.shifthud.data.local.dao.PayrollDao
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

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN manuallyEntered INTEGER NOT NULL DEFAULT 0")
    }
}


/** Additive only: all existing work, schedule, and Quick Find tables remain untouched. */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS paychecks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, periodStart INTEGER NOT NULL, periodEnd INTEGER NOT NULL, payDate INTEGER, reportedHours TEXT, grossCents INTEGER, netCents INTEGER, deductionsComplete INTEGER NOT NULL, notes TEXT NOT NULL, reference TEXT NOT NULL, revision INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_paychecks_periodStart_periodEnd ON paychecks (periodStart, periodEnd)")
        db.execSQL("CREATE TABLE IF NOT EXISTS payroll_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, paycheckId INTEGER NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, cents INTEGER, FOREIGN KEY(paycheckId) REFERENCES paychecks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_payroll_lines_paycheckId ON payroll_lines (paycheckId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS payroll_deposits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, paycheckId INTEGER NOT NULL, date INTEGER, cents INTEGER, FOREIGN KEY(paycheckId) REFERENCES paychecks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_payroll_deposits_paycheckId ON payroll_deposits (paycheckId)")
    }
}
