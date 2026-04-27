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
    version = 2,
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
    }
}
