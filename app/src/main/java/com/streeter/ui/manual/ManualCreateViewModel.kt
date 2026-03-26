package com.streeter.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.*
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

enum class ManualCreateStep { SET_START, SET_END, GENERATING, DONE }

data class ManualCreateUiState(
    val step: ManualCreateStep = ManualCreateStep.SET_START,
    val startPin: LatLng? = null,
    val endPin: LatLng? = null,
    val generatedGeometryJson: String? = null,
    val createdWalkId: Long? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ManualCreateViewModel @Inject constructor(
    private val walkRepository: WalkRepository,
    private val routeSegmentRepository: RouteSegmentRepository,
    private val routingEngine: RoutingEngine,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualCreateUiState())
    val uiState: StateFlow<ManualCreateUiState> = _uiState.asStateFlow()

    fun setStep(step: ManualCreateStep) {
        _uiState.update { it.copy(step = step) }
    }

    fun onCameraMove(latLng: LatLng) {
        when (_uiState.value.step) {
            ManualCreateStep.SET_START -> _uiState.update { it.copy(startPin = latLng) }
            ManualCreateStep.SET_END -> _uiState.update { it.copy(endPin = latLng) }
            else -> {}
        }
    }

    fun generateRoute() {
        val start = _uiState.value.startPin ?: return
        val end = _uiState.value.endPin ?: return

        _uiState.update { it.copy(step = ManualCreateStep.GENERATING, errorMessage = null) }

        viewModelScope.launch {
            val engineReady = routingEngine.isReady() || run {
                try {
                    routingEngine.initialize()
                    true
                } catch (e: Exception) {
                    Timber.w(e, "Routing engine unavailable, falling back to straight-line route")
                    false
                }
            }

            if (!engineReady) {
                saveManualWalk(straightLineRoute(start, end))
                return@launch
            }

            routingEngine.route(from = start, to = end)
                .onSuccess { routeResult ->
                    saveManualWalk(routeResult)
                }
                .onFailure { error ->
                    Timber.e(error, "Manual route generation failed")
                    _uiState.update {
                        it.copy(
                            step = ManualCreateStep.SET_END,
                            errorMessage = "Could not generate route. Try different start/end points."
                        )
                    }
                }
        }
    }

    private suspend fun saveManualWalk(routeResult: RouteResult) {
        val now = System.currentTimeMillis()
        val walk = Walk(
            title = null,
            date = now,
            durationMs = 0,
            distanceM = routeResult.distanceM,
            status = WalkStatus.MANUAL_DRAFT,
            source = WalkSource.MANUAL,
            createdAt = now,
            updatedAt = now
        )

        val walkId = walkRepository.insertWalk(walk)

        // Persist route segment
        val wayIdsJson = "[${routeResult.wayIds.joinToString(",")}]"
        routeSegmentRepository.insertSegment(
            RouteSegment(
                walkId = walkId,
                geometryJson = routeResult.geometryJson,
                matchedWayIds = wayIdsJson,
                segmentOrder = 0
            )
        )

        // Transition to PENDING_MATCH and queue coverage computation
        walkRepository.updateWalk(
            walk.copy(
                id = walkId,
                status = WalkStatus.PENDING_MATCH,
                updatedAt = System.currentTimeMillis()
            )
        )
        workManager.enqueue(MapMatchingWorker.buildRequest(walkId))

        _uiState.update {
            it.copy(
                generatedGeometryJson = routeResult.geometryJson,
                createdWalkId = walkId,
                step = ManualCreateStep.DONE
            )
        }
    }

    private fun straightLineRoute(start: LatLng, end: LatLng): RouteResult {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(start.lat, start.lng, end.lat, end.lng, results)
        val geometry = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[${start.lng},${start.lat}],[${end.lng},${end.lat}]]},"properties":{}}"""
        return RouteResult(geometryJson = geometry, distanceM = results[0].toDouble(), wayIds = emptyList())
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
