package com.google.ar.core.codelabs.hellogeospatial.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AnchorDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(anchor: Anchor)

    @Update
    suspend fun update(anchor: Anchor)

    @Delete
    suspend fun delete(anchor: Anchor)

    @Query("SELECT * from anchors WHERE id = :id")
    fun getAnchor(id: Int): Flow<Anchor>

    @Query("SELECT * from anchors")
    fun getAllAnchors(): Flow<List<Anchor>>
}