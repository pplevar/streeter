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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walkRepository: WalkRepository
) : ViewModel() {

    private var locationService: LocationService? = null
    private var isBound = false

    private val _gpsPoints = MutableStateFlow<List<GpsPoint>>(emptyList())
    val gpsPoints: StateFlow<List<GpsPoint>> = _gpsPoints.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
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
            val serviceIntent = Intent(context, LocationService::class.java).apply {
                if (activeWalk != null) {
                    action = LocationService.ACTION_RESUME_WALK
                    putExtra(LocationService.EXTRA_WALK_ID, activeWalk.id)
                } else {
                    action = LocationService.ACTION_START_WALK
                }
            }
            context.startForegroundService(serviceIntent)

            val bindIntent = Intent(context, LocationService::class.java)
            isBound = context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun stopWalk(): Long {
        val walkId = locationService?.getCurrentWalkId() ?: -1L
        val stopIntent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP_WALK
        }
        context.startService(stopIntent)
        unbind()
        return walkId
    }

    private fun unbind() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }
    }

    override fun onCleared() {
        unbind()
        super.onCleared()
    }
}
