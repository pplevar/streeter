package com.streeter.ui.editpoints

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.work.WalkWorkScheduler
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

/**
 * Where the current selection came from. The list scrolls the selected row into view for
 * every origin but [LIST] — a row the user just tapped must not shift under their finger.
 */
enum class SelectionOrigin {
    /** The user clicked the point's row in the list. */
    LIST,

    /** The user tapped the point on the map. */
    MAP,

    /** Prev/next stepping, or the auto-advance that follows a deletion. */
    STEP,
}

data class EditPointsUiState(
    val points: List<GpsPoint> = emptyList(),
    val selectedPointId: Long? = null,
    val selectionOrigin: SelectionOrigin? = null,
    val isLoading: Boolean = true,
    val minPointsMessage: Boolean = false,
) {
    val selectedPoint: GpsPoint? get() = points.find { it.id == selectedPointId }
    val canDeleteMore: Boolean get() = points.size > MIN_POINTS
    private val selectedIndex: Int? get() = indexOf(selectedPointId)
    val canGoPrevious: Boolean get() = selectedIndex?.let { it > 0 } ?: false
    val canGoNext: Boolean get() = selectedIndex?.let { it < points.size - 1 } ?: false

    /** Position of the point with [id] in [points], or null if it isn't present. */
    fun indexOf(id: Long?): Int? = points.indexOfFirst { it.id == id }.takeIf { it >= 0 }
}

@HiltViewModel
class EditPointsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val gpsPointRepository: GpsPointRepository,
        private val walkRepository: WalkRepository,
        private val walkWorkScheduler: WalkWorkScheduler,
    ) : ViewModel() {
        private val walkId: Long = checkNotNull(savedStateHandle["walkId"])

        private val _uiState = MutableStateFlow(EditPointsUiState())
        val uiState: StateFlow<EditPointsUiState> = _uiState.asStateFlow()

        /** Set once any point is deleted this session; drives the re-match trigger on exit. */
        private var pointsWereDeleted = false

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

        /**
         * Marks [pointId] as selected. [origin] records how the user got here, so the list can
         * scroll to a selection it did not make and leave alone one it did.
         */
        fun selectPoint(
            pointId: Long,
            origin: SelectionOrigin,
        ) {
            _uiState.update { it.copy(selectedPointId = pointId, selectionOrigin = origin) }
        }

        /** Drops the selection — a tap on empty map is the user backing out of one. */
        fun clearSelection() {
            _uiState.update { it.copy(selectedPointId = null, selectionOrigin = null) }
        }

        /** Moves the selection to the previous point in list order; no-op at the start of the list. */
        fun selectPrevious() {
            val state = _uiState.value
            val index = state.indexOf(state.selectedPointId) ?: return
            if (index <= 0) return
            selectPoint(state.points[index - 1].id, SelectionOrigin.STEP)
        }

        /** Moves the selection to the next point in list order; no-op at the end of the list. */
        fun selectNext() {
            val state = _uiState.value
            val index = state.indexOf(state.selectedPointId) ?: return
            if (index >= state.points.size - 1) return
            selectPoint(state.points[index + 1].id, SelectionOrigin.STEP)
        }

        /**
         * Deletes [point], unless doing so would drop the walk below [MIN_POINTS]. If [point] was
         * selected, the selection auto-advances to the point that takes its place in list order
         * (or the new last point, if the deleted point was last) rather than dropping to none.
         */
        fun deletePoint(point: GpsPoint) {
            val state = _uiState.value
            if (!state.canDeleteMore) {
                _uiState.update { it.copy(minPointsMessage = true) }
                return
            }
            if (state.selectedPointId == point.id) {
                val index = state.indexOf(point.id) ?: 0
                val remaining = state.points.filterNot { it.id == point.id }
                val successor =
                    when {
                        remaining.isEmpty() -> null
                        index < remaining.size -> remaining[index].id
                        else -> remaining.last().id
                    }
                if (successor == null) clearSelection() else selectPoint(successor, SelectionOrigin.STEP)
            }
            pointsWereDeleted = true
            viewModelScope.launch {
                gpsPointRepository.deletePoint(walkId, point.id)
                _undoEvents.send(PendingUndo(point))
            }
        }

        /**
         * Called when leaving the editor. If any points were deleted this session, puts the walk
         * back through map matching and sync so the route, coverage, and remote copy reflect the
         * shorter point set — the same transition [com.streeter.ui.edit.RouteEditViewModel.save]
         * performs after a route edit. No-op if nothing was deleted.
         *
         * Suspends rather than firing on [viewModelScope]: the caller navigates away right after
         * this returns, and that navigation tears down this ViewModel's scope, which would cancel
         * an in-flight `viewModelScope.launch` before the DB write and enqueue completed.
         */
        suspend fun onExit() {
            if (!pointsWereDeleted) return
            walkRepository.getWalkById(walkId)?.let { walk ->
                walkRepository.updateWalk(
                    walk.copy(
                        status = WalkStatus.PENDING_MATCH,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            walkWorkScheduler.enqueueCalculation(walkId)
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
