package com.shifthud

import com.shifthud.domain.model.WorkSession
import com.shifthud.domain.model.ShiftState
import com.shifthud.ui.completedLunch
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class CompletedLunchTest {
    private val start = Instant.parse("2026-10-05T14:53:00Z")
    private val end = Instant.parse("2026-10-05T15:43:00Z")
    private val session = WorkSession(clockIn = start.minusSeconds(3600), lunchStart = start, lunchEnd = end)
    @Test fun sharedDashboardAndWidgetPresentationUsesLocalIntervalAndTimestampDuration() {
        val lunch = completedLunch(session, ZoneId.of("America/Chicago"), Locale.US, false)!!
        assertEquals(start, lunch.start)
        assertEquals(end, lunch.end)
        assertEquals(Duration.ofMinutes(50), lunch.duration)
        assertEquals("Lunch taken\n9:53 AM – 10:43 AM · 50m", lunch.detail)
        assertEquals("Lunch taken · 50m\n9:53 AM – 10:43 AM", lunch.compactDetail)
    }
    @Test fun incompleteLunchCannotReplaceCountdownWithCompletedInterval() {
        assertNull(completedLunch(session.copy(lunchEnd = null, state = ShiftState.ON_LUNCH), ZoneOffset.UTC, Locale.US, false))
        assertNull(completedLunch(session.copy(lunchStart = null, lunchEnd = null), ZoneOffset.UTC, Locale.US, false))
    }
    @Test fun overnightDurationUsesInstantsRatherThanTimeOfDaySubtraction() {
        val overnight = session.copy(lunchStart = Instant.parse("2026-10-05T23:50:00Z"), lunchEnd = Instant.parse("2026-10-06T00:20:00Z"))
        val lunch = completedLunch(overnight, ZoneOffset.UTC, Locale.UK, true)!!
        assertEquals("23:50 – 00:20", lunch.interval)
        assertEquals("30m", lunch.durationLabel)
    }
}
