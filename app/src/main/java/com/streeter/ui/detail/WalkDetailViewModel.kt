package com.streeter.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.streeter.domain.model.*
import com.streeter.domain.repository.WalkRepository
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
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
    val showDeleteConfirm: Boolean = false
)

@HiltViewModel
class WalkDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val walkRepository: WalkRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val walkId: Long = checkNotNull(savedStateHandle["walkId"])

    private val _uiState = MutableStateFlow(WalkDetailUiState())
    val uiState: StateFlow<WalkDetailUiState> = _uiState.asStateFlow()

    init {
        loadWalkDetail()
        observeCoverage()
    }

    private fun loadWalkDetail() {
        viewModelScope.launch {
            val walk = walkRepository.getWalkById(walkId)
            _uiState.update { it.copy(walk = walk, isLoading = false) }
        }
    }

    private fun observeCoverage() {
        viewModelScope.launch {
            walkRepository.getWalkWithCoverage(walkId).collect { coverage ->
                _uiState.update { it.copy(streetCoverage = coverage) }
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
                walkRepository.deleteWalk(walkId)
                _uiState.update { it.copy(isDeleted = true, showDeleteConfirm = false) }
            } catch (e: Exception) {
                Timber.e(e, "Delete failed for walk=$walkId")
                _uiState.update {
                    it.copy(
                        showDeleteConfirm = false,
                        errorMessage = "Delete failed. Please try again."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
