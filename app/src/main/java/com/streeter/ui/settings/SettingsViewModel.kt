package com.streeter.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.engine.RoutingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SettingsUiState(
    val gpsIntervalSeconds: Int = 20,
    val maxSpeedKmh: Int = 50,
    val isRefreshingMapData: Boolean = false,
    val refreshMapDataError: String? = null,
    val showClearDataConfirm: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routingEngine: RoutingEngine
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("streeter_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            gpsIntervalSeconds = prefs.getInt(KEY_GPS_INTERVAL, 20),
            maxSpeedKmh = prefs.getInt(KEY_MAX_SPEED, 50)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setGpsInterval(seconds: Int) {
        prefs.edit().putInt(KEY_GPS_INTERVAL, seconds).apply()
        _uiState.update { it.copy(gpsIntervalSeconds = seconds) }
    }

    fun setMaxSpeed(kmh: Int) {
        prefs.edit().putInt(KEY_MAX_SPEED, kmh).apply()
        _uiState.update { it.copy(maxSpeedKmh = kmh) }
    }

    fun refreshMapData() {
        _uiState.update { it.copy(isRefreshingMapData = true, refreshMapDataError = null) }
        viewModelScope.launch {
            try {
                routingEngine.initialize()
                _uiState.update { it.copy(isRefreshingMapData = false) }
            } catch (e: Exception) {
                Timber.e(e, "Map data refresh failed")
                _uiState.update {
                    it.copy(
                        isRefreshingMapData = false,
                        refreshMapDataError = "Map data refresh failed. Please try again."
                    )
                }
            }
        }
    }

    fun showClearDataConfirm() {
        _uiState.update { it.copy(showClearDataConfirm = true) }
    }

    fun dismissClearDataConfirm() {
        _uiState.update { it.copy(showClearDataConfirm = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(refreshMapDataError = null) }
    }

    companion object {
        const val KEY_GPS_INTERVAL = "gps_interval_seconds"
        const val KEY_MAX_SPEED = "max_speed_kmh"
    }
}
