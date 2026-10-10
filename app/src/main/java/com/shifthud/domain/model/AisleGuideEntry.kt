package com.shifthud.domain.model

import java.time.Instant

data class AisleGuideEntry(val id: Long = 0, val aisle: String, val categories: String, val createdAt: Instant, val updatedAt: Instant)

fun guideCategoryLabel(categories: String) = categories.split(',').map(::cleanQuickFindText).filter { it.isNotEmpty() }.joinToString(" · ")
fun normalizeGuideCategories(categories: String) = categories.split(',').map(::cleanQuickFindText).filter { it.isNotEmpty() }
    .distinctBy(::normalizeQuickFind).joinToString(", ")

// Numeric aisles sort naturally before named departments; never assume locations must be integers.
fun searchAisleGuide(entries: List<AisleGuideEntry>, query: String): List<AisleGuideEntry> {
    val q = normalizeQuickFind(query)
    return entries.filter { q.isEmpty() || normalizeQuickFind(it.aisle).contains(q) || normalizeQuickFind(it.categories).contains(q) }
        .sortedWith(compareBy<AisleGuideEntry> { it.aisle.toBigIntegerOrNull() == null }
            .thenBy { it.aisle.toBigIntegerOrNull() }.thenBy { normalizeQuickFind(it.aisle) }.thenBy { it.id })
}
