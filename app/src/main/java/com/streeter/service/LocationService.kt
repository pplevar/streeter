package com.streeter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.google.android.gms.location.*
import com.streeter.MainActivity
import com.streeter.R
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.PendingMatchJob
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.work.MapMatchingWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class LocationService : LifecycleService() {
    companion object {
        const val ACTION_START_WALK = "com.streeter.ACTION_START_WALK"
        const val ACTION_STOP_WALK = "com.streeter.ACTION_STOP_WALK"
        const val ACTION_RESUME_WALK = "com.streeter.ACTION_RESUME_WALK"
        const val ACTION_PAUSE_WALK = "com.streeter.ACTION_PAUSE_WALK"
        const val EXTRA_WALK_ID = "com.streeter.EXTRA_WALK_ID"
        private const val NOTIFICATION_ID = 1001

        @Volatile var isRunning = false
        private const val CHANNEL_ID = "streeter_recording"
        private const val FLUSH_BATCH_SIZE = 50
    }

    @Inject lateinit var walkRepository: WalkRepository

    @Inject lateinit var gpsPointRepository: GpsPointRepository

    @Inject lateinit var pendingMatchJobRepository: PendingMatchJobRepository

    @Inject lateinit var routingEngine: RoutingEngine

    private val workManager by lazy { WorkManager.getInstance(applicationContext) }

    private val binder = LocalBinder()
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentWalkId: Long = -1L

    fun getCurrentWalkId(): Long = currentWalkId

    private var lastKeptPoint: GpsPoint? = null
    private val pendingPoints = mutableListOf<GpsPoint>()
    private var maxSpeedKmh: Float = 50f
    private var sampleIntervalSeconds: Int = 20

    private val _currentPoints = MutableStateFlow<List<GpsPoint>>(emptyList())
    val currentPoints: StateFlow<List<GpsPoint>> = _currentPoints.asStateFlow()

    private val isRecording = MutableStateFlow(false)

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_WALK -> startWalk()
            ACTION_STOP_WALK -> stopWalk()
            ACTION_PAUSE_WALK -> pauseWalk()
            ACTION_RESUME_WALK -> {
                val walkId = intent.getLongExtra(EXTRA_WALK_ID, -1L)
                if (walkId != -1L) resumeWalk(walkId)
            }
        }
        return START_STICKY
    }

    private fun startWalk() {
        if (isRecording.value) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(paused = false))

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val walkId =
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
            currentWalkId = walkId
            isRecording.value = true
            _isPaused.value = false
            startLocationUpdates()
            Timber.d("Walk started, id=$walkId")
        }
        lifecycleScope.launch {
            try {
                if (!routingEngine.isReady()) {
                    Timber.d("Pre-warming routing engine from LocationService.startWalk")
                    routingEngine.initialize()
                }
            } catch (e: Exception) {
                Timber.w(e, "LocationService: routing engine pre-warm failed")
            }
        }
    }

    private fun pauseWalk() {
        if (!isRecording.value || _isPaused.value) return
        stopLocationUpdates()
        _isPaused.value = true
        updateNotification()

        lifecycleScope.launch {
            flushPoints()
            if (currentWalkId != -1L) {
                val walk = walkRepository.getWalkById(currentWalkId)
                walk?.let {
                    val now = System.currentTimeMillis()
                    val segmentMs = if (it.lastResumedAt != null) now - it.lastResumedAt else 0L
                    walkRepository.updateWalk(
                        it.copy(
                            durationMs = it.durationMs + segmentMs,
                            lastResumedAt = null,
                            isPaused = true,
                            updatedAt = now,
                        ),
                    )
                }
            }
            Timber.d("Walk paused, id=$currentWalkId")
        }
    }

    private fun resumeWalk(walkId: Long) {
        if (isRecording.value && !_isPaused.value) return
        createNotificationChannel()
        currentWalkId = walkId
        isRecording.value = true
        _isPaused.value = false
        startForeground(NOTIFICATION_ID, buildNotification(paused = false))

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val walk = walkRepository.getWalkById(walkId)
            walk?.let {
                walkRepository.updateWalk(
                    it.copy(
                        lastResumedAt = now,
                        isPaused = false,
                        updatedAt = now,
                    ),
                )
            }
        }
        startLocationUpdates()
        Timber.d("Walk resumed, id=$walkId")
    }

    private fun stopWalk() {
        if (currentWalkId == -1L) return
        if (!_isPaused.value) stopLocationUpdates()

        lifecycleScope.launch {
            flushPoints()
            val walkId = currentWalkId
            val walk = walkRepository.getWalkById(walkId)
            walk?.let {
                val now = System.currentTimeMillis()
                val finalDuration =
                    if (it.lastResumedAt != null) {
                        it.durationMs + (now - it.lastResumedAt)
                    } else {
                        it.durationMs
                    }
                walkRepository.updateWalk(
                    it.copy(
                        status = WalkStatus.PENDING_MATCH,
                        durationMs = finalDuration,
                        lastResumedAt = null,
                        isPaused = false,
                        updatedAt = now,
                    ),
                )
            }
            pendingMatchJobRepository.enqueue(
                PendingMatchJob(
                    walkId = walkId,
                    queuedAt = System.currentTimeMillis(),
                    status = JobStatus.QUEUED,
                    retryCount = 0,
                    lastError = null,
                ),
            )
            workManager.enqueueUniqueWork(
                "match_$walkId",
                ExistingWorkPolicy.KEEP,
                MapMatchingWorker.buildRequest(walkId),
            )
            Timber.w("Walk stopped: id=%d → PENDING_MATCH, worker enqueued", walkId)
            currentWalkId = -1L
            isRecording.value = false
            _isPaused.value = false
            lastKeptPoint = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startLocationUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val request =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                sampleIntervalSeconds * 1000L,
            )
                .setMinUpdateIntervalMillis(sampleIntervalSeconds * 500L)
                .build()

        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        val point =
                            GpsPoint(
                                walkId = currentWalkId,
                                lat = location.latitude,
                                lng = location.longitude,
                                timestamp = location.time,
                                accuracyM = location.accuracy,
                                speedKmh = location.speed * 3.6f,
                                isFiltered = false,
                            )
                        handleNewPoint(point)
                    }
                }
            }

        try {
            fusedClient?.requestLocationUpdates(request, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission not granted")
        }
    }

    private fun handleNewPoint(point: GpsPoint) {
        val prev = lastKeptPoint
        val filtered = if (prev != null) !GpsOutlierFilter.shouldKeep(prev, point, maxSpeedKmh) else false
        val finalPoint = point.copy(isFiltered = filtered)

        if (!filtered) lastKeptPoint = finalPoint

        pendingPoints.add(finalPoint)
        _currentPoints.value = _currentPoints.value + finalPoint

        if (pendingPoints.size >= FLUSH_BATCH_SIZE) {
            lifecycleScope.launch { flushPoints() }
        }
    }

    private suspend fun flushPoints() {
        if (pendingPoints.isEmpty()) return
        val toFlush = pendingPoints.toList()
        pendingPoints.clear()
        acquireWakeLock()
        try {
            gpsPointRepository.insertPoints(toFlush)
        } finally {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streeter:flush")
                .apply { acquire(5_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(paused = true))
    }

    private fun buildNotification(paused: Boolean): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val title = if (paused) getString(R.string.notification_paused_title) else getString(R.string.notification_recording_title)
        val text = if (paused) getString(R.string.notification_paused_text) else getString(R.string.notification_recording_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        stopLocationUpdates()
        super.onDestroy()
    }
}
