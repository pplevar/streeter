package com.streeter.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.data.remote.auth.SyncAuthTokenStore
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.sync.SyncAuthStatus
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
    val syncAuthToken: String = "",
    val syncAuthFailed: Boolean = false,
    val isRefreshingMapData: Boolean = false,
    val refreshMapDataError: String? = null,
    val showClearDataConfirm: Boolean = false,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val routingEngine: RoutingEngine,
        private val syncAuthTokenStore: SyncAuthTokenStore,
        private val syncAuthStatus: SyncAuthStatus,
    ) : ViewModel() {
        private val prefs: SharedPreferences =
            context.getSharedPreferences("streeter_settings", Context.MODE_PRIVATE)

        private val _uiState =
            MutableStateFlow(
                SettingsUiState(
                    gpsIntervalSeconds = prefs.getInt(KEY_GPS_INTERVAL, 20),
                    maxSpeedKmh = prefs.getInt(KEY_MAX_SPEED, 50),
                    syncAuthToken = syncAuthTokenStore.token,
                ),
            )
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                syncAuthStatus.authFailed.collect { failed ->
                    _uiState.update { it.copy(syncAuthFailed = failed) }
                }
            }
        }

        fun setGpsInterval(seconds: Int) {
            prefs.edit().putInt(KEY_GPS_INTERVAL, seconds).apply()
            _uiState.update { it.copy(gpsIntervalSeconds = seconds) }
        }

        fun setMaxSpeed(kmh: Int) {
            prefs.edit().putInt(KEY_MAX_SPEED, kmh).apply()
            _uiState.update { it.copy(maxSpeedKmh = kmh) }
        }

        fun setSyncAuthToken(token: String) {
            syncAuthTokenStore.token = token
            // Editing the token is the user acting on the auth-failure prompt: clear it so the banner
            // dismisses and the next sync attempt re-raises it only if the new token is still rejected.
            syncAuthStatus.clearAuthFailure()
            _uiState.update { it.copy(syncAuthToken = token) }
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
                            refreshMapDataError = "Map data refresh failed. Please try again.",
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
