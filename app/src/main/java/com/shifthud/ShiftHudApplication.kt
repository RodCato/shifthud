package com.shifthud
import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.widget.WidgetRefresh

class ShiftHudApplication : Application() {
    val notifications by lazy { com.shifthud.notification.ShiftNotifications(this) }
    val engine by lazy { ShiftEngine() }
    private val database by lazy { Room.databaseBuilder(this, ShiftDatabase::class.java, "shifthud.db").build() }
    val widgetRefresh by lazy { WidgetRefresh(this) }
    val repository by lazy { ShiftRepository(database, engine, onChanged = { widgetRefresh.refresh() }) }
    val preferences by lazy { ShiftPreferences(this, onChanged = { widgetRefresh.refresh() }) }
}
