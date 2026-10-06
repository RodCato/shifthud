package com.shifthud.data.repository

import androidx.room.withTransaction
import com.shifthud.data.local.ShiftDatabase
import com.shifthud.data.local.entity.entity
import com.shifthud.domain.model.*
import kotlinx.coroutines.flow.map
import java.time.Clock

class DuplicateQuickFindItem(val existingId: Long) : IllegalArgumentException("An item with this name already exists. Edit it instead.")

class QuickFindRepository(private val db: ShiftDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = db.quickFind()
    val items = dao.observeItems().map { rows -> rows.map { it.model() } }
    suspend fun save(id: Long?, name: String, aisle: String, note: String, aliases: String, favorite: Boolean): Long = db.withTransaction {
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
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun favorite(id: Long, favorite: Boolean) = dao.favorite(id, favorite, clock.millis())
    suspend fun select(id: Long) = dao.recordUse(id,clock.millis())
}
