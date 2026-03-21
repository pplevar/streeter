package com.streeter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.WalkRepository
import com.streeter.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val walkRepository: WalkRepository
) : ViewModel() {

    init {
        // If the service is not running, any RECORDING walks are stale — finalize them.
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
}
