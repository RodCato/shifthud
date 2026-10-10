package com.shifthud.data.local.entity

import androidx.room.*
import com.shifthud.domain.model.*
import org.json.JSONArray
import java.time.Instant

@Entity(tableName = "quick_find_items", indices = [Index(value = ["normalizedName"], unique = true)])
data class QuickFindItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val aisle: String,
    val locationNote: String,
    val aliases: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    val useCount: Long,
) {
    fun model(): QuickFindItem {
        val array = JSONArray(aliases)
        return QuickFindItem(id,name,aisle,locationNote,(0 until array.length()).map { array.getString(it) },isFavorite,
            Instant.ofEpochMilli(createdAt),Instant.ofEpochMilli(updatedAt),lastUsedAt?.let(Instant::ofEpochMilli),useCount)
    }
}
fun QuickFindItem.entity() = QuickFindItemEntity(id,name,normalizeQuickFind(name),aisle,locationNote,JSONArray(aliases).toString(),
    isFavorite,createdAt.toEpochMilli(),updatedAt.toEpochMilli(),lastUsedAt?.toEpochMilli(),useCount)
