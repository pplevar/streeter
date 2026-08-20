package com.streeter.ui.editpoints

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.toLatLng
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.work.WalkRecalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    /** The point whose coordinate is being moved, or null when the editor is at rest. */
    val editingPointId: Long? = null,
    /**
     * Where the crosshair currently is — the coordinate [editingPointId] would take on Done.
     * Nothing is written until then, so this is the whole of an uncommitted move.
     */
    val pendingLatLng: LatLng? = null,
) {
    val selectedPoint: GpsPoint? get() = points.find { it.id == selectedPointId }
    val canDeleteMore: Boolean get() = points.size > MIN_POINTS

    /** True while the modal coordinate editor is up: the list and the pill give way to it. */
    val isEditing: Boolean get() = editingPointId != null

    /** The point being moved, still at its stored coordinate — the origin the ghost marks. */
    val editingPoint: GpsPoint? get() = points.find { it.id == editingPointId }

    /**
     * The line the live preview draws for the move in progress, or empty when nothing is being
     * moved. Derived, so the composable's only job is to report where the crosshair is.
     */
    val previewLine: List<LatLng>
        get() {
            val id = editingPointId ?: return emptyList()
            val pending = pendingLatLng ?: editingPoint?.toLatLng() ?: return emptyList()
            return editPreviewLine(points, id, pending)
        }
    private val selectedIndex: Int? get() = indexOf(selectedPointId)
    val canGoPrevious: Boolean get() = selectedIndex?.let { it > 0 } ?: false
    val canGoNext: Boolean get() = selectedIndex?.let { it < points.size - 1 } ?: false

    /** Position of the point with [id] in [points], or null if it isn't present. */
    fun indexOf(id: Long?): Int? = points.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    /**
     * The row the list should scroll to, given the rows it is currently showing, or null to
     * hold still (ADR-0007). The list follows a selection only when the selected row is
     * genuinely off-view, and never one the user made by tapping a row — a row must not shift
     * under the finger that just tapped it.
     */
    fun rowToScrollTo(visibleRows: List<Int>): Int? {
        if (selectionOrigin == SelectionOrigin.LIST) return null
        return indexOf(selectedPointId)?.takeIf { it !in visibleRows }
    }
}

@HiltViewModel
class EditPointsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val gpsPointRepository: GpsPointRepository,
        private val walkRecalculator: WalkRecalculator,
    ) : ViewModel() {
        private val walkId: Long = checkNotNull(savedStateHandle["walkId"])

        private val _uiState = MutableStateFlow(EditPointsUiState())
        val uiState: StateFlow<EditPointsUiState> = _uiState.asStateFlow()

        /**
         * Set once this session changed the trace — a deletion or a committed move. Drives the
         * re-match trigger on exit: a session of pure corrections changed the trace just as much
         * as one that deleted, so both must recalculate. An edit that was cancelled wrote
         * nothing and does not set it.
         */
        private var traceWasEdited = false

        /**
         * The newest trace write, each chained behind the one before it, so this single job
         * stands for every write the session has started. [onExit] waits on it: navigation
         * cancels [viewModelScope] the moment it returns, and a write still in flight would go
         * with it — taking the user's edit, and leaving the recalculation to run over a trace
         * that never changed.
         */
        private var writes: Job? = null

        /**
         * One undo opportunity per delete, queued rather than overwritten, so a rapid second
         * swipe can't silently strand the first point's undo snackbar.
         */
        private val _undoEvents = Channel<PendingUndo>(Channel.UNLIMITED)
        val undoEvents: Flow<PendingUndo> = _undoEvents.receiveAsFlow()

        /**
         * Runs [write] after every write already started, and records it as the one to wait for.
         * Writes to a trace are ordered — a delete and the undo that restores it, above all —
         * and chaining is what lets [onExit] wait for all of them by waiting for the last.
         */
        private fun writeTrace(write: suspend () -> Unit) {
            val previous = writes
            writes =
                viewModelScope.launch {
                    previous?.join()
                    write()
                }
        }

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
            traceWasEdited = true
            writeTrace {
                gpsPointRepository.deletePoint(walkId, point.id)
                _undoEvents.send(PendingUndo(point))
            }
        }

        /**
         * Called when leaving the editor. If the trace changed this session — points deleted,
         * points moved, or both — its Calculation is stale, so hand that to
         * [com.streeter.domain.work.WalkRecalculator]. No-op if nothing changed.
         *
         * Suspends rather than firing on [viewModelScope]: the caller navigates away right after
         * this returns, and that navigation tears down this ViewModel's scope, which would cancel
         * an in-flight `viewModelScope.launch` before the DB write and enqueue completed.
         */
        suspend fun onExit() {
            writes?.join()
            if (!traceWasEdited) return
            walkRecalculator.traceChanged(walkId)
        }

        /** Restores the exact point removed by [pendingUndo]. */
        fun undoDelete(pendingUndo: PendingUndo) {
            writeTrace {
                gpsPointRepository.insertPoints(listOf(pendingUndo.point))
            }
        }

        /**
         * Enters the modal coordinate editor for the selected point, seeding the pending
         * coordinate with where the point already is so the preview is drawn from the first
         * frame rather than after the first pan.
         *
         * Deliberately not gated on [EditPointsUiState.canDeleteMore]: moving a point removes
         * none, so the editor stays open at the minimum-points floor where deletion is blocked.
         * The asymmetry is the point — do not "fix" it by sharing delete's guard.
         */
        fun beginEdit() {
            val state = _uiState.value
            val point = state.selectedPoint ?: return
            _uiState.update { it.copy(editingPointId = point.id, pendingLatLng = point.toLatLng()) }
        }

        /** Reports where the crosshair now is. Stored points are untouched until [commitEdit]. */
        fun crosshairMovedTo(latLng: LatLng) {
            if (_uiState.value.editingPointId == null) return
            _uiState.update { it.copy(pendingLatLng = latLng) }
        }

        /**
         * Writes the pending coordinate and leaves edit mode, keeping the point selected so the
         * user can step straight on to its neighbour.
         *
         * A Done that moved the point nowhere is a Cancel: it writes nothing and leaves the
         * session no more edited than it found it, so a look at the editor never costs the walk
         * a recalculation.
         */
        fun commitEdit() {
            val state = _uiState.value
            val pointId = state.editingPointId ?: return
            val origin = state.editingPoint?.toLatLng()
            val pending = state.pendingLatLng
            _uiState.update { it.copy(editingPointId = null, pendingLatLng = null) }
            if (pending == null || pending == origin) return
            traceWasEdited = true
            writeTrace {
                gpsPointRepository.movePoint(walkId, pointId, pending.lat, pending.lng)
            }
        }

        /**
         * Leaves edit mode with the point where it started. Nothing was written, so this is a
         * pure state reset — no undo entry, and no recalculation to account for.
         */
        fun cancelEdit() {
            _uiState.update { it.copy(editingPointId = null, pendingLatLng = null) }
        }

        fun dismissMinPointsMessage() {
            _uiState.update { it.copy(minPointsMessage = false) }
        }
    }
