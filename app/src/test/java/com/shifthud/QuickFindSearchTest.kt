package com.shifthud

import com.shifthud.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale

class QuickFindSearchTest {
    private val now=Instant.parse("2026-10-06T00:00:00Z")
    private fun item(id: Long=1,name: String="Worcestershire Sauce",aisle: String="4",note: String="Condiments · lower shelf",aliases: List<String> = listOf("worc","steak sauce"),favorite: Boolean=false,last: Instant?=null,count: Long=0)=QuickFindItem(id,name,aisle,note,aliases,favorite,now,now,last,count)
    private fun search(q: String)=searchQuickFind(listOf(item()),q).matches
    @Test fun exactName() { assertEquals(1,search("Worcestershire Sauce").size) }
    @Test fun ignoresCase() { assertEquals(search("worc"),search("WORC")) }
    @Test fun namePrefix() { assertEquals(1,search("Worcester").size) }
    @Test fun partialName() { assertEquals(1,search("cestershire").size) }
    @Test fun aliasExact() { assertEquals(1,search("steak sauce").size) }
    @Test fun aliasPrefix() { assertEquals(1,search("steak").size) }
    @Test fun aliasPartial() { assertEquals(1,search("eak sau").size) }
    @Test fun locationMatches() { assertEquals(1,search("4").size);assertEquals(1,searchQuickFind(listOf(item(aisle="Frozen")),"froz").matches.size) }
    @Test fun noteMatches() { assertEquals(1,search("lower").size) }
    @Test fun noMatch() { assertTrue(search("bread").isEmpty()) }
    @Test fun whitespaceTolerant() { assertEquals(search("steak sauce"),search("  STEAK\t\n SAUCE  ")) }
    @Test fun unicodeWhitespaceNormalizes() { assertEquals("steak sauce",normalizeQuickFind(" STEAK\u00a0 sauce ")) }
    @Test fun normalizationIsLocaleIndependent() {
        val previous=Locale.getDefault()
        try {Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("ice",normalizeQuickFind("ICE"))} finally {Locale.setDefault(previous)}
    }
    @Test fun ranksExactNameAbovePrefixAndPartialRegardlessOfFavorites() {
        val data=listOf(item(1,"Soy sauce",favorite=true),item(2,"Sauce bottles"),item(3,"Sauce"))
        assertEquals(listOf(3L,2L,1L),searchQuickFind(data,"sauce").matches.map{it.id})
    }
    @Test fun fullRankingOrder() {
        val data=listOf(item(7,"Z",note="sauce",aliases=emptyList()),item(6,"Z",note="",aliases=listOf("soy sauce")),
            item(5,"Soy sauce",aliases=emptyList()),item(4,"Z",aliases=listOf("sauces")),item(3,"Z",aliases=listOf("sauce")),
            item(2,"Sauce bottle",aliases=emptyList()),item(1,"Sauce",aliases=emptyList()))
        assertEquals((1L..7L).toList(),searchQuickFind(data,"sauce").matches.map{it.id})
    }
    @Test fun aliasRanksAboveNote() {
        assertEquals(listOf(2L,1L),searchQuickFind(listOf(item(1,"A",note="honey",aliases=emptyList()),item(2,"B",aliases=listOf("honey"))),"honey").matches.map{it.id})
    }
    @Test fun favoriteThenRecentBreakTies() {
        val items=listOf(item(1,last=now),item(2,favorite=true),item(3,last=now.minusSeconds(60)))
        assertEquals(listOf(2L,1L,3L),searchQuickFind(items,"worc").matches.map{it.id})
    }
    @Test fun emptyQueryReturnsFavoritesAndRecent() {
        val results=searchQuickFind(listOf(item(1,favorite=true),item(2,last=now)),"  ")
        assertTrue(results.matches.isEmpty());assertEquals(1L,results.favorites.single().id);assertEquals(2L,results.recent.single().id)
    }
    @Test fun recentOrderedAndLimitedToEight() {
        val data=(1L..12L).map{item(it,last=now.plusSeconds(it))}
        assertEquals((12L downTo 5L).toList(),searchQuickFind(data,"").recent.map{it.id})
    }
    @Test fun favoritesRemainEvenIfNeverOrLongAgoUsed() {
        val data=(1L..12L).map{item(it,last=now.plusSeconds(it))}+item(100,favorite=true)
        assertEquals(100L,searchQuickFind(data,"").favorites.single().id)
    }
    @Test fun searchingDoesNotMutateUsage() {
        val data=listOf(item());repeat(10){searchQuickFind(data,"worc")}
        assertEquals(0L,data.single().useCount);assertNull(data.single().lastUsedAt)
    }
    @Test fun numericAndSimpleAisleLabels() {assertEquals("Aisle 4",aisleLabel(" 4 "));assertEquals("Aisle 12A",aisleLabel("12A"));assertEquals("Aisle 4-5",aisleLabel("4-5"))}
    @Test fun namedLocationLabels() {listOf("Frozen","Produce","Front End","Aisle 4").forEach{assertEquals(it,aisleLabel(it))}}
    @Test fun aliasesNormalizedDistinctAndEmptyRemoved() {assertEquals(listOf("worc","steak sauce"),normalizeAliases(" WORC, ,worc, Steak   Sauce, "))}
}
