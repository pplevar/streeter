package com.streeter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.WalkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WalkSortOrder { NEWEST, LONGEST, MOST_STREETS }

data class HistoryUiState(
    val walks: List<Walk> = emptyList(),
    val sortOrder: WalkSortOrder = WalkSortOrder.NEWEST,
    val isLoading: Boolean = true
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val walkRepository: WalkRepository
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
                _uiState.update { it.copy(walks = sorted, isLoading = false) }
            }
        }
    }

    fun setSortOrder(order: WalkSortOrder) {
        _uiState.update { state ->
            state.copy(
                sortOrder = order,
                walks = sortWalks(state.walks, order)
            )
        }
    }

    private fun sortWalks(walks: List<Walk>, order: WalkSortOrder): List<Walk> = when (order) {
        WalkSortOrder.NEWEST -> walks.sortedByDescending { it.date }
        WalkSortOrder.LONGEST -> walks.sortedByDescending { it.distanceM }
        WalkSortOrder.MOST_STREETS -> walks.sortedByDescending { it.date } // street count needs join; fallback to date
    }
}
