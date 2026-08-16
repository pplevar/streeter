package com.streeter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.streeter.MainActivity
import com.streeter.R
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.recording.GpsObservation
import com.streeter.domain.recording.RecordingSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * The foreground half of recording a walk: the notification, the location callbacks and the
 * service lifecycle. Every decision about the walk itself — batching, outlier anchoring,
 * duration, ending — belongs to [RecordingSession]; this class only drives it.
 */
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
    }

    @Inject lateinit var routingEngine: RoutingEngine

    @Inject lateinit var session: RecordingSession

    private val binder = LocalBinder()
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var sampleIntervalSeconds: Int = 20

    fun getCurrentWalkId(): Long = session.walkId

    val currentPoints: StateFlow<List<GpsPoint>> get() = session.points

    val isPaused: StateFlow<Boolean> get() = session.isPaused

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        session.onFlushFailure = { e -> Timber.e(e, "GPS batch write failed; points stay buffered for the next flush") }
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
                val walkId = intent.getLongExtra(EXTRA_WALK_ID, RecordingSession.NO_WALK)
                if (walkId != RecordingSession.NO_WALK) resumeWalk(walkId)
            }
        }
        return START_STICKY
    }

    private fun startWalk() {
        if (session.isRecording.value) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(paused = false))

        lifecycleScope.launch {
            val walkId = session.start()
            startLocationUpdates()
            Timber.d("Walk started, id=%d", walkId)
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
        if (!session.isRecording.value || session.isPaused.value) return
        stopLocationUpdates()
        updateNotification()

        lifecycleScope.launch {
            session.pause()
            Timber.d("Walk paused, id=%d", session.walkId)
        }
    }

    private fun resumeWalk(walkId: Long) {
        if (session.isRecording.value && !session.isPaused.value) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(paused = false))

        // The session claims the walk before it suspends, so location updates can start right
        // away — the fixes queue behind the resume write rather than being dropped.
        lifecycleScope.launch { session.resume(walkId) }
        startLocationUpdates()
        Timber.d("Walk resumed, id=%d", walkId)
    }

    private fun stopWalk() {
        if (session.walkId == RecordingSession.NO_WALK) return
        if (!session.isPaused.value) stopLocationUpdates()

        lifecycleScope.launch {
            val walkId = session.walkId
            session.stop()
            Timber.w("Walk stopped: id=%d → PENDING_MATCH, sync + calculation enqueued", walkId)
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
                        val observation =
                            GpsObservation(
                                lat = location.latitude,
                                lng = location.longitude,
                                timestamp = location.time,
                                accuracyM = location.accuracy,
                                speedKmh = location.speed * 3.6f,
                            )
                        // Serialized by the session's lock, so fixes are recorded in arrival order.
                        lifecycleScope.launch { session.record(observation) }
                    }
                }
            }

        try {
            fusedClient?.requestLocationUpdates(request, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission not granted")
        }
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
