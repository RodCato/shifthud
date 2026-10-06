package com.shifthud.widget

import com.shifthud.domain.model.*

data class WidgetReferences(val favorites: List<String> = emptyList(), val guide: List<String> = emptyList(), val moreFavorites: Int = 0, val moreGuide: Int = 0)

// Presentation only: derived from Room on every composition/refresh, never persisted separately.
fun widgetReferences(items: List<QuickFindItem>, guide: List<AisleGuideEntry>, width: Float, height: Float, completedLunch: Boolean): WidgetReferences {
    if(width < 320 || height < 300) return WidgetReferences()
    val favorites=items.filter {it.isFavorite}.sortedWith(compareBy<QuickFindItem>{normalizeQuickFind(it.name)}.thenBy{it.id})
    val sorted=searchAisleGuide(guide, "")
    val favoriteLimit=if(height>=360)2 else 1
    val guideLimit=if(height>=360)6 else if(completedLunch)2 else 4
    return WidgetReferences(favorites.take(favoriteLimit).map {"${it.name} · ${aisleLabel(it.aisle)}"},
        sorted.take(guideLimit).map {"${it.aisle}  ${guideCategoryLabel(it.categories)}"},
        (favorites.size-favoriteLimit).coerceAtLeast(0),(sorted.size-guideLimit).coerceAtLeast(0))
}
