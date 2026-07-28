package com.streeter

import androidx.lifecycle.SavedStateHandle
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.ui.editpoints.EditPointsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioral spec for [EditPointsViewModel] (issues #37, #38).
 *
 * Covers browsing/selecting a walk's GPS points, plus swipe-to-delete with undo and the
 * 2-point floor that blocks further deletion.
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

    /** In-memory, stateful [GpsPointRepository] so delete/undo round-trips are observable. */
    private class FakeGpsPointRepository(
        points: List<GpsPoint>,
    ) : GpsPointRepository {
        private val state = MutableStateFlow(points)

        override suspend fun insertPoints(points: List<GpsPoint>) {
            state.update { current ->
                val byId = current.associateBy { it.id }.toMutableMap()
                points.forEach { byId[it.id] = it }
                byId.values.sortedBy { it.timestamp }
            }
        }

        override suspend fun getPointsForWalk(walkId: Long): List<GpsPoint> = state.value.filter { it.walkId == walkId }

        override suspend fun getPointsForMapMatching(walkId: Long): List<GpsPoint> = state.value.filter { it.walkId == walkId }

        override fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>> =
            state.asStateFlow().map { list -> list.filter { it.walkId == walkId } }

        override suspend fun replacePointsFromRemote(
            walkId: Long,
            points: List<GpsPoint>,
        ) = Unit

        override suspend fun deletePoint(
            walkId: Long,
            pointId: Long,
        ): Int {
            state.update { current -> current.filterNot { it.walkId == walkId && it.id == pointId } }
            return state.value.count { it.walkId == walkId }
        }
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

    @Test
    fun `deleting a point removes it from the list immediately`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()

            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(point(1), point(3)), vm.uiState.value.points)
        }

    @Test
    fun `deleting a point sets a pending undo for the exact point removed`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()

            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(point(2), vm.uiState.value.pendingUndo?.point)
        }

    @Test
    fun `deleting the selected point clears the selection`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.selectPoint(2L)

            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(vm.uiState.value.selectedPointId)
        }

    @Test
    fun `undoing a delete restores the exact point that was deleted`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            vm.undoDelete(checkNotNull(vm.uiState.value.pendingUndo))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(point(1), point(2), point(3)), vm.uiState.value.points)
            assertNull(vm.uiState.value.pendingUndo)
        }

    @Test
    fun `dismissing the undo snackbar without acting clears the pending undo`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            vm.consumePendingUndo()

            assertNull(vm.uiState.value.pendingUndo)
            assertEquals(listOf(point(1), point(3)), vm.uiState.value.points)
        }

    @Test
    fun `deleting down to exactly 2 points is allowed`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()

            vm.deletePoint(point(3))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(point(1), point(2)), vm.uiState.value.points)
        }

    @Test
    fun `deleting when only 2 points remain is blocked and shows the floor message`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2)))
            dispatcher.scheduler.advanceUntilIdle()

            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(point(1), point(2)), vm.uiState.value.points)
            assertNull(vm.uiState.value.pendingUndo)
            assertTrue(vm.uiState.value.minPointsMessage)
        }

    @Test
    fun `dismissing the floor message clears it`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.deletePoint(point(2))

            vm.dismissMinPointsMessage()

            assertFalse(vm.uiState.value.minPointsMessage)
        }
}
