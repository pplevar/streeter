package com.streeter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.Walk
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.StreetRepository
import com.streeter.domain.repository.WalkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject

enum class WalkSortOrder { NEWEST, LONGEST, MOST_STREETS }

data class WeeklyStats(
    val walkCount: Int = 0,
    val totalDistanceM: Double = 0.0,
    val totalStreetsCount: Int = 0,
)

data class HistoryUiState(
    val walks: List<Walk> = emptyList(),
    val sortOrder: WalkSortOrder = WalkSortOrder.NEWEST,
    val isLoading: Boolean = true,
    val weeklyStats: WeeklyStats = WeeklyStats(),
    val streetCountByWalkId: Map<Long, Int> = emptyMap(),
    val routePointsByWalkId: Map<Long, List<LatLng>> = emptyMap(),
)

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val streetRepository: StreetRepository,
        private val routeSegmentRepository: RouteSegmentRepository,
        private val gpsPointRepository: GpsPointRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HistoryUiState())
        val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

        init {
            observeWalks()
        }

        private fun observeWalks() {
            viewModelScope.launch {
                walkRepository.getAllWalks().collect { walks ->
                    val sorted = sortWalks(walks, _uiState.value.sortOrder)
                    val weeklyStats = computeWeeklyStats(sorted)
                    _uiState.update { it.copy(walks = sorted, isLoading = false, weeklyStats = weeklyStats) }
                    loadStreetCounts(sorted, weeklyStats)
                    loadRoutePoints(sorted)
                }
            }
        }

        private suspend fun loadStreetCounts(
            walks: List<Walk>,
            weeklyStats: WeeklyStats,
        ) {
            val startOfWeek = getStartOfWeekMs()
            val streetCounts = mutableMapOf<Long, Int>()
            var weeklyStreets = 0
            walks.forEach { walk ->
                val count = streetRepository.getStreetCountForWalk(walk.id)
                streetCounts[walk.id] = count
                if (walk.date >= startOfWeek) weeklyStreets += count
            }
            _uiState.update { state ->
                state.copy(
                    streetCountByWalkId = streetCounts,
                    weeklyStats = weeklyStats.copy(totalStreetsCount = weeklyStreets),
                )
            }
        }

        private suspend fun loadRoutePoints(walks: List<Walk>) {
            val routePoints = mutableMapOf<Long, List<LatLng>>()
            walks.forEach { walk ->
                val segments = routeSegmentRepository.getSegmentsForWalk(walk.id)
                routePoints[walk.id] =
                    if (segments.isNotEmpty()) {
                        parseLatLngsFromGeoJson(segments.first().geometryJson)
                    } else {
                        gpsPointRepository.getPointsForWalk(walk.id)
                            .filter { !it.isFiltered }
                            .map { LatLng(it.lat, it.lng) }
                    }
            }
            _uiState.update { it.copy(routePointsByWalkId = routePoints) }
        }

        private fun parseLatLngsFromGeoJson(geometryJson: String): List<LatLng> =
            try {
                val obj = JSONObject(geometryJson)
                val geometry = obj.optJSONObject("geometry") ?: obj
                val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
                if (geometry.optString("type") == "MultiLineString") {
                    buildList {
                        for (i in 0 until coordinates.length()) {
                            val line = coordinates.getJSONArray(i)
                            for (j in 0 until line.length()) {
                                val coord = line.getJSONArray(j)
                                add(LatLng(coord.getDouble(1), coord.getDouble(0)))
                            }
                        }
                    }
                } else {
                    buildList {
                        for (i in 0 until coordinates.length()) {
                            val coord = coordinates.getJSONArray(i)
                            add(LatLng(coord.getDouble(1), coord.getDouble(0)))
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }

        private fun computeWeeklyStats(walks: List<Walk>): WeeklyStats {
            val startOfWeek = getStartOfWeekMs()
            val thisWeek = walks.filter { it.date >= startOfWeek }
            return WeeklyStats(
                walkCount = thisWeek.size,
                totalDistanceM = thisWeek.sumOf { it.distanceM },
            )
        }

        private fun getStartOfWeekMs(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun setSortOrder(order: WalkSortOrder) {
            _uiState.update { state ->
                state.copy(
                    sortOrder = order,
                    walks = sortWalks(state.walks, order),
                )
            }
        }

        private fun sortWalks(
            walks: List<Walk>,
            order: WalkSortOrder,
        ): List<Walk> =
            when (order) {
                WalkSortOrder.NEWEST -> walks.sortedByDescending { it.createdAt }
                WalkSortOrder.LONGEST -> walks.sortedByDescending { it.distanceM }
                WalkSortOrder.MOST_STREETS -> walks.sortedByDescending { it.date }
            }
    }
