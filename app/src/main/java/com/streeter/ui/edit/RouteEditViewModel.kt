package com.streeter.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.streeter.data.engine.StreetCoverageEngine
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.*
import com.streeter.domain.repository.EditOperationRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.work.MapMatchingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class EditMode {
    IDLE,
    SELECT_ANCHOR_1,
    SELECT_ANCHOR_2,
    SELECT_WAYPOINT,
    RECALCULATING,
    PREVIEW,
    SAVING
}

data class RouteEditUiState(
    val walk: Walk? = null,
    val routeGeometryJson: String? = null,
    val previewGeometryJson: String? = null,
    val anchor1: LatLng? = null,
    val anchor2: LatLng? = null,
    val waypoint: LatLng? = null,
    val editMode: EditMode = EditMode.IDLE,
    val correctionCount: Int = 0,
    val hasUnsavedChanges: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isSaved: Boolean = false
)

@HiltViewModel
class RouteEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walkRepository: WalkRepository,
    private val routeSegmentRepository: RouteSegmentRepository,
    private val editOperationRepository: EditOperationRepository,
    private val routingEngine: RoutingEngine,
    private val workManager: WorkManager
) : ViewModel() {

    private val walkId: Long = checkNotNull(savedStateHandle["walkId"])

    private val _uiState = MutableStateFlow(RouteEditUiState())
    val uiState: StateFlow<RouteEditUiState> = _uiState.asStateFlow()

    // In-memory route geometry (working copy, not yet saved)
    private var currentGeometryJson: String? = null

    init {
        loadWalk()
    }

    private fun loadWalk() {
        viewModelScope.launch {
            val walk = walkRepository.getWalkById(walkId)
            if (walk == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Walk not found") }
                return@launch
            }

            val segments = routeSegmentRepository.getSegmentsForWalk(walkId)
            val geometry = segments.firstOrNull()?.geometryJson

            // Load any existing uncommitted edit operations (crash recovery)
            val ops = editOperationRepository.getOperationsForWalk(walkId)
            val correctionCount = ops.size

            currentGeometryJson = geometry
            _uiState.update {
                it.copy(
                    walk = walk,
                    routeGeometryJson = geometry,
                    correctionCount = correctionCount,
                    hasUnsavedChanges = correctionCount > 0,
                    isLoading = false,
                    editMode = EditMode.IDLE
                )
            }
        }
    }

    fun startSelectAnchor1() {
        _uiState.update { it.copy(editMode = EditMode.SELECT_ANCHOR_1, anchor1 = null, anchor2 = null, waypoint = null) }
    }

    fun onMapTap(latLng: LatLng) {
        when (_uiState.value.editMode) {
            EditMode.SELECT_ANCHOR_1 -> {
                _uiState.update { it.copy(anchor1 = latLng, editMode = EditMode.SELECT_ANCHOR_2) }
            }
            EditMode.SELECT_ANCHOR_2 -> {
                _uiState.update { it.copy(anchor2 = latLng, editMode = EditMode.SELECT_WAYPOINT) }
            }
            EditMode.SELECT_WAYPOINT -> {
                _uiState.update { it.copy(waypoint = latLng) }
                recalculateSegment()
            }
            else -> { /* ignore taps in other modes */ }
        }
    }

    private fun recalculateSegment() {
        val state = _uiState.value
        val anchor1 = state.anchor1 ?: return
        val anchor2 = state.anchor2 ?: return
        val waypoint = state.waypoint ?: return

        _uiState.update { it.copy(editMode = EditMode.RECALCULATING, errorMessage = null) }

        viewModelScope.launch {
            if (!routingEngine.isReady()) {
                try {
                    routingEngine.initialize()
                } catch (e: Exception) {
                    Timber.e(e, "Routing engine initialization failed")
                    _uiState.update {
                        it.copy(
                            editMode = EditMode.IDLE,
                            errorMessage = "Routing unavailable: map data not loaded."
                        )
                    }
                    return@launch
                }
            }

            routingEngine.route(from = anchor1, to = anchor2, via = listOf(waypoint))
                .onSuccess { routeResult ->
                    _uiState.update {
                        it.copy(
                            previewGeometryJson = routeResult.geometryJson,
                            editMode = EditMode.PREVIEW
                        )
                    }
                }
                .onFailure { error ->
                    Timber.e(error, "Segment recalculation failed")
                    _uiState.update {
                        it.copy(
                            editMode = EditMode.SELECT_WAYPOINT,
                            errorMessage = "Routing failed. Try a different waypoint."
                        )
                    }
                }
        }
    }

    fun confirmPreview() {
        val state = _uiState.value
        val previewGeometry = state.previewGeometryJson ?: return
        val anchor1 = state.anchor1 ?: return
        val anchor2 = state.anchor2 ?: return
        val waypoint = state.waypoint ?: return
        val originalGeometry = currentGeometryJson ?: return

        viewModelScope.launch {
            val op = EditOperation(
                walkId = walkId,
                operationOrder = state.correctionCount,
                anchor1Lat = anchor1.lat,
                anchor1Lng = anchor1.lng,
                anchor2Lat = anchor2.lat,
                anchor2Lng = anchor2.lng,
                waypointLat = waypoint.lat,
                waypointLng = waypoint.lng,
                replacedGeometryJson = originalGeometry,
                newGeometryJson = previewGeometry,
                createdAt = System.currentTimeMillis()
            )
            editOperationRepository.insertOperation(op)

            val splicedGeometry = spliceGeometry(originalGeometry, previewGeometry, anchor1, anchor2)
            currentGeometryJson = splicedGeometry

            _uiState.update {
                it.copy(
                    routeGeometryJson = splicedGeometry,
                    previewGeometryJson = null,
                    correctionCount = it.correctionCount + 1,
                    hasUnsavedChanges = true,
                    editMode = EditMode.IDLE,
                    anchor1 = null,
                    anchor2 = null,
                    waypoint = null
                )
            }
        }
    }

    /**
     * Splices [previewJson] into [originalJson] between the points closest to [anchor1] and [anchor2].
     * Replaces the original segment from idx1 to idx2 (inclusive) with the preview.
     */
    private fun spliceGeometry(
        originalJson: String,
        previewJson: String,
        anchor1: LatLng,
        anchor2: LatLng
    ): String {
        val origCoords = parseCoordinates(originalJson)
        val previewCoords = parseCoordinates(previewJson)
        if (origCoords.isEmpty() || previewCoords.isEmpty()) return previewJson

        // Search the full route for both anchors independently, then sort so idx1 < idx2
        val idxA = origCoords.indices.minByOrNull { distanceSq(origCoords[it], anchor1) } ?: 0
        val idxB = origCoords.indices.minByOrNull { distanceSq(origCoords[it], anchor2) } ?: (origCoords.size - 1)
        val idx1 = minOf(idxA, idxB)
        val idx2 = maxOf(idxA, idxB)

        Timber.d("splice: origSize=${origCoords.size} idx1=$idx1 idx2=$idx2 previewSize=${previewCoords.size}")

        // Replace origCoords[idx1..idx2] with preview (idx1/idx2 are removed, not kept)
        val spliced = origCoords.take(idx1) + previewCoords + origCoords.drop(idx2 + 1)
        return buildLineString(spliced)
    }

    private fun parseCoordinates(geometryJson: String): List<LatLng> {
        return try {
            val obj = org.json.JSONObject(geometryJson)
            val arr = obj.getJSONObject("geometry").getJSONArray("coordinates")
            (0 until arr.length()).map { i ->
                val pair = arr.getJSONArray(i)
                LatLng(lat = pair.getDouble(1), lng = pair.getDouble(0))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse geometry coordinates")
            emptyList()
        }
    }

    private fun distanceSq(a: LatLng, b: LatLng): Double {
        val dlat = a.lat - b.lat
        val dlng = a.lng - b.lng
        return dlat * dlat + dlng * dlng
    }

    private fun buildLineString(coords: List<LatLng>): String {
        val coordStr = coords.joinToString(",") { "[${it.lng},${it.lat}]" }
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordStr]},"properties":{}}"""
    }

    fun discardPreview() {
        _uiState.update {
            it.copy(
                previewGeometryJson = null,
                editMode = EditMode.SELECT_WAYPOINT
            )
        }
    }

    fun undo() {
        viewModelScope.launch {
            val ops = editOperationRepository.getOperationsForWalk(walkId)
            if (ops.isEmpty()) return@launch

            val lastOp = ops.last()
            editOperationRepository.deleteLastOperation(walkId)
            currentGeometryJson = lastOp.replacedGeometryJson

            _uiState.update {
                it.copy(
                    routeGeometryJson = lastOp.replacedGeometryJson,
                    correctionCount = (it.correctionCount - 1).coerceAtLeast(0),
                    hasUnsavedChanges = it.correctionCount - 1 > 0,
                    editMode = EditMode.IDLE
                )
            }
        }
    }

    fun discardAll() {
        viewModelScope.launch {
            editOperationRepository.deleteAllOperationsForWalk(walkId)

            // Reload original segment from DB
            val segments = routeSegmentRepository.getSegmentsForWalk(walkId)
            val originalGeometry = segments.firstOrNull()?.geometryJson
            currentGeometryJson = originalGeometry

            _uiState.update {
                it.copy(
                    routeGeometryJson = originalGeometry,
                    correctionCount = 0,
                    hasUnsavedChanges = false,
                    editMode = EditMode.IDLE,
                    anchor1 = null,
                    anchor2 = null,
                    waypoint = null
                )
            }
        }
    }

    fun save() {
        val geometry = currentGeometryJson ?: return
        _uiState.update { it.copy(editMode = EditMode.SAVING) }

        viewModelScope.launch {
            try {
                // Delete uncommitted edit operations (they're now baked into the persisted segment)
                editOperationRepository.deleteAllOperationsForWalk(walkId)

                // Update route segment in DB
                routeSegmentRepository.deleteSegmentsForWalk(walkId)
                routeSegmentRepository.insertSegment(
                    RouteSegment(
                        walkId = walkId,
                        geometryJson = geometry,
                        matchedWayIds = "[]",
                        segmentOrder = 0
                    )
                )

                // Transition walk to PENDING_MATCH and queue coverage recalculation
                walkRepository.getWalkById(walkId)?.let { walk ->
                    walkRepository.updateWalk(
                        walk.copy(
                            status = WalkStatus.PENDING_MATCH,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                workManager.enqueue(MapMatchingWorker.buildRequest(walkId))

                _uiState.update { it.copy(isSaved = true) }
            } catch (e: Exception) {
                Timber.e(e, "Save failed for walk=$walkId")
                _uiState.update {
                    it.copy(
                        editMode = EditMode.IDLE,
                        errorMessage = "Save failed. Please try again."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
