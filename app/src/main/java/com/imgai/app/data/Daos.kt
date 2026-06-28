package com.imgai.app.data

import androidx.room.*

@Dao
interface ProcessedImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProcessedImageEntity)

    @Query("SELECT uri FROM processed_images")
    suspend fun getAllUris(): List<String>

    @Query("SELECT COUNT(*) FROM processed_images")
    suspend fun count(): Int

    @Query("DELETE FROM processed_images")
    suspend fun deleteAll()
}

@Dao
interface FaceEmbeddingDao {
    @Insert
    suspend fun insert(entity: FaceEmbeddingEntity): Long

    @Insert
    suspend fun insertAll(entities: List<FaceEmbeddingEntity>)

    @Query("SELECT * FROM face_embeddings")
    suspend fun getAll(): List<FaceEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM face_embeddings")
    suspend fun count(): Int

    @Query("UPDATE face_embeddings SET clusterId = :clusterId WHERE id IN (:ids)")
    suspend fun updateClusterIds(ids: List<Long>, clusterId: Int)

    @Query("SELECT * FROM face_embeddings WHERE clusterId = :clusterId")
    suspend fun getByCluster(clusterId: Int): List<FaceEmbeddingEntity>

    @Query("SELECT DISTINCT clusterId FROM face_embeddings WHERE clusterId >= 0")
    suspend fun getClusterIds(): List<Int>

    @Query("SELECT COUNT(*) FROM face_embeddings WHERE clusterId >= 0")
    suspend fun getClusteredCount(): Int

    @Query("UPDATE face_embeddings SET clusterId = -1")
    suspend fun resetClusters()

    @Query("DELETE FROM face_embeddings")
    suspend fun deleteAll()
}

@Dao
interface DuplicateGroupDao {
    @Insert
    suspend fun insert(entity: DuplicateGroupEntity): Long

    @Query("SELECT COUNT(*) FROM duplicate_groups")
    suspend fun count(): Int

    @Query("SELECT * FROM duplicate_groups ORDER BY createdAt DESC")
    suspend fun getAll(): List<DuplicateGroupEntity>
}
