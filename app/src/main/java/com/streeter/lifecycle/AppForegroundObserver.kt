package com.streeter.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.streeter.work.PullSyncWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppForegroundObserver
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            workManager.enqueueUniqueWork(
                PullSyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                PullSyncWorker.buildOneTimeRequest(),
            )
        }
    }
