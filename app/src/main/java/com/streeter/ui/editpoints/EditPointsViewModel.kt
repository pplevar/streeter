package com.streeter.ui.editpoints

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.repository.GpsPointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Minimum number of GPS points a walk must retain; swipe-to-delete is blocked at this floor. */
private const val MIN_POINTS = 2

data class PendingUndo(
    val point: GpsPoint,
)

data class EditPointsUiState(
    val points: List<GpsPoint> = emptyList(),
    val selectedPointId: Long? = null,
    val isLoading: Boolean = true,
    val minPointsMessage: Boolean = false,
) {
    val selectedPoint: GpsPoint? get() = points.find { it.id == selectedPointId }
    val canDeleteMore: Boolean get() = points.size > MIN_POINTS
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

        /**
         * One undo opportunity per delete, queued rather than overwritten, so a rapid second
         * swipe can't silently strand the first point's undo snackbar.
         */
        private val _undoEvents = Channel<PendingUndo>(Channel.UNLIMITED)
        val undoEvents: Flow<PendingUndo> = _undoEvents.receiveAsFlow()

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

        /** Swipe-deletes [point], unless doing so would drop the walk below [MIN_POINTS]. */
        fun deletePoint(point: GpsPoint) {
            if (!_uiState.value.canDeleteMore) {
                _uiState.update { it.copy(minPointsMessage = true) }
                return
            }
            viewModelScope.launch {
                gpsPointRepository.deletePoint(walkId, point.id)
                _uiState.update {
                    it.copy(selectedPointId = if (it.selectedPointId == point.id) null else it.selectedPointId)
                }
                _undoEvents.send(PendingUndo(point))
            }
        }

        /** Restores the exact point removed by [pendingUndo]. */
        fun undoDelete(pendingUndo: PendingUndo) {
            viewModelScope.launch {
                gpsPointRepository.insertPoints(listOf(pendingUndo.point))
            }
        }

        fun dismissMinPointsMessage() {
            _uiState.update { it.copy(minPointsMessage = false) }
        }
    }
