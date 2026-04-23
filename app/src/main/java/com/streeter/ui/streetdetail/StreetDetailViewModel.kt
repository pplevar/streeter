package com.streeter.ui.streetdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.StreetWalkEntry
import com.streeter.domain.repository.StreetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class StreetDetailUiState(
    val streetName: String = "",
    val totalLengthM: Double = 0.0,
    val coveredLengthM: Double = 0.0,
    val coveredPct: Float = 0f,
    val walks: List<StreetWalkEntry> = emptyList(),
    val combinedGeometryJson: String? = null,
    val selectedWalkGeometryJson: String? = null,
    val selectedWalkId: Long? = null,
    val isLoading: Boolean = true,
    val notFound: Boolean = false
)

@HiltViewModel
class StreetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val streetRepository: StreetRepository,
    private val routingEngine: RoutingEngine
) : ViewModel() {

    private val streetId: Long = checkNotNull(savedStateHandle["streetId"])

    private val _uiState = MutableStateFlow(StreetDetailUiState())
    val uiState: StateFlow<StreetDetailUiState> = _uiState.asStateFlow()

    init {
        loadStreetDetail()
    }

    private fun loadStreetDetail() {
        viewModelScope.launch {
            try {
                val streetDeferred = async { streetRepository.getStreetById(streetId) }
                val coveredDeferred = async { streetRepository.getCoveredLengthForStreet(streetId) }
                val walksDeferred = async { streetRepository.getWalksForStreet(streetId) }

                val street = streetDeferred.await()
                if (street == null) {
                    _uiState.update { it.copy(isLoading = false, notFound = true) }
                    return@launch
                }

                val coveredLengthM = coveredDeferred.await()
                val walks = walksDeferred.await()

                val coveredPct = if (street.cityTotalLengthM > 0) {
                    (coveredLengthM / street.cityTotalLengthM).toFloat().coerceIn(0f, 1f)
                } else 0f

                val combinedGeometry = buildStreetGeometry(street.name)

                _uiState.update {
                    it.copy(
                        streetName = street.name,
                        totalLengthM = street.cityTotalLengthM,
                        coveredLengthM = coveredLengthM,
                        coveredPct = coveredPct,
                        walks = walks,
                        combinedGeometryJson = combinedGeometry,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load street detail for streetId=$streetId")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // Builds GeoJSON FeatureCollection for the full street extent from the routing graph.
    // Uses all graph edges sharing this street name, so uncovered sections are included.
    private fun buildStreetGeometry(streetName: String): String? {
        val features = routingEngine.getEdgeGeometriesForStreet(streetName)
        if (features.isEmpty()) return null
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    fun selectWalk(walkId: Long) {
        if (_uiState.value.selectedWalkId == walkId) {
            _uiState.update { it.copy(selectedWalkId = null, selectedWalkGeometryJson = null) }
            return
        }
        viewModelScope.launch {
            try {
                val edgeIds = streetRepository.getCoveredSectionEdgeIdsForWalk(walkId, streetId)
                val features = edgeIds.mapNotNull { edgeId ->
                    routingEngine.getEdgeGeometry(edgeId)
                }.filter { it.isNotBlank() }
                val geometry = if (features.isNotEmpty()) {
                    """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
                } else null
                _uiState.update { it.copy(selectedWalkId = walkId, selectedWalkGeometryJson = geometry) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load walk coverage for walkId=$walkId, streetId=$streetId")
            }
        }
    }
}
