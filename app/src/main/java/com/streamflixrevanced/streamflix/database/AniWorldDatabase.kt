package com.streamflixrevanced.streamflix.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.streamflixrevanced.streamflix.database.dao.TvShowDao
import com.streamflixrevanced.streamflix.models.TvShow

@Database(entities = [TvShow::class], version = 7, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AniWorldDatabase: RoomDatabase() {
    abstract fun tvShowDao(): TvShowDao

    companion object {
        @Volatile private var instance: AniWorldDatabase? = null

        fun getInstance(context: Context): AniWorldDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AniWorldDatabase::class.java,
                    "ani_world.db"
                )
                    .addMigrations(MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN imdbId TEXT")
            }
        }
    }
}
