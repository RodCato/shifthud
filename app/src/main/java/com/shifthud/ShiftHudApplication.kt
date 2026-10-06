package com.shifthud
import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.widget.WidgetRefresh

class ShiftHudApplication : Application() {
    val notifications by lazy { com.shifthud.notification.ShiftNotifications(this) }
    val payRates by lazy { com.shifthud.data.preferences.PayRatePreferences(this) }
    val engine by lazy { ShiftEngine() }
    private val database by lazy { Room.databaseBuilder(this, ShiftDatabase::class.java, "shifthud.db").addMigrations(com.shifthud.data.local.MIGRATION_1_2, com.shifthud.data.local.MIGRATION_2_3, com.shifthud.data.local.MIGRATION_3_4).build() }
    val widgetRefresh by lazy { WidgetRefresh(this) }
    val repository by lazy { ShiftRepository(database, engine, onChanged = { widgetRefresh.refresh() }, autoLunchSettings = { preferences.autoLunchSettings.first() }) }
    val quickFind by lazy { com.shifthud.data.repository.QuickFindRepository(database, onChanged = { widgetRefresh.refresh() }) }
    val preferences by lazy { ShiftPreferences(this, onChanged = { widgetRefresh.refresh() }) }
}
