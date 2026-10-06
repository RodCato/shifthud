package com.shifthud

import android.app.Application
import androidx.room.Room
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.repository.*
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
class QuickFindRepositoryTest {
    private lateinit var db: ShiftDatabase
    private lateinit var repo: QuickFindRepository
    private val now=Instant.parse("2026-10-06T00:00:00Z")
    @Before fun setup() {db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ShiftDatabase::class.java).build();repo=QuickFindRepository(db,Clock.fixed(now,ZoneOffset.UTC))}
    @After fun close() {db.close()}
    private suspend fun add(name: String="Worcestershire Sauce",aisle: String="4")=repo.save(null,name,aisle,"lower shelf"," WORC, worc, steak sauce ",false)
    @Test fun createNormalizedItem()=runBlocking {
        val id=add("  Worcestershire   Sauce  "," 4 ")
        val item=repo.items.first().single()
        assertEquals(id,item.id);assertEquals("Worcestershire Sauce",item.name);assertEquals("4",item.aisle)
        assertEquals(listOf("worc","steak sauce"),item.aliases);assertEquals(now,item.createdAt);assertEquals(now,item.updatedAt)
    }
    @Test fun editPreservesCreationAndUsageAndUpdatesFlow()=runBlocking {
        val id=add();repo.select(id)
        val later=QuickFindRepository(db,Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC))
        later.save(id,"New name","Frozen","back half","pizza",true)
        val item=repo.items.first().single()
        assertEquals("New name",item.name);assertEquals("Frozen",item.aisle);assertEquals(now,item.createdAt)
        assertEquals(now.plusSeconds(60),item.updatedAt);assertEquals(now,item.lastUsedAt);assertEquals(1L,item.useCount)
        assertTrue(searchQuickFind(listOf(item),"pizza").matches.isNotEmpty())
    }
    @Test fun deleteRemovesItem()=runBlocking {val id=add();repo.delete(id);assertTrue(repo.items.first().isEmpty())}
    @Test fun favoriteAndUnfavoritePreserveUse()=runBlocking {
        val id=add();repo.favorite(id,true);assertTrue(repo.items.first().single().isFavorite)
        repo.favorite(id,false);assertFalse(repo.items.first().single().isFavorite);assertEquals(0L,repo.items.first().single().useCount)
    }
    @Test fun onlyIntentionalSelectionUpdatesUsage()=runBlocking {
        val id=add();repeat(5){searchQuickFind(repo.items.first(),"worc")}
        assertEquals(0L,repo.items.first().single().useCount);assertNull(repo.items.first().single().lastUsedAt)
        repo.select(id);repo.select(id)
        val item=repo.items.first().single();assertEquals(now,item.lastUsedAt);assertEquals(2L,item.useCount)
    }
    @Test fun concurrentSelectionsAreNotLost()=runBlocking {
        val id=add();coroutineScope{repeat(10){launch{repo.select(id)}}}
        assertEquals(10L,repo.items.first().single().useCount)
    }
    @Test fun duplicateNamesOfferExistingIdentityAndDoNotOverwrite()=runBlocking {
        val id=add()
        val error=runCatching{add("  WORCESTERSHIRE\tSAUCE ")}.exceptionOrNull()
        assertTrue(error is DuplicateQuickFindItem);assertEquals(id,(error as DuplicateQuickFindItem).existingId)
        assertEquals(1,repo.items.first().size)
        val second=add("Honey")
        assertTrue(runCatching{repo.save(second,"Worcestershire Sauce","2","","",false)}.exceptionOrNull() is DuplicateQuickFindItem)
        assertEquals(2,repo.items.first().size)
    }
    @Test fun sameAisleIsAllowedAndRequiredFieldsAreValidated()=runBlocking {
        add();add("Honey");assertEquals(2,repo.items.first().size)
        assertTrue(runCatching{add(" ")}.isFailure);assertTrue(runCatching{add("Pizza"," ")}.isFailure)
        assertEquals(2,repo.items.first().size)
    }
    @Test fun editOwnNormalizedNameAndDeletedRecordHandling()=runBlocking {
        val id=add();repo.save(id,"WORCESTERSHIRE SAUCE","4","","",false)
        repo.delete(id)
        assertTrue(runCatching{repo.save(id,"Honey","2","","",false)}.isFailure)
        assertTrue(repo.items.first().isEmpty())
    }
    @Test fun concurrentDuplicateCreationHasSingleWinner()=runBlocking {
        val results=coroutineScope{listOf(async{runCatching{add("Honey")}},async{runCatching{add(" HONEY ")}}).awaitAll()}
        assertEquals(1,results.count{it.isSuccess});assertEquals(1,repo.items.first().size)
    }
}
