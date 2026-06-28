package com.imgai.app.data

import androidx.room.*

// ── Type Converters ──
class Converters {
    @TypeConverter
    fun floatArrayToString(value: FloatArray): String = value.joinToString(",")

    @TypeConverter
    fun stringToFloatArray(value: String): FloatArray =
        if (value.isBlank()) FloatArray(0) else value.split(",").map { it.toFloat() }.toFloatArray()
}

// ── Entities ──

/** 大类分类：人物/风景/美食/文档/其他 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,           // 人物/风景/美食/文档/其他
    val icon: String? = null,
    val sortOrder: Int = 0
)

/** 人脸聚类（人物子类） */
@Entity(tableName = "face_clusters")
data class FaceClusterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,          // Person_1, Person_2...
    val representativeUri: String? = null,
    val memberCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 照片记录 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val uri: String,
    val dateTaken: Long = 0,
    val categoryId: Long? = null,       // 大类 ID
    val clusterId: Long? = null,        // 人脸聚类 ID（仅人物类）
    val qualityScore: Float = 0f,
    val phash: Long = 0L,
    val processedAt: Long = System.currentTimeMillis(),
    val status: String = "normal"       // normal/pending_review/best
)

/** 人脸特征 */
@Entity(tableName = "face_embeddings")
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageUri: String,
    val embeddingRaw: String,
    val clusterId: Long? = null,     // 关联 face_clusters.id
    val faceRect: String = ""
)

/** 重复组 */
@Entity(tableName = "duplicate_groups")
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bestUri: String,
    val pendingUris: String,
    val createdAt: Long = System.currentTimeMillis()
)
