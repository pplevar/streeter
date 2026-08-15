package com.streeter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.StreetRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.work.WalkRecalculator
import com.streeter.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.roundToInt

data class CityStats(
    val coveragePct: Int = 0,
    val coveredStreets: Int = 0,
    val totalDistanceKm: Float = 0f,
    val walkCount: Int = 0,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        streetRepository: StreetRepository,
        private val routingEngine: RoutingEngine,
        private val walkRecalculator: WalkRecalculator,
    ) : ViewModel() {
        init {
            if (!LocationService.isRunning) {
                viewModelScope.launch {
                    var stale = walkRepository.getActiveRecordingWalk()
                    var previousId: Long? = null
                    while (stale != null) {
                        if (stale.isPaused) break // paused walk is intact; RecordingViewModel will restore it
                        if (stale.id == previousId) break // the sweep didn't clear it; don't spin
                        previousId = stale.id
                        // Recalculate-only: Calculation's completion re-syncs the walk (ADR-0001),
                        // so the recovered recording reaches the server without an upfront Sync.
                        walkRecalculator.traceChanged(stale.id)
                        stale = walkRepository.getActiveRecordingWalk()
                    }
                }
            }
            viewModelScope.launch {
                try {
                    if (!routingEngine.isReady()) {
                        Timber.d("Pre-warming routing engine from HomeViewModel")
                        routingEngine.initialize()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Pre-warming routing engine failed (worker will retry)")
                }
            }
        }

        // null = still loading, -1L = no active walk, >0 = active walk id
        val activeWalkId: StateFlow<Long?> =
            walkRepository.getAllWalks()
                .map { walks -> walks.firstOrNull { it.status == WalkStatus.RECORDING }?.id ?: -1L }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        val cityStats: StateFlow<CityStats> =
            combine(
                walkRepository.getAllWalks(),
                streetRepository.observeCoveredStreetCount(),
                streetRepository.observeTotalStreetCount(),
            ) { walks, covered, total ->
                val finishedWalks =
                    walks.filter {
                        it.status == WalkStatus.COMPLETED || it.status == WalkStatus.PENDING_MATCH
                    }
                val totalDistanceKm = finishedWalks.sumOf { it.distanceM }.toFloat() / 1000f
                val pct = if (total > 0) (covered.toFloat() / total.toFloat() * 100).roundToInt() else 0
                CityStats(
                    coveragePct = pct,
                    coveredStreets = covered,
                    totalDistanceKm = totalDistanceKm,
                    walkCount = finishedWalks.size,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CityStats())
    }
