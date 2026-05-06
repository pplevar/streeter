package com.streeter.di

import android.content.Context
import androidx.room.Room
import com.streeter.data.local.StreeterDatabase
import com.streeter.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): StreeterDatabase =
        Room.databaseBuilder(context, StreeterDatabase::class.java, "streeter_database")
            .enableMultiInstanceInvalidation()
            .addMigrations(StreeterDatabase.MIGRATION_1_2, StreeterDatabase.MIGRATION_2_3)
            .build()

    @Provides fun provideWalkDao(db: StreeterDatabase): WalkDao = db.walkDao()

    @Provides fun provideGpsPointDao(db: StreeterDatabase): GpsPointDao = db.gpsPointDao()

    @Provides fun provideStreetDao(db: StreeterDatabase): StreetDao = db.streetDao()

    @Provides fun provideRouteSegmentDao(db: StreeterDatabase): RouteSegmentDao = db.routeSegmentDao()

    @Provides fun provideEditOperationDao(db: StreeterDatabase): EditOperationDao = db.editOperationDao()

    @Provides fun providePendingMatchJobDao(db: StreeterDatabase): PendingMatchJobDao = db.pendingMatchJobDao()
}
