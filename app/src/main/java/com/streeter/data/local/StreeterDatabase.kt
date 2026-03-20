package com.streeter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true
)
abstract class StreeterDatabase : RoomDatabase() {
    abstract fun walkDao(): WalkDao
    abstract fun gpsPointDao(): GpsPointDao
    abstract fun streetDao(): StreetDao
    abstract fun routeSegmentDao(): RouteSegmentDao
    abstract fun editOperationDao(): EditOperationDao
    abstract fun pendingMatchJobDao(): PendingMatchJobDao
}
