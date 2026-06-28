package com.imgai.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CategoryEntity::class,
        FaceClusterEntity::class,
        PhotoEntity::class,
        FaceEmbeddingEntity::class,
        DuplicateGroupEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun faceClusterDao(): FaceClusterDao
    abstract fun photoDao(): PhotoDao
    abstract fun faceEmbeddingDao(): FaceEmbeddingDao
    abstract fun duplicateGroupDao(): DuplicateGroupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "imgai.db"
                ).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                    // 预填充分类
                    preloadCategories(it)
                }
            }
        }

        private val DEFAULT_CATEGORIES = listOf(
            CategoryEntity(name = "人物", sortOrder = 0),
            CategoryEntity(name = "风景", sortOrder = 1),
            CategoryEntity(name = "美食", sortOrder = 2),
            CategoryEntity(name = "文档", sortOrder = 3),
            CategoryEntity(name = "其他", sortOrder = 4)
        )

        fun preloadCategories(db: AppDatabase) {
            kotlinx.coroutines.runBlocking {
                if (db.categoryDao().getAll().isEmpty()) {
                    db.categoryDao().insertAll(DEFAULT_CATEGORIES)
                }
            }
        }
    }
}
