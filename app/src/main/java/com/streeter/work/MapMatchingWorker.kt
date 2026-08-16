package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.streeter.domain.calculation.CalculationDisposition
import com.streeter.domain.calculation.CalculationDriver
import com.streeter.domain.calculation.CalculationSession
import com.streeter.domain.work.WorkRetryPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Runs Calculation for one walk. WorkManager plumbing only — every decision belongs to
 * [CalculationSession] (issue #62): this class publishes progress and maps the session's
 * disposition onto a [Result].
 */
@HiltWorker
class MapMatchingWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted workerParams: WorkerParameters,
        private val session: CalculationSession,
    ) : CoroutineWorker(context, workerParams) {
        companion object {
            const val KEY_WALK_ID = "walk_id"
            const val KEY_PROGRESS = "progress"
            const val KEY_STEP = "step"

            /** Progress the heartbeat starts from and creeps towards while matching runs. */
            private const val MATCHING_PROGRESS_FROM = 20
            private const val MATCHING_PROGRESS_TO = 48

            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<MapMatchingWorker>()
                    .setInputData(workDataOf(KEY_WALK_ID to walkId))
                    .setConstraints(Constraints.NONE)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRetryPolicy.BACKOFF_SECONDS,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                    .build()
        }

        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                val walkId = inputData.getLong(KEY_WALK_ID, -1L)
                if (walkId == -1L) return@withContext Result.failure()

                Timber.i("MapMatchingWorker starting for walk=$walkId")
                val disposition = session.calculate(walkId, runAttemptCount, ProgressDriver())
                Timber.i("MapMatchingWorker finished for walk=$walkId: $disposition")

                when (disposition) {
                    CalculationDisposition.SUCCESS -> Result.success()
                    CalculationDisposition.RETRY -> Result.retry()
                    CalculationDisposition.FAILURE -> Result.failure()
                }
            }

        /**
         * Publishes the session's progress to WorkManager, and keeps it moving during map
         * matching — a single call that can run for minutes with nothing to report from inside.
         */
        private inner class ProgressDriver : CalculationDriver {
            override suspend fun report(
                percent: Int,
                step: String,
            ) = setProgress(workDataOf(KEY_PROGRESS to percent, KEY_STEP to step))

            override val isStopped: Boolean get() = this@MapMatchingWorker.isStopped

            override fun note(
                message: String,
                cause: Throwable?,
            ) {
                if (cause == null) Timber.w(message) else Timber.e(cause, message)
            }

            override suspend fun <T> whileMatching(block: suspend () -> T): T =
                coroutineScope {
                    val heartbeat =
                        launch {
                            var pct = MATCHING_PROGRESS_FROM
                            while (pct < MATCHING_PROGRESS_TO) {
                                delay(3_000L)
                                pct = (pct + 4).coerceAtMost(MATCHING_PROGRESS_TO)
                                report(pct, "Matching route to streets…")
                            }
                        }
                    try {
                        block()
                    } finally {
                        heartbeat.cancel()
                    }
                }
        }
    }
