package com.streeter

import com.streeter.data.local.dao.StreetDao
import com.streeter.data.local.dao.StreetWalkRow
import com.streeter.data.local.dao.WalkDao
import com.streeter.data.local.dao.WalkStreetWithName
import com.streeter.data.local.entity.StreetEntity
import com.streeter.data.local.entity.StreetSectionEntity
import com.streeter.data.local.entity.WalkEntity
import com.streeter.data.local.entity.WalkSectionEntity
import com.streeter.data.local.entity.WalkStreetEntity
import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.data.repository.WalkRepositoryImpl
import com.streeter.domain.model.WalkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioral spec for converging a walk deletion that originated on another device (issue #23,
 * ADR-0003). The pull feed carries the deletion as a `DELETED` tombstone; on receiving one for a
 * walk it already holds, a peer device hard-deletes the local row (Room CASCADE then drops its
 * Coverage, correcting the covered-street count). A tombstone for an unknown walk is never
 * resurrected, and deletion is terminal — it wins over any unsynced local edit.
 */
class PullDeletionConvergenceTest {
    private fun repo(dao: FakeWalkDao) = WalkRepositoryImpl(dao, UnusedStreetDao)

    private fun tombstone(
        serverWalkId: Long,
        updatedAt: Long = 100L,
    ) = dto(serverWalkId = serverWalkId, status = WalkStatus.DELETED, updatedAt = updatedAt)

    private fun dto(
        serverWalkId: Long,
        status: WalkStatus,
        updatedAt: Long = 100L,
    ) = WalkSyncDto(
        serverWalkId = serverWalkId,
        localWalkId = 0L,
        clientId = "other-device",
        title = "Evening loop",
        date = 0L,
        durationMs = 0L,
        distanceM = 0.0,
        status = status.name,
        source = "RECORDED",
        createdAt = 0L,
        updatedAt = updatedAt,
        serverUpdatedAt = updatedAt,
    )

    private fun heldWalk(
        id: Long,
        serverWalkId: Long,
        updatedAt: Long = 0L,
    ) = WalkEntity(
        id = id,
        title = "Evening loop",
        date = 0L,
        durationMs = 0L,
        distanceM = 0.0,
        status = WalkStatus.COMPLETED.name,
        source = "RECORDED",
        createdAt = 0L,
        updatedAt = updatedAt,
        syncStatus = "SYNCED",
        serverWalkId = serverWalkId,
    )

    @Test
    fun `pulling a DELETED tombstone hard-deletes the matching local walk`() =
        runBlocking {
            val dao = FakeWalkDao(listOf(heldWalk(id = 1L, serverWalkId = 99L)))
            val repo = repo(dao)

            repo.upsertFromRemote(tombstone(serverWalkId = 99L))

            // The local row is gone entirely (not merely soft-updated to DELETED), so Room CASCADE
            // drops its Coverage and the covered-street count corrects itself.
            assertNull(repo.getWalkById(1L))
            assertNull(repo.getWalkByServerWalkId(99L))
        }

    @Test
    fun `a DELETED tombstone for an unknown walk is a no-op`() =
        runBlocking {
            val dao = FakeWalkDao()
            val repo = repo(dao)

            repo.upsertFromRemote(tombstone(serverWalkId = 77L))

            // A walk we never held is never resurrected into a live row by its tombstone.
            assertNull(repo.getWalkByServerWalkId(77L))
        }

    @Test
    fun `a pulled tombstone wins over an unsynced local edit`() =
        runBlocking {
            // The local copy carries a newer edit than the tombstone (updatedAt 500 > 100), modelling
            // an unsynced local change racing a deletion made on another device.
            val dao = FakeWalkDao(listOf(heldWalk(id = 1L, serverWalkId = 99L, updatedAt = 500L)))
            val repo = repo(dao)

            repo.upsertFromRemote(tombstone(serverWalkId = 99L, updatedAt = 100L))

            // Deletion is terminal: it wins regardless of the local edit's recency.
            assertNull(repo.getWalkById(1L))
        }

    @Test
    fun `a live remote walk not held locally is inserted`() =
        runBlocking {
            // Control: the DELETED branch suppresses creation specifically — an ordinary walk is still
            // inserted, so the tombstone no-op above is not merely a dead upsert path.
            val dao = FakeWalkDao()
            val repo = repo(dao)

            repo.upsertFromRemote(dto(serverWalkId = 42L, status = WalkStatus.COMPLETED))

            val inserted = repo.getWalkByServerWalkId(42L)
            assertNotNull(inserted)
            assertEquals(WalkStatus.COMPLETED, inserted?.status)
        }
}

// --- In-memory DAO fakes -------------------------------------------------------------------------

/**
 * In-memory [WalkDao] backed by a mutable map, faithful for the lookup/insert/delete surface the
 * remote-upsert convergence exercises. Insert honours Room autogenerate: a zero id gets the next
 * sequence value.
 */
