package com.streeter.ui.editpoints

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.repository.GpsPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditPointsUiState(
    val points: List<GpsPoint> = emptyList(),
    val selectedPointId: Long? = null,
    val isLoading: Boolean = true,
) {
    val selectedPoint: GpsPoint? get() = points.find { it.id == selectedPointId }
}

@HiltViewModel
class EditPointsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val gpsPointRepository: GpsPointRepository,
    ) : ViewModel() {
        private val walkId: Long = checkNotNull(savedStateHandle["walkId"])

        private val _uiState = MutableStateFlow(EditPointsUiState())
        val uiState: StateFlow<EditPointsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                gpsPointRepository.observePointsForWalk(walkId).collect { points ->
                    _uiState.update { it.copy(points = points, isLoading = false) }
                }
            }
        }

        fun selectPoint(pointId: Long) {
            _uiState.update { it.copy(selectedPointId = pointId) }
        }
    }
