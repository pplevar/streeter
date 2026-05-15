package com.streeter.di

import androidx.room.withTransaction
import com.streeter.data.engine.GraphHopperEngine
import com.streeter.data.engine.TransactionRunner
import com.streeter.data.local.StreeterDatabase
import com.streeter.domain.engine.RoutingEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds @Singleton
    abstract fun bindRoutingEngine(impl: GraphHopperEngine): RoutingEngine

    companion object {
        @Provides @Singleton
        fun provideTransactionRunner(database: StreeterDatabase): TransactionRunner =
            TransactionRunner { block -> database.withTransaction(block) }
    }
}
