package com.shifthud
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.widget.WidgetRefresh

class ShiftHudApplication : Application() {
    val backups by lazy { com.shifthud.backup.BackupManager(this) }
    var recoveryError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    fun blockForRecovery() {
        recoveryError="Backup recovery could not finish. Force-stop and reopen ShiftHUD to retry. Do not uninstall or clear app data; your original recovery copy is retained."
        com.shifthud.backup.DataGate.blocked=true
    }
    override fun onCreate() {
        super.onCreate()
        try { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { backups.recover() } }
        catch (_:Exception) { blockForRecovery() }
        if(recoveryError==null && runCatching{androidx.work.WorkManager.getInstance(this)}.isFailure)androidx.work.WorkManager.initialize(this,androidx.work.Configuration.Builder().build())
    }

    val upcoming: com.shifthud.notification.upcoming.UpcomingReminders by lazy { com.shifthud.notification.upcoming.UpcomingReminders(this, schedule = { repository.schedule.first() }) }
    val notifications by lazy { com.shifthud.notification.ShiftNotifications(this) }
    val payRates by lazy { com.shifthud.data.preferences.PayRatePreferences(this) }
    val engine by lazy { ShiftEngine() }
    internal val database by lazy { Room.databaseBuilder(this, ShiftDatabase::class.java, "shifthud.db").addMigrations(com.shifthud.data.local.MIGRATION_1_2, com.shifthud.data.local.MIGRATION_2_3, com.shifthud.data.local.MIGRATION_3_4, com.shifthud.data.local.MIGRATION_4_5, com.shifthud.data.local.MIGRATION_5_6).build() }
    val widgetRefresh by lazy { WidgetRefresh(this) }
    val repository by lazy { ShiftRepository(database, engine, onChanged = { widgetRefresh.refresh() }, onHistoricalChanged = { widgetRefresh.redrawHistorical() }, autoLunchSettings = { preferences.autoLunchSettings.first() }, onSessionDeleted = { notifications.forgetSession(it) }, onScheduleChanged = { upcoming.reschedule(debounce = true) }) }
    val payroll by lazy { com.shifthud.data.repository.PayrollRepository(database) }
    val quickFind by lazy { com.shifthud.data.repository.QuickFindRepository(database, onChanged = { widgetRefresh.refresh() }) }
    val preferences by lazy { ShiftPreferences(this, onChanged = { widgetRefresh.refresh() }) }
}
