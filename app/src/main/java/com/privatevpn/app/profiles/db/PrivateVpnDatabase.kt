package com.privatevpn.app.profiles.db

import android.content.Context
import androidx.room.Room
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, SubscriptionSourceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PrivateVpnDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionSourceDao(): SubscriptionSourceDao

    companion object {
        const val DB_NAME: String = "privatevpn.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN parentSubscriptionId TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN sourceOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subscription_sources (
                        id TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        autoUpdateEnabled INTEGER NOT NULL,
                        updateIntervalMinutes INTEGER NOT NULL,
                        lastUpdatedAtMs INTEGER,
                        lastSuccessAtMs INTEGER,
                        lastError TEXT,
                        isCollapsed INTEGER NOT NULL,
                        profileCount INTEGER NOT NULL,
                        etag TEXT,
                        lastModified TEXT,
                        metadata TEXT,
                        childProfileIdsCsv TEXT NOT NULL,
                        lastSelectedProfileId TEXT,
                        syncStatus TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profiles_parentSubscriptionId ON profiles(parentSubscriptionId)")
            }
        }

        fun build(context: Context): PrivateVpnDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PrivateVpnDatabase::class.java,
                DB_NAME
            ).enableMultiInstanceInvalidation()
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
