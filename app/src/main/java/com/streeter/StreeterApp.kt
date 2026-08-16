package com.streeter

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.streeter.domain.work.WalkWorkScheduler
import com.streeter.lifecycle.AppForegroundObserver
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class StreeterApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var walkWorkScheduler: WalkWorkScheduler

    @Inject
    lateinit var appForegroundObserver: AppForegroundObserver

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundObserver)
        walkWorkScheduler.schedulePeriodicPull()
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(
            priority: Int,
            tag: String?,
            message: String,
            t: Throwable?,
        ) {
            if (priority < android.util.Log.WARN) return
            // Strip coordinates from release logs — do not log raw lat/lng
            val sanitized = message.replace(Regex("-?\\d+\\.\\d{4,}"), "[coord]")
            android.util.Log.println(priority, tag ?: "Streeter", sanitized)
        }
    }
}
