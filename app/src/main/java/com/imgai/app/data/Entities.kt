package com.imgai.app.data

import androidx.room.*

class Converters {
    @TypeConverter
    fun floatArrayToString(value: FloatArray): String = value.joinToString(",")

    @TypeConverter
    fun stringToFloatArray(value: String): FloatArray =
        if (value.isBlank()) FloatArray(0) else value.split(",").map { it.toFloat() }.toFloatArray()
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val sortOrder: Int = 0
)

@Entity(tableName = "face_clusters")
data class FaceClusterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val representativeUri: String? = null,
    val memberCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val archivePath: String? = null
)

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val uri: String,
    val dateTaken: Long = 0,
    val categoryId: Long? = null,
    val clusterId: Long? = null,
    val qualityScore: Float = 0f,
    val phash: Long = 0L,
    val processedAt: Long = System.currentTimeMillis(),
    val status: String = "normal"
)

@Entity(tableName = "face_embeddings")
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    val embeddingRaw: String,
    val clusterId: Long? = null,
    val faceRect: String = ""
)

@Entity(tableName = "duplicate_groups")
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bestUri: String,
    val pendingUris: String,
    val createdAt: Long = System.currentTimeMillis()
)
