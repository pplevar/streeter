package com.streeter

import androidx.lifecycle.SavedStateHandle
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.ui.editpoints.EditPointsViewModel
import com.streeter.ui.editpoints.PendingUndo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

        override suspend fun getPointsExcludingWalk(excludeWalkId: Long): List<GpsPoint> = emptyList()

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

    private fun walk(status: WalkStatus = WalkStatus.COMPLETED) =
        Walk(
            id = 1L,
            title = "Test walk",
            date = 0L,
            durationMs = 0L,
            distanceM = 0.0,
            status = status,
            source = WalkSource.RECORDED,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun viewModel(
        points: List<GpsPoint>,
        walkRepository: WalkRepository = FakeWalkRepository(listOf(walk())),
        walkWorkScheduler: FakeWalkWorkScheduler = FakeWalkWorkScheduler(),
    ): EditPointsViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("walkId" to 1L))
        return EditPointsViewModel(savedStateHandle, FakeGpsPointRepository(points), walkRepository, walkWorkScheduler)
    }

    /**
     * Runs [block] with a live collector on [EditPointsViewModel.undoEvents], as the screen has
     * while it is on screen, passing it the events seen so far.
     *
     * The collector is a child of the test coroutine, so `advanceUntilIdle` drives it — coroutines
     * launched on `backgroundScope` are *not* run by an `advanceUntilIdle` call from inside the
     * test body, and the events would never arrive. `undoEvents` never completes, so the job is
     * cancelled in a `finally`: left running, it would hang `runTest` to its timeout and fail the
     * test with `UncompletedCoroutinesError` however the assertions went.
     */
    private fun TestScope.withUndoEvents(
        vm: EditPointsViewModel,
        block: (events: List<PendingUndo>) -> Unit,
    ) {
        val events = mutableListOf<PendingUndo>()
        val collector = launch { vm.undoEvents.collect { events += it } }
        try {
            block(events)
        } finally {
            collector.cancel()
        }
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
    fun `deleting a point emits an undo event for the exact point removed`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            withUndoEvents(vm) { undoEvents ->
                dispatcher.scheduler.advanceUntilIdle()

                vm.deletePoint(point(2))
                dispatcher.scheduler.advanceUntilIdle()

                assertEquals(listOf(PendingUndo(point(2))), undoEvents)
            }
        }

    @Test
    fun `deleting two points in quick succession emits an undo event for each, in order`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            withUndoEvents(vm) { undoEvents ->
                dispatcher.scheduler.advanceUntilIdle()

                vm.deletePoint(point(1))
                vm.deletePoint(point(2))
                dispatcher.scheduler.advanceUntilIdle()

                assertEquals(listOf(PendingUndo(point(1)), PendingUndo(point(2))), undoEvents)
            }
        }

    @Test
    fun `deleting the selected point auto-advances selection to the point that took its place`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.selectPoint(2L)

            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(3L, vm.uiState.value.selectedPointId)
        }

    @Test
    fun `deleting the selected last point auto-advances selection to the new last point`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.selectPoint(3L)

            vm.deletePoint(point(3))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(2L, vm.uiState.value.selectedPointId)
        }

    @Test
    fun `deleting a non-selected point leaves the selection untouched`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.selectPoint(1L)

            vm.deletePoint(point(3))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1L, vm.uiState.value.selectedPointId)
        }

    @Test
    fun `selectPrevious moves selection to the adjacent point and stops at the start`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.selectPoint(3L)

            vm.selectPrevious()
            assertEquals(2L, vm.uiState.value.selectedPointId)

            vm.selectPrevious()
            assertEquals(1L, vm.uiState.value.selectedPointId)

            vm.selectPrevious()
            assertEquals(1L, vm.uiState.value.selectedPointId)
        }

    @Test
    fun `selectNext moves selection to the adjacent point and stops at the end`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.selectPoint(1L)

            vm.selectNext()
            assertEquals(2L, vm.uiState.value.selectedPointId)

            vm.selectNext()
            assertEquals(3L, vm.uiState.value.selectedPointId)

            vm.selectNext()
            assertEquals(3L, vm.uiState.value.selectedPointId)
        }

    @Test
    fun `canGoPrevious and canGoNext reflect position in the list`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()

            vm.selectPoint(1L)
            assertFalse(vm.uiState.value.canGoPrevious)
            assertTrue(vm.uiState.value.canGoNext)

            vm.selectPoint(2L)
            assertTrue(vm.uiState.value.canGoPrevious)
            assertTrue(vm.uiState.value.canGoNext)

            vm.selectPoint(3L)
            assertTrue(vm.uiState.value.canGoPrevious)
            assertFalse(vm.uiState.value.canGoNext)
        }

    @Test
    fun `undoing a delete restores the exact point that was deleted`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            withUndoEvents(vm) { undoEvents ->
                dispatcher.scheduler.advanceUntilIdle()
                vm.deletePoint(point(2))
                dispatcher.scheduler.advanceUntilIdle()

                vm.undoDelete(undoEvents.single())
                dispatcher.scheduler.advanceUntilIdle()

                assertEquals(listOf(point(1), point(2), point(3)), vm.uiState.value.points)
            }
        }

    @Test
    fun `not acting on the undo event leaves the deletion in place`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

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
            withUndoEvents(vm) { undoEvents ->
                dispatcher.scheduler.advanceUntilIdle()

                vm.deletePoint(point(2))
                dispatcher.scheduler.advanceUntilIdle()

                assertEquals(listOf(point(1), point(2)), vm.uiState.value.points)
                assertTrue(undoEvents.isEmpty())
                assertTrue(vm.uiState.value.minPointsMessage)
            }
        }

    @Test
    fun `canDeleteMore reflects the 2-point floor`() =
        runTest {
            val vm = viewModel(listOf(point(1), point(2), point(3)))
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(vm.uiState.value.canDeleteMore)

            vm.deletePoint(point(3))
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(vm.uiState.value.canDeleteMore)
        }

    @Test
    fun `leaving the editor with zero deletions does not change walk status or enqueue anything`() =
        runTest {
            val walkRepository = FakeWalkRepository(listOf(walk(status = WalkStatus.COMPLETED)))
            val scheduler = FakeWalkWorkScheduler()
            val vm = viewModel(listOf(point(1), point(2), point(3)), walkRepository, scheduler)
            dispatcher.scheduler.advanceUntilIdle()

            vm.onExit()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(WalkStatus.COMPLETED, walkRepository.getWalkById(1L)?.status)
            assertTrue(scheduler.calculationEnqueued.isEmpty())
        }

    @Test
    fun `leaving the editor after a deletion sets the walk to PENDING_MATCH and enqueues map matching`() =
        runTest {
            val walkRepository = FakeWalkRepository(listOf(walk(status = WalkStatus.COMPLETED)))
            val scheduler = FakeWalkWorkScheduler()
            val vm = viewModel(listOf(point(1), point(2), point(3)), walkRepository, scheduler)
            dispatcher.scheduler.advanceUntilIdle()
            vm.deletePoint(point(2))
            dispatcher.scheduler.advanceUntilIdle()

            vm.onExit()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(WalkStatus.PENDING_MATCH, walkRepository.getWalkById(1L)?.status)
            assertEquals(listOf(1L), scheduler.calculationEnqueued)
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
