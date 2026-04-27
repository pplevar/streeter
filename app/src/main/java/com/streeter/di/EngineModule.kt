package com.streeter.di

import com.streeter.data.engine.GraphHopperEngine
import com.streeter.domain.engine.RoutingEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds @Singleton
    abstract fun bindRoutingEngine(impl: GraphHopperEngine): RoutingEngine
}
