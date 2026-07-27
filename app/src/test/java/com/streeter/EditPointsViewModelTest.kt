package com.streeter

import androidx.lifecycle.SavedStateHandle
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.repository.GpsPointRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import com.streeter.ui.editpoints.EditPointsViewModel

/**
 * Behavioral spec for [EditPointsViewModel] (issue #37).
 *
 * This slice is read-only: the ViewModel's job is to expose a walk's GPS points and track
 * which one is currently selected, so the screen can center the map and highlight a marker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditPointsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun point(id: Long) =
        GpsPoint(
            id = id,
            walkId = 1L,
            lat = id.toDouble(),
            lng = id.toDouble(),
            timestamp = id,
            accuracyM = 5f,
            speedKmh = 0f,
            isFiltered = false,
        )

    /** In-memory [GpsPointRepository]; only [observePointsForWalk] is exercised by this ViewModel. */
    private class FakeGpsPointRepository(
        private val points: List<GpsPoint>,
    ) : GpsPointRepository {
        override suspend fun insertPoints(points: List<GpsPoint>) = Unit

        override suspend fun getPointsForWalk(walkId: Long): List<GpsPoint> = points

        override suspend fun getPointsForMapMatching(walkId: Long): List<GpsPoint> = points

        override fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>> = flowOf(points.filter { it.walkId == walkId })

        override suspend fun replacePointsFromRemote(
            walkId: Long,
            points: List<GpsPoint>,
        ) = Unit

        override suspend fun deletePoint(
            walkId: Long,
            pointId: Long,
        ): Int = points.size
    }

    private fun viewModel(points: List<GpsPoint>): EditPointsViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("walkId" to 1L))
        return EditPointsViewModel(savedStateHandle, FakeGpsPointRepository(points))
    }

    @Test
    fun `loads the walk's GPS points on start`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(point(1), point(2), point(3)), vm.uiState.value.points)
        }

    @Test
    fun `no point is selected initially`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2)))
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(vm.uiState.value.selectedPointId)
        }

    @Test
    fun `selecting a point updates state to that point's id`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2)))
            dispatcher.scheduler.advanceUntilIdle()

            vm.selectPoint(2L)

            assertEquals(2L, vm.uiState.value.selectedPointId)
        }

    @Test
    fun `selecting a point twice keeps it selected`() =
        runTest {
            val vm = viewModel(listOf(point(1)))
            dispatcher.scheduler.advanceUntilIdle()

            vm.selectPoint(1L)
            vm.selectPoint(1L)

            assertEquals(1L, vm.uiState.value.selectedPointId)
        }
}
