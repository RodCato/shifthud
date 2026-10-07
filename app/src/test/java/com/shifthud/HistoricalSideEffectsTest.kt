package com.shifthud

import android.app.NotificationManager
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.usecase.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ShiftHudApplication::class)
class HistoricalSideEffectsTest {
    @Test fun historicalInsertionAndRedrawDoNotStartServiceOrPostNotifications()=runBlocking {
        val app=RuntimeEnvironment.getApplication() as ShiftHudApplication
        val db=Room.inMemoryDatabaseBuilder(app,ShiftDatabase::class.java).build()
        try {
            val clock=Clock.fixed(Instant.parse("2026-10-06T18:00:00Z"),ZoneOffset.UTC)
            val repo=ShiftRepository(db,ShiftEngine(clock),clock,onHistoricalChanged={app.widgetRefresh.redrawHistorical()},
                onChanged={error("Historical entry must not use live refresh")})
            repo.addHistorical(HistoricalShiftInput(LocalDate.of(2026,10,3),LocalTime.of(4,0),LocalTime.of(13,0)),ZoneOffset.UTC)
            assertNull(Shadows.shadowOf(app).nextStartedService)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            assertNull(db.shifts().active())
        } finally {db.close()}
    }
}
