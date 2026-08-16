package com.streeter.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.streeter.domain.work.WalkWorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppForegroundObserver
    @Inject
    constructor(
        private val walkWorkScheduler: WalkWorkScheduler,
    ) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            walkWorkScheduler.enqueuePull()
        }
    }
