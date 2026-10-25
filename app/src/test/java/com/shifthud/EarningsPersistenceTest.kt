package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.PayrollRepository
import com.shifthud.domain.payroll.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class EarningsPersistenceTest {
    @Test fun savedConfirmationEditsDeletionAndReopenRefreshDerivedTotals()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val name="earnings-isolated-test.db"
        app.deleteDatabase(name)
        fun open()=Room.databaseBuilder(app,ShiftDatabase::class.java,name).build()
        var db=open()
        try {
            var repo=PayrollRepository(db)
            val start=LocalDate.of(2026,1,3)
            val draft=Paycheck(periodStart=start,periodEnd=start.plusDays(6),grossCents=10001)
            assertTrue(repo.paychecks.first().isEmpty()) // unconfirmed draft is not persisted
            var result:EarningsOverview?=null
            val job=launch {repo.paychecks.collect {result=earningsOverview(it)}}
            suspend fun awaitCount(count:Int)=withTimeout(5000){while(result?.checks?.size!=count)delay(10)}
            awaitCount(0)
            repo.save(draft);awaitCount(1)
            val saved=repo.paychecks.first().single()
            repo.save(saved.copy(grossCents=20002))
            withTimeout(5000) {while(result?.totals()?.gross?.known?.toPlainString()!="200.02")delay(10)}
            job.cancelAndJoin();db.close();db=open();repo=PayrollRepository(db)
            assertEquals("200.02",earningsOverview(repo.paychecks.first()).totals().gross.known.toPlainString())
            // Test deletion invalidation without adding a new production mutation API.
            val deletion=launch {repo.paychecks.collect {result=earningsOverview(it)}}
            withContext(Dispatchers.IO) {db.openHelper.writableDatabase.execSQL("DELETE FROM paychecks");db.invalidationTracker.refreshAsync()}
            awaitCount(0);deletion.cancelAndJoin()
        } finally {db.close();app.deleteDatabase(name)}
    }
}
