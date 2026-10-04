package com.shifthud.widget

import com.shifthud.data.repository.ShiftRepository
import com.shifthud.domain.model.ShiftState
import com.shifthud.domain.usecase.ShiftEngine
import kotlinx.coroutines.CancellationException

/** Persist through existing transactions; stale buttons may never mutate a different session. */
class WidgetActionExecutor(
    private val repository: ShiftRepository,
    private val engine: ShiftEngine,
    private val refresh: suspend () -> Unit,
) {
    suspend fun execute(command: WidgetCommand): Boolean {
        return try {
            when (command.operation) {
                WidgetOperation.CLOCK_IN -> repository.clockIn(command.sessionId, command.date)
                WidgetOperation.START_LUNCH -> repository.transition(command.sessionId, ShiftState.WORKING, engine::startLunch)
                WidgetOperation.END_LUNCH -> repository.transition(command.sessionId, ShiftState.ON_LUNCH, engine::endLunch)
                WidgetOperation.REFRESH -> Unit
            }
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        finally { refresh() }
    }
}
