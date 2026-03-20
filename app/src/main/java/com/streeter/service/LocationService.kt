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
import com.google.android.gms.location.*
import com.streeter.MainActivity
import com.streeter.R
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.WalkRepository
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
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "streeter_recording"
        private const val FLUSH_BATCH_SIZE = 50
    }

    @Inject lateinit var walkRepository: WalkRepository
    @Inject lateinit var gpsPointRepository: GpsPointRepository
    @Inject lateinit var pendingMatchJobRepository: PendingMatchJobRepository

    private val binder = LocalBinder()
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentWalkId: Long = -1L
    private var lastKeptPoint: GpsPoint? = null
    private val pendingPoints = mutableListOf<GpsPoint>()
    private var maxSpeedKmh: Float = 50f
    private var sampleIntervalSeconds: Int = 20

    private val _currentPoints = MutableStateFlow<List<GpsPoint>>(emptyList())
    val currentPoints: StateFlow<List<GpsPoint>> = _currentPoints.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_WALK -> startWalk()
            ACTION_STOP_WALK -> stopWalk()
        }
        return START_STICKY
    }

    private fun startWalk() {
        if (_isRecording.value) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val walkId = walkRepository.insertWalk(
                Walk(
                    title = null,
                    date = now,
                    durationMs = 0L,
                    distanceM = 0.0,
                    status = WalkStatus.RECORDING,
                    source = WalkSource.RECORDED,
                    createdAt = now,
                    updatedAt = now
                )
            )
            currentWalkId = walkId
            _isRecording.value = true
            startLocationUpdates()
            Timber.d("Walk started, id=$walkId")
        }
    }

    private fun stopWalk() {
        if (!_isRecording.value) return
        stopLocationUpdates()
        lifecycleScope.launch {
            flushPoints()
            if (currentWalkId != -1L) {
                val walk = walkRepository.getWalkById(currentWalkId)
                walk?.let {
                    val now = System.currentTimeMillis()
                    walkRepository.updateWalk(
                        it.copy(
                            status = WalkStatus.PENDING_MATCH,
                            durationMs = now - it.date,
                            updatedAt = now
                        )
                    )
                }
                Timber.d("Walk stopped, id=$currentWalkId → PENDING_MATCH")
            }
            currentWalkId = -1L
            _isRecording.value = false
            lastKeptPoint = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startLocationUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            sampleIntervalSeconds * 1000L
        )
            .setMinUpdateIntervalMillis(sampleIntervalSeconds * 500L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val point = GpsPoint(
                        walkId = currentWalkId,
                        lat = location.latitude,
                        lng = location.longitude,
                        timestamp = location.time,
                        accuracyM = location.accuracy,
                        speedKmh = location.speed * 3.6f,
                        isFiltered = false
                    )
                    handleNewPoint(point)
                }
            }
        }

        try {
            fusedClient?.requestLocationUpdates(request, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            Timber.e("Location permission not granted")
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streeter:flush")
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }
}
