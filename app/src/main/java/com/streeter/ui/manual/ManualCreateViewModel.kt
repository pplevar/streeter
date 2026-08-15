package com.streeter.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.*
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.work.WalkRecalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class ManualCreateStep {
    PLACING_FIRST_POINT, // No points yet
    PLACING_NEXT_POINT, // ≥1 point placed, awaiting next
    ROUTING, // Computing segment between last two points
    FINISHING, // Persisting to DB
    DONE, // createdWalkId set, triggers nav
}

data class ManualCreateUiState(
    val step: ManualCreateStep = ManualCreateStep.PLACING_FIRST_POINT,
    val placedPoints: List<LatLng> = emptyList(),
    // parallel to placedPoints pairs
    val segmentGeometries: List<String> = emptyList(),
    val segmentDistances: List<Double> = emptyList(),
    val segmentWayIds: List<List<Long>> = emptyList(),
    val currentPin: LatLng? = null,
    val isRouting: Boolean = false,
    val createdWalkId: Long? = null,
    val errorMessage: String? = null,
) {
    val totalDistanceM: Double get() = segmentDistances.sum()
    val allWayIds: List<Long> get() = segmentWayIds.flatten()
    val hasSegments: Boolean get() = segmentGeometries.isNotEmpty()
    val hasPoints: Boolean get() = placedPoints.isNotEmpty()
    val accumulatedGeometryJson: String? get() =
        if (segmentGeometries.isEmpty()) null else SegmentMerge.merge(segmentGeometries)
}

