package com.streeter

import com.streeter.data.local.dao.GpsPointDao
import com.streeter.data.local.entity.GpsPointEntity
import com.streeter.data.repository.GpsPointRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioral spec for [GpsPointRepositoryImpl.deletePoint] (issue #36).
 *
 * Point-level deletion is the foundation later GPS-edit slices build on: it must remove
 * exactly the targeted point and report the walk's remaining point count.
 */
class GpsPointRepositoryTest {
    private fun point(
        id: Long,
        walkId: Long = 1L,
    ) = GpsPointEntity(
        id = id,
        walkId = walkId,
        lat = 0.0,
        lng = 0.0,
        timestamp = id,
        accuracyM = 0f,
        speedKmh = 0f,
        isFiltered = false,
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

        override fun observePoints(walkId: Long): Flow<List<GpsPointEntity>> = flowOf(emptyList())

        override suspend fun deleteByWalkId(walkId: Long) {
            store.values.removeAll { it.walkId == walkId }
        }

        override suspend fun deleteById(
            walkId: Long,
            pointId: Long,
        ) {
            store[pointId]?.let { if (it.walkId == walkId) store.remove(pointId) }
        }

        override suspend fun countForWalk(walkId: Long): Int = store.values.count { it.walkId == walkId }
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
}
