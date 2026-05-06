package com.streeter.di

import com.streeter.data.repository.*
import com.streeter.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindWalkRepository(impl: WalkRepositoryImpl): WalkRepository

    @Binds @Singleton
    abstract fun bindGpsPointRepository(impl: GpsPointRepositoryImpl): GpsPointRepository

    @Binds @Singleton
    abstract fun bindStreetRepository(impl: StreetRepositoryImpl): StreetRepository

    @Binds @Singleton
    abstract fun bindRouteSegmentRepository(impl: RouteSegmentRepositoryImpl): RouteSegmentRepository

    @Binds @Singleton
    abstract fun bindEditOperationRepository(impl: EditOperationRepositoryImpl): EditOperationRepository

    @Binds @Singleton
    abstract fun bindPendingMatchJobRepository(impl: PendingMatchJobRepositoryImpl): PendingMatchJobRepository

    @Binds @Singleton
    abstract fun bindRemoteSyncRepository(impl: RemoteSyncRepositoryImpl): RemoteSyncRepository
}
