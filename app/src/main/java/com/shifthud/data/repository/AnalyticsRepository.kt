package com.shifthud.data.repository

import com.shifthud.domain.analytics.*
import com.shifthud.domain.pay.*
import com.shifthud.domain.usecase.ShiftEngine
import com.shifthud.domain.weekly.WeeklyTargetSettings
import kotlinx.coroutines.flow.*
import java.time.*

/** Read-only, range-scoped flows. Schedules are detail context, never worked contributions. */
class AnalyticsRepository(private val records: ShiftRepository, private val rates: Flow<List<PayRate>>, private val targets: Flow<WeeklyTargetSettings>, engine: ShiftEngine) {
    private val estimator = PayEstimator(engine)
    fun observe(period: AnalyticsPeriod, zone: ZoneId, ticks: Flow<Instant>): Flow<WorkAnalytics> = combine(
        records.sessionsBetween(period.start, period.endExclusive, zone),
        records.scheduleBetween(period.start, period.endExclusive), rates, targets, ticks,
    ) { sessions, schedule, payRates, target, now -> workAnalytics(period, sessions, schedule, payRates, now, zone, estimator, target) }
}
