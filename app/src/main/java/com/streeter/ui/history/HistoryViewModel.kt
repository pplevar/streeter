package com.streeter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.Walk
import com.streeter.domain.repository.StreetRepository
import com.streeter.domain.repository.WalkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val streetRepository: StreetRepository,
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
                WalkSortOrder.NEWEST -> walks.sortedByDescending { it.date }
                WalkSortOrder.LONGEST -> walks.sortedByDescending { it.distanceM }
                WalkSortOrder.MOST_STREETS -> walks.sortedByDescending { it.date }
            }
    }