@HiltViewModel
class ManualCreateViewModel
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val routeSegmentRepository: RouteSegmentRepository,
        private val gpsPointRepository: GpsPointRepository,
        private val routingEngine: RoutingEngine,
        private val walkRecalculator: WalkRecalculator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ManualCreateUiState())
        val uiState: StateFlow<ManualCreateUiState> = _uiState.asStateFlow()

        fun onCameraMove(latLng: LatLng) {
            val state = _uiState.value
            if (state.step != ManualCreateStep.PLACING_FIRST_POINT &&
                state.step != ManualCreateStep.PLACING_NEXT_POINT
            ) {
                return
            }
            _uiState.update { it.copy(currentPin = latLng) }
        }

        fun onConfirmPoint() {
            val state = _uiState.value
            val currentPin = state.currentPin ?: return
            if (state.step == ManualCreateStep.ROUTING || state.step == ManualCreateStep.FINISHING) return

            val updatedPoints = state.placedPoints + currentPin

            if (updatedPoints.size == 1) {
                _uiState.update {
                    it.copy(
                        placedPoints = updatedPoints,
                        step = ManualCreateStep.PLACING_NEXT_POINT,
                    )
                }
                return
            }

            // We have ≥2 points now; route the segment
            val start = updatedPoints[updatedPoints.size - 2]
            val end = updatedPoints[updatedPoints.size - 1]

            _uiState.update {
                it.copy(
                    placedPoints = updatedPoints,
                    step = ManualCreateStep.ROUTING,
                    isRouting = true,
                )
            }

            viewModelScope.launch {
                val engineReady =
                    routingEngine.isReady() ||
                        run {
                            try {
                                routingEngine.initialize()
                                true
                            } catch (e: Exception) {
                                Timber.w(e, "Routing engine unavailable, falling back to straight-line")
                                false
                            }
                        }

                if (engineReady) {
                    routingEngine.route(from = start, to = end)
                        .onSuccess { routeResult ->
                            appendSegment(routeResult)
                        }
                        .onFailure { error ->
                            Timber.e(error, "Segment routing failed")
                            _uiState.update {
                                it.copy(
                                    placedPoints = it.placedPoints.dropLast(1),
                                    step = ManualCreateStep.PLACING_NEXT_POINT,
                                    isRouting = false,
                                    errorMessage = "Could not generate route segment. Try a different point.",
                                )
                            }
                        }
                } else {
                    val result = straightLineRoute(start, end)
                    appendSegment(result)
                }
            }
        }

        private fun appendSegment(routeResult: RouteResult) {
            _uiState.update {
                it.copy(
                    segmentGeometries = it.segmentGeometries + routeResult.geometryJson,
                    segmentDistances = it.segmentDistances + routeResult.distanceM,
                    segmentWayIds = it.segmentWayIds + listOf(routeResult.wayIds),
                    step = ManualCreateStep.PLACING_NEXT_POINT,
                    isRouting = false,
                )
            }
        }

        fun onUndo() {
            val state = _uiState.value
            if (state.step == ManualCreateStep.ROUTING || state.step == ManualCreateStep.FINISHING) return
            if (!state.hasPoints) return

            var points = state.placedPoints.dropLast(1)
            var geometries = state.segmentGeometries
            var distances = state.segmentDistances
            var wayIds = state.segmentWayIds

            if (state.segmentGeometries.isNotEmpty()) {
                geometries = geometries.dropLast(1)
                distances = distances.dropLast(1)
                wayIds = wayIds.dropLast(1)
            }

            val step =
                if (points.isEmpty()) {
                    ManualCreateStep.PLACING_FIRST_POINT
                } else {
                    ManualCreateStep.PLACING_NEXT_POINT
                }

            _uiState.update {
                it.copy(
                    placedPoints = points,
                    segmentGeometries = geometries,
                    segmentDistances = distances,
                    segmentWayIds = wayIds,
                    step = step,
                    isRouting = false,
                )
            }
        }

        fun onFinish() {
            val state = _uiState.value
            if (!state.hasSegments) return
            if (state.step == ManualCreateStep.ROUTING || state.step == ManualCreateStep.FINISHING) return

            _uiState.update { it.copy(step = ManualCreateStep.FINISHING) }

            viewModelScope.launch {
                try {
                    val mergedGeometry = SegmentMerge.merge(state.segmentGeometries)

                    val wayIdsJson = "[${state.allWayIds.joinToString(",")}]"

                    val now = System.currentTimeMillis()
                    val walk =
                        Walk(
                            title = null,
                            date = now,
                            durationMs = 0,
                            distanceM = state.totalDistanceM,
                            status = WalkStatus.MANUAL_DRAFT,
                            source = WalkSource.MANUAL,
                            createdAt = now,
                            updatedAt = now,
                        )

                    val walkId = walkRepository.insertWalk(walk)

                    gpsPointRepository.insertPoints(
                        state.placedPoints.mapIndexed { index, point ->
                            GpsPoint(
                                walkId = walkId,
                                lat = point.lat,
                                lng = point.lng,
                                timestamp = now + index,
                                accuracyM = 0f,
                                speedKmh = 0f,
                                isFiltered = false,
                                isManual = true,
                            )
                        },
                    )

                    routeSegmentRepository.insertSegment(
                        RouteSegment(
                            walkId = walkId,
                            geometryJson = mergedGeometry,
                            matchedWayIds = wayIdsJson,
                            segmentOrder = 0,
                        ),
                    )

                    // New walk: Sync (durability) and Calculation (coverage) run in parallel.
                    walkRecalculator.traceChanged(walkId, newWalk = true)

                    _uiState.update {
                        it.copy(
                            step = ManualCreateStep.DONE,
                            createdWalkId = walkId,
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to finish walk creation")
                    _uiState.update {
                        it.copy(
                            step = ManualCreateStep.PLACING_NEXT_POINT,
                            errorMessage = "Failed to save. Please try again.",
                        )
                    }
                }
            }
        }

        /**
         * The segment used when the routing engine is unavailable: the two points joined
         * directly, measured as a great circle rather than as a difference of degrees.
         */
        internal fun straightLineRoute(
            start: LatLng,
            end: LatLng,
        ): RouteResult =
            RouteResult(
                geometryJson = TraceGeometry.lineStringFeature(listOf(start, end)),
                distanceM = TraceGeometry.distanceMeters(start, end),
                wayIds = emptyList(),
            )

        fun clearError() {
            _uiState.update { it.copy(errorMessage = null) }
        }
    }
