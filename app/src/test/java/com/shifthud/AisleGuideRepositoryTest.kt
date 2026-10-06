package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.QuickFindRepository
import com.shifthud.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AisleGuideRepositoryTest {
    private lateinit var db: ShiftDatabase
    private lateinit var repo: QuickFindRepository
    private var refreshes=0
    private val snapshots=mutableListOf<List<AisleGuideEntry>>()
    private val now=Instant.parse("2026-10-06T00:00:00Z")
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build()
        repo=QuickFindRepository(db,Clock.fixed(now,ZoneOffset.UTC),onChanged={refreshes++;snapshots+=db.aisleGuide().observe().first().map{it.model()}})
    }
    @After fun close(){db.close()}
    @Test fun guideCreateEditDeleteRefreshOnlyAfterCommit()=runBlocking {
        assertTrue(repo.guide.first().isEmpty())
        val id=repo.saveGuide(null," 5 "," Pasta, Rice, rice ")
        assertEquals("Pasta, Rice",snapshots.last().single().categories);assertEquals(1,refreshes)
        repo.saveGuide(id,"6","Rice, Soup")
        assertEquals("6",snapshots.last().single().aisle);assertEquals(2,refreshes)
        assertEquals(id,searchAisleGuide(repo.guide.first(),"soup").single().id)
        repo.deleteGuide(id);assertTrue(snapshots.last().isEmpty());assertEquals(3,refreshes)
    }
    @Test fun creationTimestampSurvivesEdit()=runBlocking {
        val id=repo.saveGuide(null,"Frozen","Pizza")
        val later=QuickFindRepository(db,Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC))
        later.saveGuide(id,"Frozen","Pizza, Ice cream")
        val entry=repo.guide.first().single();assertEquals(now,entry.createdAt);assertEquals(now.plusSeconds(60),entry.updatedAt)
    }
    @Test fun blankAndDuplicateEntriesRejectedWithoutRefreshOrMutation()=runBlocking {
        val id=repo.saveGuide(null,"Front End","Candy")
        assertTrue(runCatching{repo.saveGuide(null," front  END ","Gum")}.isFailure)
        assertTrue(runCatching{repo.saveGuide(null," ","Rice")}.isFailure)
        assertTrue(runCatching{repo.saveGuide(null,"2",", ,")}.isFailure)
        assertEquals(1,refreshes);assertEquals(id,repo.guide.first().single().id)
        repo.saveGuide(id,"Front end","Gum");assertEquals(2,refreshes)
    }
    @Test fun concurrentDuplicateMappingsHaveOneWinner()=runBlocking {
        val outcomes=coroutineScope{listOf(async{runCatching{repo.saveGuide(null,"2","Cereal")}},async{runCatching{repo.saveGuide(null," 2 ","Juice")}}).awaitAll()}
        assertEquals(1,outcomes.count{it.isSuccess});assertEquals(1,repo.guide.first().size);assertEquals(1,refreshes)
    }
    @Test fun favoriteAddEditUnfavoriteDeleteAllRefresh()=runBlocking {
        val id=repo.save(null,"Milk","2","","",true)
        repo.save(id,"Coconut milk","3","","",true)
        repo.favorite(id,false);repo.favorite(id,true);repo.delete(id)
        assertEquals(5,refreshes);assertTrue(repo.items.first().isEmpty())
    }
    @Test fun searchAndUseMetadataDoNotWriteWidgetCopiesOrRefresh()=runBlocking {
        val id=repo.save(null,"Milk","2","","",true)
        repo.saveGuide(null,"2","Rice")
        repeat(5){searchAisleGuide(repo.guide.first(),"rice");searchQuickFind(repo.items.first(),"milk")}
        repo.select(id)
        assertEquals(2,refreshes);assertEquals(1L,repo.items.first().single().useCount)
    }
    @Test fun deletedGuideCannotBeRecreatedByStaleEdit()=runBlocking {
        val id=repo.saveGuide(null,"1","Juice");repo.deleteGuide(id)
        assertTrue(runCatching{repo.saveGuide(id,"1","Soda")}.isFailure);assertTrue(repo.guide.first().isEmpty());assertEquals(2,refreshes)
    }
}