internal class FakeWalkDao(seed: List<WalkEntity> = emptyList()) : WalkDao {
    private val store = seed.associateBy { it.id }.toMutableMap()
    private var idSeq = seed.maxOfOrNull { it.id } ?: 0L

    override suspend fun getById(id: Long): WalkEntity? = store[id]

    override suspend fun getWalkByServerWalkId(serverWalkId: Long): WalkEntity? =
        store.values.firstOrNull { it.serverWalkId == serverWalkId }

    override suspend fun insert(walk: WalkEntity): Long {
        val id = if (walk.id == 0L) ++idSeq else walk.id
        store[id] = walk.copy(id = id)
        return id
    }

    override suspend fun update(
        id: Long,
        title: String?,
        date: Long,
        durationMs: Long,
        distanceM: Double,
        status: String,
        source: String,
        createdAt: Long,
        updatedAt: Long,
        syncStatus: String,
        serverWalkId: Long?,
        lastResumedAt: Long?,
        isPaused: Boolean,
    ) {
        val existing = store[id] ?: return
        store[id] =
            existing.copy(
                title = title,
                date = date,
                durationMs = durationMs,
                distanceM = distanceM,
                status = status,
                source = source,
                createdAt = createdAt,
                updatedAt = updatedAt,
                syncStatus = syncStatus,
                serverWalkId = serverWalkId,
                lastResumedAt = lastResumedAt,
                isPaused = isPaused,
            )
    }

    override suspend fun hardDelete(id: Long) {
        store.remove(id)
    }

    override suspend fun softDelete(id: Long) {
        store[id]?.let { store[id] = it.copy(status = WalkStatus.DELETED.name) }
    }

    override fun getAllWalks(): Flow<List<WalkEntity>> = error("unused")

    override fun observeById(id: Long): Flow<WalkEntity?> = error("unused")

    override suspend fun getActiveRecording(): WalkEntity? = error("unused")

    override suspend fun getPendingSync(): List<WalkEntity> = error("unused")

    override suspend fun updateSyncStatus(
        id: Long,
        syncStatus: String,
        serverWalkId: Long?,
    ) = error("unused")

    override suspend fun updateSyncStatusOnly(
        id: Long,
        syncStatus: String,
    ) = error("unused")

    override suspend fun getGpsTraceSyncedAt(id: Long): Long? = error("unused")

    override suspend fun updateGpsTraceSyncedAt(
        id: Long,
        timestamp: Long,
    ) = error("unused")
}

/** [StreetDao] is never touched by remote-upsert convergence; every call is a test bug. */
internal object UnusedStreetDao : StreetDao {
    override suspend fun upsertStreet(street: StreetEntity): Long = error("unused")

    override suspend fun upsertStreets(streets: List<StreetEntity>): List<Long> = error("unused")

    override suspend fun getStreetByOsmWayId(osmWayId: Long): StreetEntity? = error("unused")

    override suspend fun upsertSection(section: StreetSectionEntity) = error("unused")

    override suspend fun upsertSections(sections: List<StreetSectionEntity>) = error("unused")

    override suspend fun getSectionsByStreetId(streetId: Long): List<StreetSectionEntity> = error("unused")

    override suspend fun getSectionByStableId(stableId: String): StreetSectionEntity? = error("unused")

    override suspend fun getSectionsByStableIds(stableIds: List<String>): List<StreetSectionEntity> = error("unused")

    override suspend fun insertWalkStreet(coverage: WalkStreetEntity) = error("unused")

    override suspend fun insertWalkStreets(coverages: List<WalkStreetEntity>) = error("unused")

    override suspend fun insertWalkSection(coverage: WalkSectionEntity) = error("unused")

    override suspend fun insertWalkSections(coverages: List<WalkSectionEntity>) = error("unused")

    override suspend fun deleteWalkStreets(walkId: Long) = error("unused")

    override suspend fun deleteWalkSections(walkId: Long) = error("unused")

    override fun observeWalkCoverage(walkId: Long): Flow<List<WalkStreetWithName>> = error("unused")

    override suspend fun getWalkCoverage(walkId: Long): List<WalkStreetWithName> = error("unused")

    override suspend fun getStreetCountForWalk(walkId: Long): Int = error("unused")

    override fun observeCoveredStreetCount(): Flow<Int> = error("unused")

    override fun observeTotalStreetCount(): Flow<Int> = error("unused")

    override suspend fun getStreetById(streetId: Long): StreetEntity? = error("unused")

    override suspend fun getCoveredLengthForStreet(streetId: Long): Double = error("unused")

    override suspend fun getWalksForStreet(streetId: Long): List<StreetWalkRow> = error("unused")

    override suspend fun getCoveredSectionEdgeIdsForWalk(
        walkId: Long,
        streetId: Long,
    ): List<Long> = error("unused")
}
