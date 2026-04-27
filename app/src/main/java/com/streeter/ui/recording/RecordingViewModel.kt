package com.streeter.ui.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.repository.WalkRepository
import com.streeter.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.*

@HiltViewModel
class RecordingViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val walkRepository: WalkRepository,
    ) : ViewModel() {
        private var locationService: LocationService? = null
        private var isBound = false

        private val _gpsPoints = MutableStateFlow<List<GpsPoint>>(emptyList())
        val gpsPoints: StateFlow<List<GpsPoint>> = _gpsPoints.asStateFlow()

        private val walkStartMs = MutableStateFlow(0L)
        private val _elapsedMs = MutableStateFlow(0L)
        val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

        private val _isWalkStarted = MutableStateFlow(false)
        val isWalkStarted: StateFlow<Boolean> = _isWalkStarted.asStateFlow()

        val distanceM: StateFlow<Double> =
            _gpsPoints
                .map { points ->
                    points.filter { !it.isFiltered }
                        .zipWithNext()
                        .sumOf { (a, b) -> haversineM(a.lat, a.lng, b.lat, b.lng) }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

        private val serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    val service = (binder as LocationService.LocalBinder).getService()
                    locationService = service
                    viewModelScope.launch {
                        service.currentPoints.collect { points ->
                            _gpsPoints.value = points
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    locationService = null
                    isBound = false
                }
            }

        init {
            viewModelScope.launch {
                val activeWalk = walkRepository.getActiveRecordingWalk()
                if (activeWalk != null) {
                    walkStartMs.value = activeWalk.date
                    _isWalkStarted.value = true
                    val serviceIntent =
                        Intent(context, LocationService::class.java).apply {
                            action = LocationService.ACTION_RESUME_WALK
                            putExtra(LocationService.EXTRA_WALK_ID, activeWalk.id)
                        }
                    context.startForegroundService(serviceIntent)
                    bindToService()
                }
            }

            viewModelScope.launch {
                while (true) {
                    delay(1000L)
                    val start = walkStartMs.value
                    if (start > 0) _elapsedMs.value = System.currentTimeMillis() - start
                }
            }
        }

        fun startWalk() {
            if (_isWalkStarted.value) return
            walkStartMs.value = System.currentTimeMillis()
            _isWalkStarted.value = true
            val serviceIntent =
                Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_START_WALK
                }
            context.startForegroundService(serviceIntent)
            bindToService()
        }

        fun stopWalk(): Long {
            val walkId = locationService?.getCurrentWalkId() ?: -1L
            val stopIntent =
                Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_STOP_WALK
                }
            context.startService(stopIntent)
            unbind()
            return walkId
        }

        private fun bindToService() {
            val bindIntent = Intent(context, LocationService::class.java)
            isBound = context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        private fun unbind() {
            if (isBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (_: Exception) {
                }
                isBound = false
            }
        }

        override fun onCleared() {
            unbind()
            super.onCleared()
        }

        private fun haversineM(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val earthRadiusM = 6371000.0
            val phi1 = lat1.toRadians()
            val phi2 = lat2.toRadians()
            val dphi = (lat2 - lat1).toRadians()
            val dlambda = (lon2 - lon1).toRadians()
            val a = sin(dphi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dlambda / 2).pow(2)
            return earthRadiusM * 2 * asin(sqrt(a))
        }

        private fun Double.toRadians() = this * PI / 180.0
    }
