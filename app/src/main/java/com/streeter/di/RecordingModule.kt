package com.streeter.di

import com.streeter.domain.recording.WakeGuard
import com.streeter.domain.time.Clock
import com.streeter.domain.time.SystemClock
import com.streeter.service.PowerManagerWakeGuard
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Platform adapters the recording session depends on: the wall clock and the wake lock. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingModule {
    @Binds @Singleton
    abstract fun bindClock(impl: SystemClock): Clock

    @Binds @Singleton
    abstract fun bindWakeGuard(impl: PowerManagerWakeGuard): WakeGuard
}
