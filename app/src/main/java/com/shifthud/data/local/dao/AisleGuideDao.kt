package com.shifthud.data.local.dao

import androidx.room.*
import com.shifthud.data.local.entity.AisleGuideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AisleGuideDao {
    @Query("SELECT * FROM aisle_guide ORDER BY normalizedAisle, id") fun observe(): Flow<List<AisleGuideEntity>>
    @Query("SELECT * FROM aisle_guide WHERE id=:id") suspend fun get(id: Long): AisleGuideEntity?
    @Query("SELECT * FROM aisle_guide WHERE normalizedAisle=:aisle") suspend fun named(aisle: String): AisleGuideEntity?
    @Insert suspend fun insert(entry: AisleGuideEntity): Long
    @Update suspend fun update(entry: AisleGuideEntity)
    @Query("DELETE FROM aisle_guide WHERE id=:id") suspend fun delete(id: Long)
}
