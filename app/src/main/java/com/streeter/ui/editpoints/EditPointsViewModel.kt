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

/** Minimum number of GPS points a walk must retain; swipe-to-delete is blocked at this floor. */
private const val MIN_POINTS = 2

data class PendingUndo(
    val point: GpsPoint,
    val token: Long,
)

data class EditPointsUiState(
    val points: List<GpsPoint> = emptyList(),
    val selectedPointId: Long? = null,
    val isLoading: Boolean = true,
    val pendingUndo: PendingUndo? = null,
    val minPointsMessage: Boolean = false,
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

        private var undoToken = 0L

        /** Swipe-deletes [point], unless doing so would drop the walk below [MIN_POINTS]. */
        fun deletePoint(point: GpsPoint) {
            if (_uiState.value.points.size <= MIN_POINTS) {
                _uiState.update { it.copy(minPointsMessage = true) }
                return
            }
            viewModelScope.launch {
                gpsPointRepository.deletePoint(walkId, point.id)
                _uiState.update {
                    it.copy(
                        selectedPointId = if (it.selectedPointId == point.id) null else it.selectedPointId,
                        pendingUndo = PendingUndo(point, ++undoToken),
                    )
                }
            }
        }

        /** Restores the exact point removed by [pendingUndo], if it hasn't already been superseded. */
        fun undoDelete(pendingUndo: PendingUndo) {
            viewModelScope.launch {
                gpsPointRepository.insertPoints(listOf(pendingUndo.point))
                if (_uiState.value.pendingUndo?.token == pendingUndo.token) {
                    _uiState.update { it.copy(pendingUndo = null) }
                }
            }
        }

        fun consumePendingUndo() {
            _uiState.update { it.copy(pendingUndo = null) }
        }

        fun dismissMinPointsMessage() {
            _uiState.update { it.copy(minPointsMessage = false) }
        }
    }
