package com.streeter.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.*
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.work.WalkCalculationFinalizer
import com.streeter.domain.work.WalkDeleter
import com.streeter.domain.work.WalkRecalculator
import com.streeter.domain.work.WalkWork
import com.streeter.domain.work.WalkWorkScheduler
import com.streeter.work.MapMatchingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class WalkDetailUiState(
    val walk: Walk? = null,
    val streetCoverage: List<WalkStreetCoverage> = emptyList(),
    val routeGeometryJson: String? = null,
    val gpsPoints: List<GpsPoint> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
    val showDeleteConfirm: Boolean = false,
    val matchingProgress: Int? = null,
    val progressStep: String? = null,
    val isSyncing: Boolean = false,
)

@HiltViewModel
class WalkDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val walkRepository: WalkRepository,
        private val routeSegmentRepository: RouteSegmentRepository,
        private val gpsPointRepository: GpsPointRepository,
        private val workManager: WorkManager,
        private val walkWorkScheduler: WalkWorkScheduler,
        private val walkRecalculator: WalkRecalculator,
        private val walkDeleter: WalkDeleter,
        private val finalizer: WalkCalculationFinalizer,
        private val routingEngine: RoutingEngine,
    ) : ViewModel() {
        private val walkId: Long = checkNotNull(savedStateHandle["walkId"])

        private val _uiState = MutableStateFlow(WalkDetailUiState())
        val uiState: StateFlow<WalkDetailUiState> = _uiState.asStateFlow()

        init {
            loadWalkDetail()
            observeCoverage()
            observeWorkerProgress()
            observeSyncWorkerState()
            loadRouteData()
            preWarmEngine()
        }

        private fun loadRouteData() {
            viewModelScope.launch {
                val segments = routeSegmentRepository.getSegmentsForWalk(walkId)
                val geometry = segments.firstOrNull()?.geometryJson
                val points = if (geometry == null) gpsPointRepository.getPointsForWalk(walkId) else emptyList()
                _uiState.update { it.copy(routeGeometryJson = geometry, gpsPoints = points) }
            }
        }

        /**
         * Pre-warm the routing engine in the background so it's already loaded
         * when the user taps recalculate. Without this, the worker has to
         * initialize the engine from scratch (loading the graph from disk),
         * which causes heavy GC pressure and makes progress appear stuck at 5%.
         */
        private fun preWarmEngine() {
            viewModelScope.launch {
                try {
                    if (!routingEngine.isReady()) {
                        Timber.d("Pre-warming routing engine from WalkDetailViewModel")
                        routingEngine.initialize()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Pre-warming routing engine failed (worker will retry)")
                }
            }
        }

        private fun loadWalkDetail() {
            // Fast one-shot load to exit the loading state immediately
            viewModelScope.launch {
                val walk = walkRepository.getWalkById(walkId)
                _uiState.update { it.copy(walk = walk, isLoading = false) }
            }
            // Live observation so the UI reacts when the worker completes the walk
            viewModelScope.launch {
                walkRepository.observeWalk(walkId).collect { walk ->
                    val prevStatus = _uiState.value.walk?.status
                    _uiState.update { it.copy(walk = walk) }
                    // Reload route geometry once matching completes
                    if (prevStatus == WalkStatus.PENDING_MATCH && walk?.status == WalkStatus.COMPLETED) {
                        loadRouteData()
                    }
                }
            }
        }

        private fun observeWorkerProgress() {
            viewModelScope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(WalkWork.calculationName(walkId)).collect { infos ->
                    val info = infos.firstOrNull()
                    if (info != null && !info.state.isFinished) {
                        val progress = info.progress.getInt(MapMatchingWorker.KEY_PROGRESS, 0)
                        val step = info.progress.getString(MapMatchingWorker.KEY_STEP)
                        _uiState.update { it.copy(matchingProgress = progress.takeIf { p -> p > 0 }, progressStep = step) }
                    } else {
                        _uiState.update { it.copy(matchingProgress = null, progressStep = null) }
                    }
                }
            }
        }

        private fun observeCoverage() {
            viewModelScope.launch {
                walkRepository.getWalkWithCoverage(walkId).collect { coverage ->
                    val named = coverage.filter { !it.streetName.startsWith("Way ") }
                    _uiState.update { it.copy(streetCoverage = named) }
                }
            }
        }

        fun showDeleteConfirm() {
            _uiState.update { it.copy(showDeleteConfirm = true) }
        }

        fun dismissDeleteConfirm() {
            _uiState.update { it.copy(showDeleteConfirm = false) }
        }

        fun deleteWalk() {
            viewModelScope.launch {
                try {
                    walkDeleter.delete(walkId)
                    _uiState.update { it.copy(isDeleted = true, showDeleteConfirm = false) }
                } catch (e: Exception) {
                    Timber.e(e, "Delete failed for walk=$walkId")
                    _uiState.update {
                        it.copy(
                            showDeleteConfirm = false,
                            errorMessage = "Delete failed. Please try again.",
                        )
                    }
                }
            }
        }

        fun recalculateRoute() {
            viewModelScope.launch {
                try {
                    walkRecalculator.traceChanged(walkId)
                } catch (e: Exception) {
                    Timber.e(e, "Recalculate failed for walk=$walkId")
                    _uiState.update { it.copy(errorMessage = "Recalculation failed. Please try again.") }
                }
            }
        }

        /**
         * Stop a running Calculation: cancel the worker, mark the job DONE, and land the
         * walk in COMPLETED without coverage. Sync is unaffected; the walk can be
         * recalculated later via [recalculateRoute] (issue #15).
         */
        fun stopCalculation() {
            viewModelScope.launch {
                try {
                    finalizer.stop(walkId)
                } catch (e: Exception) {
                    Timber.e(e, "Stop failed for walk=$walkId")
                    _uiState.update { it.copy(errorMessage = "Stop failed. Please try again.") }
                }
            }
        }

        private fun observeSyncWorkerState() {
            viewModelScope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(WalkWork.syncName(walkId)).collect { infos ->
                    val info = infos.firstOrNull()
                    val isSyncing =
                        info != null &&
                            (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED)
                    _uiState.update { it.copy(isSyncing = isSyncing) }
                }
            }
        }

        fun syncWalk() {
            viewModelScope.launch {
                try {
                    walkWorkScheduler.enqueueSync(walkId)
                } catch (e: Exception) {
                    Timber.e(e, "Sync enqueue failed for walk=$walkId")
                    _uiState.update { it.copy(errorMessage = "Sync failed. Please try again.") }
                }
            }
        }

        fun clearError() {
            _uiState.update { it.copy(errorMessage = null) }
        }
    }
