package com.shifthud.widget

import com.shifthud.domain.model.*

data class WidgetGuideRow(val location: String, val categories: String)

data class WidgetReferences(val favorites: List<String> = emptyList(), val guide: List<WidgetGuideRow> = emptyList(), val moreFavorites: Int = 0, val moreGuide: Int = 0)

// Presentation only: derived from Room on every composition/refresh, never persisted separately.
fun widgetReferences(items: List<QuickFindItem>, guide: List<AisleGuideEntry>, width: Float, height: Float, completedLunch: Boolean): WidgetReferences {
    if(width < 320 || height < 300) return WidgetReferences()
    val favorites=items.filter {it.isFavorite}.sortedWith(compareBy<QuickFindItem>{normalizeQuickFind(it.name)}.thenBy{it.id})
    val sorted=searchAisleGuide(guide, "")
    val favoriteLimit=if(height>=360)2 else 1
    // Reserve two category lines and row/section spacing without shrinking shift
    // information or the primary action. Completed lunch needs an extra text line.
    val guideLimit=(if(height>=360) 3 else 2) - (if(completedLunch) 1 else 0)
    return WidgetReferences(favorites.take(favoriteLimit).map {"${it.name} · ${aisleLabel(it.aisle)}"},
        sorted.take(guideLimit).map {WidgetGuideRow(it.aisle, guideCategoryLabel(it.categories))},
        (favorites.size-favoriteLimit).coerceAtLeast(0),(sorted.size-guideLimit).coerceAtLeast(0))
}
