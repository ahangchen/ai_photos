package com.imgai.app.data

import androidx.room.*
import androidx.room.TypeConverter

// ── Type Converters ──
class Converters {
    @TypeConverter
    fun floatArrayToString(value: FloatArray): String = value.joinToString(",")

    @TypeConverter
    fun stringToFloatArray(value: String): FloatArray =
        if (value.isBlank()) FloatArray(0) else value.split(",").map { it.toFloat() }.toFloatArray()
}

// ── Entities ──

@Entity(tableName = "processed_images")
data class ProcessedImageEntity(
    @PrimaryKey val uri: String,
    val processedAt: Long,
    val faceCount: Int,
    val dateTaken: Long
)

@Entity(tableName = "face_embeddings")
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    @ColumnInfo(name = "embedding") val embeddingRaw: String, // 逗号分隔的 float
    val clusterId: Int = -1, // -1 = 未聚类/噪声
    val faceRect: String,    // "left,top,right,bottom"
    val qualityScore: Float = 0f
)

@Entity(tableName = "duplicate_groups")
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bestUri: String,
    val pendingUris: String, // 逗号分隔
    val createdAt: Long
)
