package com.imgai.app.data

import androidx.room.*

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories ORDER BY sortOrder")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): CategoryEntity?
}

@Dao
interface FaceClusterDao {
    @Insert
    suspend fun insert(cluster: FaceClusterEntity): Long

    @Update
    suspend fun update(cluster: FaceClusterEntity)

    @Query("SELECT * FROM face_clusters ORDER BY memberCount DESC")
    suspend fun getAll(): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters WHERE id = :id")
    suspend fun getById(id: Long): FaceClusterEntity?

    @Query("UPDATE face_clusters SET memberCount = :count WHERE id = :id")
    suspend fun updateMemberCount(id: Long, count: Int)

    @Query("UPDATE face_clusters SET archived = 1, archivePath = :path WHERE id = :id")
    suspend fun archive(id: Long, path: String)

    @Query("SELECT * FROM face_clusters WHERE archived = 1 ORDER BY label")
    suspend fun getArchived(): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters WHERE archived = 0 ORDER BY memberCount DESC")
    suspend fun getUnarchived(): List<FaceClusterEntity>

    @Query("SELECT COUNT(*) FROM face_clusters WHERE archived = 1")
    suspend fun countArchived(): Int

    @Query("DELETE FROM face_clusters")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM face_clusters")
    suspend fun count(): Int
}

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE status = 'normal' ORDER BY dateTaken DESC")
    suspend fun getAll(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE categoryId = :categoryId ORDER BY dateTaken DESC")
    suspend fun getByCategory(categoryId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE clusterId = :clusterId ORDER BY dateTaken DESC")
    suspend fun getByCluster(clusterId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE status = 'pending_review' ORDER BY dateTaken DESC")
    suspend fun getPendingReview(): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE categoryId IS NOT NULL")
    suspend fun countCategorized(): Int

    @Query("SELECT uri FROM photos")
    suspend fun getAllUris(): List<String>

    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}

@Dao
interface FaceEmbeddingDao {
    @Insert
    suspend fun insert(entity: FaceEmbeddingEntity): Long

    @Query("SELECT * FROM face_embeddings")
    suspend fun getAll(): List<FaceEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM face_embeddings")
    suspend fun count(): Int

    @Query("UPDATE face_embeddings SET clusterId = :clusterId WHERE id IN (:ids)")
    suspend fun updateClusterIds(ids: List<Long>, clusterId: Long)

    @Query("SELECT * FROM face_embeddings WHERE clusterId = :clusterId")
    suspend fun getByCluster(clusterId: Long): List<FaceEmbeddingEntity>

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

    @Query("DELETE FROM duplicate_groups")
    suspend fun deleteAll()
}
