package com.streamflixrevanced.streamflix.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.streamflixrevanced.streamflix.database.dao.ProfileDao
import com.streamflixrevanced.streamflix.models.Profile

@Database(
    entities = [Profile::class],
    version = 2,
    exportSchema = false,
)
abstract class ProfileDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var INSTANCE: ProfileDatabase? = null

        fun getInstance(context: Context): ProfileDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context = context.applicationContext,
                    klass = ProfileDatabase::class.java,
                    name = "profiles.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE profiles_new (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        avatarPath TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO profiles_new (id, name, avatarPath, createdAt, position)
                    SELECT id, name, 'blank_picture.jpg', createdAt, position
                    FROM profiles
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE profiles")
                db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
            }
        }
    }
}
