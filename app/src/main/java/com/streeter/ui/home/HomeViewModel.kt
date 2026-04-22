package com.streeter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.StreetRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class CityStats(
    val coveragePct: Int = 0,
    val coveredStreets: Int = 0,
    val totalDistanceKm: Float = 0f,
    val walkCount: Int = 0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val walkRepository: WalkRepository,
    private val streetRepository: StreetRepository
) : ViewModel() {

    init {
        if (!LocationService.isRunning) {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                var stale = walkRepository.getActiveRecordingWalk()
                while (stale != null) {
                    walkRepository.updateWalk(
                        stale.copy(status = WalkStatus.PENDING_MATCH, updatedAt = now)
                    )
                    stale = walkRepository.getActiveRecordingWalk()
                }
            }
        }
    }

    // null = still loading, -1L = no active walk, >0 = active walk id
    val activeWalkId: StateFlow<Long?> = walkRepository.getAllWalks()
        .map { walks -> walks.firstOrNull { it.status == WalkStatus.RECORDING }?.id ?: -1L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val cityStats: StateFlow<CityStats> = combine(
        walkRepository.getAllWalks(),
        streetRepository.observeCoveredStreetCount(),
        streetRepository.observeTotalStreetCount()
    ) { walks, covered, total ->
        val finishedWalks = walks.filter {
            it.status == WalkStatus.COMPLETED || it.status == WalkStatus.PENDING_MATCH
        }
        val totalDistanceKm = finishedWalks.sumOf { it.distanceM }.toFloat() / 1000f
        val pct = if (total > 0) (covered.toFloat() / total.toFloat() * 100).roundToInt() else 0
        CityStats(
            coveragePct = pct,
            coveredStreets = covered,
            totalDistanceKm = totalDistanceKm,
            walkCount = finishedWalks.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CityStats())
}
