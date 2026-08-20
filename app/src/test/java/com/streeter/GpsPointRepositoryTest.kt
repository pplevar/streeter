package com.streeter

import com.streeter.data.local.dao.GpsPointDao
import com.streeter.data.local.entity.GpsPointEntity
import com.streeter.data.repository.GpsPointRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioral spec for [GpsPointRepositoryImpl.deletePoint] (issue #36) and the points editor's
 * observation (issue #49).
 *
 * Point-level deletion is the foundation later GPS-edit slices build on: it must remove
 * exactly the targeted point and report the walk's remaining point count. Both that count and
 * the editor's observation are over non-Outlier points, so the floor guards the same set the
 * user sees and Calculation consumes.
 */
class GpsPointRepositoryTest {
    private fun point(
        id: Long,
        walkId: Long = 1L,
        isFiltered: Boolean = false,
    ) = GpsPointEntity(
        id = id,
        walkId = walkId,
        lat = 0.0,
        lng = 0.0,
        timestamp = id,
        accuracyM = 0f,
        speedKmh = 0f,
        isFiltered = isFiltered,
    )

    /** In-memory [GpsPointDao] backing store, keyed by point id. */
    private class FakeGpsPointDao(
        points: List<GpsPointEntity>,
    ) : GpsPointDao {
        val store = points.associateBy { it.id }.toMutableMap()

        override suspend fun insertAll(points: List<GpsPointEntity>) {
            points.forEach { store[it.id] = it }
        }

        override suspend fun getPointsForMapMatching(walkId: Long): List<GpsPointEntity> = emptyList()

        override suspend fun getPointsForSync(walkId: Long): List<GpsPointEntity> = emptyList()

        override fun observePoints(walkId: Long): Flow<List<GpsPointEntity>> =
            flowOf(
                store.values
                    .filter { it.walkId == walkId && !it.isFiltered }
                    .sortedBy { it.timestamp },
            )

        override suspend fun getPointsExcludingWalk(excludeWalkId: Long): List<GpsPointEntity> =
            store.values.filter { it.walkId != excludeWalkId && !it.isFiltered }
                .sortedWith(compareBy({ it.walkId }, { it.timestamp }))

        override suspend fun deleteByWalkId(walkId: Long) {
            store.values.removeAll { it.walkId == walkId }
        }

        override suspend fun deleteById(
            walkId: Long,
            pointId: Long,
        ) {
            store[pointId]?.let { if (it.walkId == walkId) store.remove(pointId) }
        }

        override suspend fun updateCoordinate(
            walkId: Long,
            pointId: Long,
            lat: Double,
            lng: Double,
        ) {
            store[pointId]?.let { if (it.walkId == walkId) store[pointId] = it.copy(lat = lat, lng = lng) }
        }

        override suspend fun countUnfilteredForWalk(walkId: Long): Int = store.values.count { it.walkId == walkId && !it.isFiltered }
    }

    @Test
    fun `the editor's observation omits Outlier Points`() =
        runBlocking {
            val dao = FakeGpsPointDao(listOf(point(1L), point(2L, isFiltered = true), point(3L)))

            val observed = GpsPointRepositoryImpl(dao).observePointsForWalk(walkId = 1L).first()

            assertEquals(listOf(1L, 3L), observed.map { it.id })
        }

    @Test
    fun `deletePoint's remaining count ignores Outlier Points`() =
        runBlocking {
            val dao =
                FakeGpsPointDao(
                    listOf(point(1L), point(2L), point(3L), point(4L, isFiltered = true), point(5L, isFiltered = true)),
                )

            val remaining = GpsPointRepositoryImpl(dao).deletePoint(walkId = 1L, pointId = 3L)

            // Five rows stored, but only the two surviving non-outliers count towards the floor.
            assertEquals(2, remaining)
        }

    @Test
    fun `deleting an existing point removes it`() =
        runBlocking {
            val dao = FakeGpsPointDao(listOf(point(1L), point(2L), point(3L)))

            GpsPointRepositoryImpl(dao).deletePoint(walkId = 1L, pointId = 2L)

            assertNull(dao.store[2L])
        }

    @Test
    fun `deletePoint returns the remaining point count for the walk`() =
        runBlocking {
            val dao =
                FakeGpsPointDao(
                    listOf(point(1L, walkId = 1L), point(2L, walkId = 1L), point(3L, walkId = 1L), point(4L, walkId = 2L)),
                )

            val remaining = GpsPointRepositoryImpl(dao).deletePoint(walkId = 1L, pointId = 2L)

            // Two points left on walk 1; the point belonging to walk 2 is untouched and uncounted.
            assertEquals(2, remaining)
        }

    @Test
    fun `deleting a point can leave the walk at exactly 2 points`() =
        runBlocking {
            val dao = FakeGpsPointDao(listOf(point(1L), point(2L), point(3L)))

            val remaining = GpsPointRepositoryImpl(dao).deletePoint(walkId = 1L, pointId = 3L)

            assertEquals(2, remaining)
        }

    @Test
    fun `deletePoint does not delete a point belonging to a different walk`() =
        runBlocking {
            val dao = FakeGpsPointDao(listOf(point(1L, walkId = 1L), point(2L, walkId = 2L)))

            val remaining = GpsPointRepositoryImpl(dao).deletePoint(walkId = 1L, pointId = 2L)

            // Point 2 belongs to walk 2, so a walk-1 delete request must leave it untouched.
            assertEquals(point(2L, walkId = 2L), dao.store[2L])
            assertEquals(1, remaining)
        }

    @Test
    fun `movePoint writes the new coordinate`() =
        runBlocking {
            val dao = FakeGpsPointDao(listOf(point(1L), point(2L)))

            GpsPointRepositoryImpl(dao).movePoint(walkId = 1L, pointId = 2L, lat = 51.5, lng = -0.12)

            assertEquals(51.5, dao.store.getValue(2L).lat, 0.0)
            assertEquals(-0.12, dao.store.getValue(2L).lng, 0.0)
        }

    @Test
    fun `movePoint leaves everything but the coordinate as it was`() =
        runBlocking {
            // A repositioned observation is still the same observation: same row, same identity,
            // same moment, same place in the order (see CONTEXT.md, GPS Trace).
            val before = point(2L).copy(accuracyM = 17f, speedKmh = 4.5f)
            val dao = FakeGpsPointDao(listOf(point(1L), before))

            GpsPointRepositoryImpl(dao).movePoint(walkId = 1L, pointId = 2L, lat = 51.5, lng = -0.12)

            val after = dao.store.getValue(2L)
            assertEquals(before.id, after.id)
            assertEquals(before.timestamp, after.timestamp)
            assertEquals(before.accuracyM, after.accuracyM, 0f)
            assertEquals(before.speedKmh, after.speedKmh, 0f)
            assertEquals(before.isFiltered, after.isFiltered)
        }

    @Test
    fun `movePoint does not move a point of the same id under a different walk`() =
        runBlocking {
            val dao = FakeGpsPointDao(listOf(point(1L, walkId = 2L)))

            GpsPointRepositoryImpl(dao).movePoint(walkId = 1L, pointId = 1L, lat = 51.5, lng = -0.12)

            assertEquals(0.0, dao.store.getValue(1L).lat, 0.0)
            assertEquals(0.0, dao.store.getValue(1L).lng, 0.0)
        }
}
