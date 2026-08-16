package com.streeter.domain.recording

import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.PendingMatchJob
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.time.Clock
import com.streeter.domain.work.WalkRecalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * What it means to record, pause and end a Recorded Walk (issue #61).
 *
 * The foreground service owns the Android side — the notification, the location callbacks, the
 * process lifecycle — and drives this for every decision:
 *
 * - **Batching.** Observations are buffered and written in batches of [FLUSH_BATCH_SIZE]. A
 *   batch leaves the buffer only once the write has landed, so a failed write costs a retry,
 *   never the points.
 * - **Outlier anchoring.** Each observation is compared against the last *kept* point. A
 *   rejected point never becomes the anchor, so one spike cannot drag the rest of the trace
 *   out with it. Outlier Points are still stored — they are excluded at the read seam.
 * - **Duration.** Accumulated segment by segment across pause/resume, off an injectable [Clock].
 * - **Ending.** The final duration is made durable first, then the walk goes through
 *   [WalkRecalculator], which owns the PENDING_MATCH transition and enqueues the work.
 *
 * Every entry point takes the same [mutex], so an observation arriving mid-flush queues behind
 * it rather than racing the buffer.
 */
class RecordingSession
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val gpsPointRepository: GpsPointRepository,
        private val pendingMatchJobRepository: PendingMatchJobRepository,
        private val walkRecalculator: WalkRecalculator,
        private val clock: Clock,
        private val wakeGuard: WakeGuard,
    ) {
        companion object {
            /** Points are written to the database in batches of this size. */
            const val FLUSH_BATCH_SIZE = 50

            /** Implied speed above which an observation is an Outlier Point. */
            const val DEFAULT_MAX_SPEED_KMH = 50f

            /** No walk in progress. */
            const val NO_WALK = -1L
        }

        private val mutex = Mutex()
        private val pendingPoints = mutableListOf<GpsPoint>()
        private var lastKeptPoint: GpsPoint? = null

        var maxSpeedKmh: Float = DEFAULT_MAX_SPEED_KMH

        /**
         * Called when a batch write fails and the points stay buffered. Logging is the driver's
         * job — the session keeps the points and carries on.
         */
        var onFlushFailure: (Throwable) -> Unit = {}

        /** The walk being recorded, or [NO_WALK] between walks. */
        @Volatile
        var walkId: Long = NO_WALK
            private set

        private val _points = MutableStateFlow<List<GpsPoint>>(emptyList())

        /** The walk's points so far, Outlier Points included and marked. */
        val points: StateFlow<List<GpsPoint>> = _points.asStateFlow()

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        /**
         * Begin a Recorded Walk and return its id. A walk already in progress is returned
         * unchanged, so a repeated start intent cannot open a second recording.
         */
        suspend fun start(): Long =
            mutex.withLock {
                if (_isRecording.value) return@withLock walkId
                val now = clock.nowMillis()
                val id =
                    walkRepository.insertWalk(
                        Walk(
                            title = null,
                            date = now,
                            durationMs = 0L,
                            distanceM = 0.0,
                            status = WalkStatus.RECORDING,
                            source = WalkSource.RECORDED,
                            createdAt = now,
                            updatedAt = now,
                            lastResumedAt = now,
                            isPaused = false,
                        ),
                    )
                walkId = id
                lastKeptPoint = null
                pendingPoints.clear()
                _points.value = emptyList()
                _isRecording.value = true
                _isPaused.value = false
                id
            }

        /**
         * Take one observation: decide whether it is an Outlier Point, buffer it, and flush the
         * batch once it is full. Ignored when no walk is in progress.
         */
        suspend fun record(observation: GpsObservation) =
            mutex.withLock {
                if (!_isRecording.value) return@withLock
                val anchor = lastKeptPoint
                val observed = observation.asPoint(walkId)
                val isOutlier = anchor != null && !GpsOutlierFilter.shouldKeep(anchor, observed, maxSpeedKmh)
                val point = observed.copy(isFiltered = isOutlier)

                // Anchor on the last kept point only: an Outlier Point must not become the
                // reference, or one spike rejects everything that follows it.
                if (!isOutlier) lastKeptPoint = point

                pendingPoints += point
                _points.value = _points.value + point

                if (pendingPoints.size >= FLUSH_BATCH_SIZE) flushLocked()
            }

        /**
         * Pause the walk: make the buffered points durable and bank the segment that just ended.
         */
        suspend fun pause() =
            mutex.withLock {
                if (!_isRecording.value || _isPaused.value) return@withLock
                flushLocked()
                walkRepository.getWalkById(walkId)?.let { walk ->
                    val now = clock.nowMillis()
                    walkRepository.updateWalk(
                        walk.copy(
                            durationMs = walk.durationMs + walk.runningSegmentMs(now),
                            lastResumedAt = null,
                            isPaused = true,
                            updatedAt = now,
                        ),
                    )
                }
                _isPaused.value = true
            }

        /**
         * Resume [resumedWalkId] — either the paused walk this session holds, or a recording
         * adopted after process death. Starts a fresh duration segment.
         */
        suspend fun resume(resumedWalkId: Long) =
            mutex.withLock {
                if (_isRecording.value && !_isPaused.value) return@withLock
                walkId = resumedWalkId
                val now = clock.nowMillis()
                walkRepository.getWalkById(resumedWalkId)?.let { walk ->
                    walkRepository.updateWalk(
                        walk.copy(
                            lastResumedAt = now,
                            isPaused = false,
                            updatedAt = now,
                        ),
                    )
                }
                _isRecording.value = true
                _isPaused.value = false
            }

        /**
         * End the walk: flush what is buffered, write the final duration, then hand the walk to
         * [WalkRecalculator]. The duration write must land first — the recalculator reloads the
         * walk, so anything written after it would be overwritten.
         */
        suspend fun stop() =
            mutex.withLock {
                if (walkId == NO_WALK) return@withLock
                val endedWalkId = walkId
                flushLocked()
                walkRepository.getWalkById(endedWalkId)?.let { walk ->
                    val now = clock.nowMillis()
                    walkRepository.updateWalk(
                        walk.copy(
                            durationMs = walk.durationMs + walk.runningSegmentMs(now),
                            lastResumedAt = null,
                            isPaused = false,
                            updatedAt = now,
                        ),
                    )
                }
                pendingMatchJobRepository.enqueue(
                    PendingMatchJob(
                        walkId = endedWalkId,
                        queuedAt = clock.nowMillis(),
                        status = JobStatus.QUEUED,
                        retryCount = 0,
                        lastError = null,
                    ),
                )
                // New walk: Sync (durability) and Calculation (coverage) run in parallel.
                walkRecalculator.traceChanged(endedWalkId, newWalk = true)

                walkId = NO_WALK
                lastKeptPoint = null
                _points.value = emptyList()
                _isRecording.value = false
                _isPaused.value = false
            }

        /**
         * Write the buffered points, dropping them from the buffer only once the write returns.
         * A failing write leaves the batch buffered for the next flush rather than losing it.
         * Call with [mutex] held.
         */
        private suspend fun flushLocked() {
            if (pendingPoints.isEmpty()) return
            val toFlush = pendingPoints.toList()
            try {
                wakeGuard.whileAwake { gpsPointRepository.insertPoints(toFlush) }
                pendingPoints.subList(0, toFlush.size).clear()
            } catch (e: Exception) {
                onFlushFailure(e)
            }
        }

        /** Milliseconds of the segment currently running; zero while paused. */
        private fun Walk.runningSegmentMs(now: Long): Long = lastResumedAt?.let { now - it } ?: 0L

        private fun GpsObservation.asPoint(walkId: Long) =
            GpsPoint(
                walkId = walkId,
                lat = lat,
                lng = lng,
                timestamp = timestamp,
                accuracyM = accuracyM,
                speedKmh = speedKmh,
                isFiltered = false,
            )
    }
