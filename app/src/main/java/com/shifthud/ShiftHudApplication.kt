package com.shifthud
import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.preferences.ShiftPreferences
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.ShiftEngine

class ShiftHudApplication : Application() {
    val engine by lazy { ShiftEngine() }
    private val database by lazy { Room.databaseBuilder(this, ShiftDatabase::class.java, "shifthud.db").build() }
    val repository by lazy { ShiftRepository(database, engine) }
    val preferences by lazy { ShiftPreferences(this) }
}
