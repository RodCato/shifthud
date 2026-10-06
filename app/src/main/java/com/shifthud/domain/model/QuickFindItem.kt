package com.shifthud.domain.model

import java.time.Instant
import java.util.Locale

fun cleanQuickFindText(value: String) = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
fun normalizeQuickFind(value: String) = cleanQuickFindText(value).lowercase(Locale.ROOT)
fun normalizeAliases(value: String): List<String> = value.split(',').map(::normalizeQuickFind).filter { it.isNotEmpty() }.distinct()
fun aisleLabel(value: String): String = cleanQuickFindText(value).let {
    if (Regex("[0-9]+[A-Za-z]?(-[0-9]+[A-Za-z]?)?").matches(it)) "Aisle $it" else it
}

data class QuickFindItem(
    val id: Long = 0,
    val name: String,
    val aisle: String,
    val locationNote: String = "",
    val aliases: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastUsedAt: Instant? = null,
    val useCount: Long = 0,
)

data class QuickFindResults(val matches: List<QuickFindItem> = emptyList(), val favorites: List<QuickFindItem> = emptyList(), val recent: List<QuickFindItem> = emptyList())

fun searchQuickFind(items: List<QuickFindItem>, query: String): QuickFindResults {
    val q = normalizeQuickFind(query)
    val ties = compareByDescending<QuickFindItem> { it.isFavorite }
        .thenByDescending { it.lastUsedAt }.thenByDescending { it.useCount }
        .thenBy { normalizeQuickFind(it.name) }.thenBy { it.id }
    if (q.isEmpty()) return QuickFindResults(
        favorites = items.filter { it.isFavorite }.sortedWith(ties),
        recent = items.filter { it.lastUsedAt != null }.sortedWith(compareByDescending<QuickFindItem> { it.lastUsedAt }.thenByDescending { it.useCount }.thenBy { it.id }).take(8),
    )
    fun rank(item: QuickFindItem): Int {
        val name = normalizeQuickFind(item.name)
        val aliases = item.aliases.map(::normalizeQuickFind)
        return when {
            name == q -> 0
            name.startsWith(q) -> 1
            q in aliases -> 2
            aliases.any { it.startsWith(q) } -> 3
            name.contains(q) -> 4
            aliases.any { it.contains(q) } -> 5
            normalizeQuickFind(item.aisle).contains(q) || normalizeQuickFind(item.locationNote).contains(q) -> 6
            else -> 7
        }
    }
    return QuickFindResults(matches = items.map { it to rank(it) }.filter { it.second < 7 }
        .sortedWith(compareBy<Pair<QuickFindItem, Int>> { it.second }.thenComparator { a,b -> ties.compare(a.first,b.first) }).map { it.first })
}
