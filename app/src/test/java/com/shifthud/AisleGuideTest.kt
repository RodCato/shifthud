package com.shifthud

import com.shifthud.domain.model.*
import com.shifthud.widget.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AisleGuideTest {
    private val now=Instant.parse("2026-10-06T00:00:00Z")
    private fun entry(id: Long=1,aisle: String="5",categories: String="Pasta, Rice")=AisleGuideEntry(id,aisle,categories,now,now)
    private fun item(id: Long=1,name: String="Coconut milk",favorite: Boolean=true)=QuickFindItem(id,name,"2",isFavorite=favorite,createdAt=now,updatedAt=now)
    @Test fun categorySearchReturnsMapping() {assertEquals("5",searchAisleGuide(listOf(entry()),"rice").single().aisle)}
    @Test fun caseWhitespaceAndPartialSearch() {assertEquals(1,searchAisleGuide(listOf(entry(categories="Long grain rice")),"  LONG   GRAIN ").size);assertEquals(1,searchAisleGuide(listOf(entry()),"RIC").size)}
    @Test fun namedAndNumericLocationsAreSearchable() {assertEquals(1,searchAisleGuide(listOf(entry()),"5").size);assertEquals(1,searchAisleGuide(listOf(entry(aisle="Frozen")),"froz").size)}
    @Test fun categoriesNormalizedWithoutDisplayFormattingInStorage() {assertEquals("Pasta, rice",normalizeGuideCategories(" Pasta, rice , RICE, "));assertEquals("Pasta · Rice",guideCategoryLabel("Pasta, Rice"))}
    @Test fun emptyQuerySortsNumericAislesNaturallyThenNamedLocations() {
        val result=searchAisleGuide(listOf(entry(1,"10"),entry(2,"2"),entry(3,"Frozen"),entry(4,"1")),"")
        assertEquals(listOf("1","2","10","Frozen"),result.map{it.aisle})
    }
    @Test fun noFabricatedDefaultGuideOrFavorites() {assertEquals(WidgetReferences(),widgetReferences(emptyList(),emptyList(),320f,300f,false))}
    @Test fun compactAndNormalStayShiftFocused() {listOf(180f to 200f,280f to 240f).forEach{(w,h)->assertEquals(WidgetReferences(),widgetReferences(listOf(item()),listOf(entry()),w,h,false))}}
    @Test fun expandedPullsFavoriteAndGuideContent() {
        val data=widgetReferences(listOf(item(),item(2,"Not favorite",false)),listOf(entry()),320f,300f,false)
        assertEquals(listOf("Coconut milk · Aisle 2"),data.favorites);assertEquals(listOf("5  Pasta · Rice"),data.guide)
    }
    @Test fun editsAndUnfavoritingImmediatelyChangeDerivedWidgetData() {
        val old=item()
        assertEquals(listOf("Milk · Frozen"),widgetReferences(listOf(old.copy(name="Milk",aisle="Frozen")),listOf(entry(categories="Soup")),320f,300f,false).favorites)
        assertTrue(widgetReferences(listOf(old.copy(isFavorite=false)),emptyList(),320f,300f,false).favorites.isEmpty())
        assertEquals(listOf("5  Soup"),widgetReferences(emptyList(),listOf(entry(categories="Soup")),320f,300f,false).guide)
    }
    @Test fun referenceRowsAreBoundedAndOverflowIndicated() {
        val data=widgetReferences((1L..10L).map{item(it,"Item $it")},(1L..12L).map{entry(it,it.toString())},320f,300f,false)
        assertEquals(1,data.favorites.size);assertEquals(4,data.guide.size);assertEquals(9,data.moreFavorites);assertEquals(8,data.moreGuide)
    }
    @Test fun completedLunchReservesSpaceForCriticalInformation() {assertEquals(2,widgetReferences(emptyList(),(1L..8).map{entry(it,it.toString())},320f,300f,true).guide.size)}
    @Test fun tallerWidgetsShowMoreRows() {
        val data=widgetReferences((1L..4).map{item(it)},(1L..8).map{entry(it,it.toString())},320f,360f,true)
        assertEquals(2,data.favorites.size);assertEquals(6,data.guide.size)
    }
    @Test fun favoriteOrderingStableAcrossRecentUseChanges() {
        val a=item(1,"Apple");val b=item(2,"Banana")
        assertEquals(listOf("Apple · Aisle 2"),widgetReferences(listOf(b.copy(lastUsedAt=now,useCount=100),a),emptyList(),320f,300f,false).favorites)
    }
}
