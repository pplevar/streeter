package com.streeter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.streeter.data.local.dao.*
import com.streeter.data.local.entity.*

@Database(
    entities = [
        WalkEntity::class,
        GpsPointEntity::class,
        StreetEntity::class,
        StreetSectionEntity::class,
        WalkStreetEntity::class,
        WalkSectionEntity::class,
        RouteSegmentEntity::class,
        EditOperationEntity::class,
        PendingMatchJobEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class StreeterDatabase : RoomDatabase() {
    abstract fun walkDao(): WalkDao

    abstract fun gpsPointDao(): GpsPointDao

    abstract fun streetDao(): StreetDao

    abstract fun routeSegmentDao(): RouteSegmentDao

    abstract fun editOperationDao(): EditOperationDao

    abstract fun pendingMatchJobDao(): PendingMatchJobDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE walk_streets ADD COLUMN walkedLengthM REAL NOT NULL DEFAULT 0")
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE walks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING_SYNC'")
                    db.execSQL("ALTER TABLE walks ADD COLUMN serverWalkId INTEGER")
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE walks ADD COLUMN lastPullSyncAt INTEGER")
                }
            }
    }
}
