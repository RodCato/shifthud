package com.shifthud.data.local.entity

import androidx.room.*
import com.shifthud.domain.model.*
import java.time.Instant

@Entity(tableName="aisle_guide", indices=[Index(value=["normalizedAisle"],unique=true)])
data class AisleGuideEntity(
    @PrimaryKey(autoGenerate=true) val id: Long = 0,
    val aisle: String,
    val normalizedAisle: String,
    val categories: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun model() = AisleGuideEntry(id,aisle,categories,Instant.ofEpochMilli(createdAt),Instant.ofEpochMilli(updatedAt))
}
fun AisleGuideEntry.entity() = AisleGuideEntity(id,aisle,normalizeQuickFind(aisle),categories,createdAt.toEpochMilli(),updatedAt.toEpochMilli())
