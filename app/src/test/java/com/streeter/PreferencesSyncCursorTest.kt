package com.streeter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.streeter.data.sync.PreferencesSyncCursor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The production [PreferencesSyncCursor] (issue #54). Two things are worth pinning beyond what the
 * in-memory adapter already covers: the cursor never rewinds, and the preference file and keys are
 * the ones the pull worker and sync repository used *before* the seam existed — an install
 * upgrading into this change must keep its cursor and its client id, or it re-pulls the whole feed
 * and starts calling itself a different device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferencesSyncCursorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The file and keys as they were written before the seam existed. */
    private val legacyPrefs get() = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    private fun cursor() = PreferencesSyncCursor(context)

    @Before
    fun clearPreferences() {
        legacyPrefs.edit().clear().commit()
    }

    // --- Pull cursor -----------------------------------------------------------------------------

    @Test
    fun `a device that has never pulled starts at zero`() =
        runBlocking {
            assertEquals(0L, cursor().pullSince())
        }

    @Test
    fun `advancing persists the new position`() =
        runBlocking {
            val cursor = cursor()

            cursor.advancePullCursor(1_700_000_000_000L)

            assertEquals(1_700_000_000_000L, cursor.pullSince())
        }

    @Test
    fun `the cursor never rewinds`() =
        runBlocking {
            val cursor = cursor()
            cursor.advancePullCursor(500L)

            cursor.advancePullCursor(400L)
            assertEquals(500L, cursor.pullSince())

            // Equal is not forward either — re-applying a page must not re-open a window.
            cursor.advancePullCursor(500L)
            assertEquals(500L, cursor.pullSince())

            cursor.advancePullCursor(501L)
            assertEquals(501L, cursor.pullSince())
        }

    @Test
    fun `the position outlives the instance that wrote it`() =
        runBlocking {
            cursor().advancePullCursor(900L)

            // A worker run is a fresh object graph; the cursor is only useful if it survives that.
            assertEquals(900L, cursor().pullSince())
        }

    // --- Compatibility with installs that predate the seam ---------------------------------------

    @Test
    fun `a cursor left by the old pull worker is picked up, not reset`() =
        runBlocking {
            legacyPrefs.edit().putLong("last_pull_sync_at", 1_234_567L).commit()

            // Reading 0 here would re-pull the entire feed on first launch after the upgrade.
            assertEquals(1_234_567L, cursor().pullSince())
        }

    @Test
    fun `a client id left by the old sync path is kept`() =
        runBlocking {
            legacyPrefs.edit().putString("client_id", "id-from-before-the-seam").commit()

            // A new id would make the server treat this install as a second device.
            assertEquals("id-from-before-the-seam", cursor().clientId())
        }

    @Test
    fun `advancing writes back to the key the old worker read`() =
        runBlocking {
            cursor().advancePullCursor(4_242L)

            assertEquals(4_242L, legacyPrefs.getLong("last_pull_sync_at", 0L))
        }

    // --- Client id -------------------------------------------------------------------------------

    @Test
    fun `the client id is minted once and then never changes`() =
        runBlocking {
            val minted = cursor().clientId()

            assertTrue("expected a UUID, got '$minted'", runCatching { UUID.fromString(minted) }.isSuccess)
            assertEquals(minted, cursor().clientId())
            assertEquals(minted, legacyPrefs.getString("client_id", null))
        }

    @Test
    fun `a fresh install mints its own id`() =
        runBlocking {
            val first = cursor().clientId()
            legacyPrefs.edit().clear().commit()

            assertNotEquals(first, cursor().clientId())
        }
}
