package com.shifthud.data.local.dao

import androidx.room.*
import com.shifthud.data.local.entity.QuickFindItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickFindDao {
    @Query("SELECT * FROM quick_find_items ORDER BY normalizedName, id") fun observeItems(): Flow<List<QuickFindItemEntity>>
    @Query("SELECT * FROM quick_find_items WHERE id = :id") suspend fun get(id: Long): QuickFindItemEntity?
    @Query("SELECT * FROM quick_find_items WHERE normalizedName = :name") suspend fun named(name: String): QuickFindItemEntity?
    @Insert suspend fun insert(item: QuickFindItemEntity): Long
    @Update suspend fun update(item: QuickFindItemEntity)
    @Query("DELETE FROM quick_find_items WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE quick_find_items SET isFavorite = :favorite, updatedAt = :now WHERE id = :id") suspend fun favorite(id: Long, favorite: Boolean, now: Long)
    @Query("UPDATE quick_find_items SET lastUsedAt = :now, useCount = useCount + 1 WHERE id = :id") suspend fun recordUse(id: Long, now: Long)
}
