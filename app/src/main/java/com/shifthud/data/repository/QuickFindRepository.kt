package com.shifthud.data.repository

import com.shifthud.backup.guardedTransaction as withTransaction
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.domain.model.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock

class DuplicateQuickFindItem(val existingId: Long) : IllegalArgumentException("An item with this name already exists. Edit it instead.")

class QuickFindRepository(private val db: ShiftDatabase, private val clock: Clock = Clock.systemUTC(), private val onChanged: suspend () -> Unit = {}) {
    private val dao = db.quickFind()
    private val guideDao = db.aisleGuide()
    val guide = guideDao.observe().map { rows -> rows.map { it.model() } }
    val items = dao.observeItems().map { rows -> rows.map { it.model() } }
    suspend fun save(id: Long?, name: String, aisle: String, note: String, aliases: String, favorite: Boolean): Long = changed {
        db.withTransaction {
            val cleanName = cleanQuickFindText(name)
            val cleanAisle = cleanQuickFindText(aisle)
            require(cleanName.isNotEmpty()) { "Enter an item name." }
            require(cleanAisle.isNotEmpty()) { "Enter an aisle or location." }
            dao.named(normalizeQuickFind(cleanName))?.let { if (it.id != id) throw DuplicateQuickFindItem(it.id) }
            val current = id?.let { checkNotNull(dao.get(it)) { "This item was deleted. Add it again if needed." }.model() }
            val now = clock.instant()
            val item = QuickFindItem(id ?: 0,cleanName,cleanAisle,cleanQuickFindText(note),normalizeAliases(aliases),favorite,
                current?.createdAt ?: now,now,current?.lastUsedAt,current?.useCount ?: 0)
            if (current == null) dao.insert(item.entity()) else { dao.update(item.entity()); current.id }
        }
    }
    suspend fun delete(id: Long) = changed { dao.delete(id) }
    suspend fun favorite(id: Long, favorite: Boolean) = changed { dao.favorite(id, favorite, clock.millis()) }
    suspend fun select(id: Long) = com.shifthud.backup.DataGate.with { dao.recordUse(id,clock.millis()) }
    suspend fun saveGuide(id: Long?, aisle: String, categories: String): Long = changed {
        db.withTransaction {
            val location = cleanQuickFindText(aisle)
            val labels = normalizeGuideCategories(categories)
            require(location.isNotEmpty()) { "Enter an aisle or location." }
            require(labels.isNotEmpty()) { "Enter at least one category." }
            guideDao.named(normalizeQuickFind(location))?.let {
                require(it.id == id) { "This location already has a guide entry. Edit that entry to change its categories." }
            }
            val current = id?.let { checkNotNull(guideDao.get(it)) { "This guide entry was deleted." }.model() }
            val now = clock.instant()
            val entry = AisleGuideEntry(id ?: 0,location,labels,current?.createdAt ?: now,now)
            if (current == null) guideDao.insert(entry.entity()) else { guideDao.update(entry.entity()); current.id }
        }
    }
    suspend fun deleteGuide(id: Long) = changed { guideDao.delete(id) }
    private suspend fun <T> changed(write: suspend () -> T): T {
        val result = com.shifthud.backup.DataGate.with { write() }
        // Commit first; widgets reread Room. No widget-owned copy and no callback under a Room lock.
        withContext(NonCancellable) { onChanged() }
        return result
    }
}
