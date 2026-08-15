package com.streeter

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.streeter.data.local.StreeterDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 7→8 drops `walks.lastPullSyncAt` — the per-walk pull cursor that lost to `SyncCursor`
 * (issue #54). It is the one schema change in that work and it runs unattended on every existing
 * install, so it is pinned here against a real SQLite: the column goes, everything else stays, and
 * the walks a user already recorded survive intact.
 *
 * `exportSchema = false`, so Room cannot validate the post-drop schema for us. The fixture closes
 * that gap itself: the v7 table is asserted to be the current `walks` shape plus the doomed column
 * before the migration runs, so a later entity change cannot silently drift this test into
 * testing a schema the app no longer has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalkCursorColumnMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-7-8-test.db"
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        context.deleteDatabase(dbName)
    }

    /** The `walks` columns Room creates today, straight from the current entity. */
    private fun currentWalkColumns(): Set<String> {
        val db =
            Room.inMemoryDatabaseBuilder(context, StreeterDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return try {
            columnsOf(db.openHelper.writableDatabase)
        } finally {
            db.close()
        }
    }

    private fun columnsOf(db: SupportSQLiteDatabase): Set<String> =
        db.query("PRAGMA table_info(`walks`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    /**
     * Opens a database at schema version 7: the `walks` table exactly as migrations 1→7 left it,
     * `lastPullSyncAt` included.
     */
    private fun openV7Database(): SupportSQLiteDatabase {
        context.deleteDatabase(dbName)
        val callback =
            object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `walks` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `title` TEXT,
                            `date` INTEGER NOT NULL,
                            `durationMs` INTEGER NOT NULL,
                            `distanceM` REAL NOT NULL,
                            `status` TEXT NOT NULL,
                            `source` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `syncStatus` TEXT NOT NULL,
                            `serverWalkId` INTEGER,
                            `lastPullSyncAt` INTEGER,
                            `gpsTraceSyncedAt` INTEGER,
                            `lastResumedAt` INTEGER,
                            `isPaused` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            }
        val created =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(callback)
                    .build(),
            )
        helper = created
        return created.writableDatabase
    }

    private fun seedWalk(
        db: SupportSQLiteDatabase,
        id: Long,
        lastPullSyncAt: Long?,
    ) {
        db.insert(
            "walks",
            android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("id", id)
                put("title", "Evening loop $id")
                put("date", 1_000L + id)
                put("durationMs", 60_000L)
                put("distanceM", 1234.5)
                put("status", "COMPLETED")
                put("source", "RECORDED")
                put("createdAt", 500L)
                put("updatedAt", 900L)
                put("syncStatus", "SYNCED")
                put("serverWalkId", 42L + id)
                put("lastPullSyncAt", lastPullSyncAt)
                put("gpsTraceSyncedAt", 800L)
                put("lastResumedAt", null as Long?)
                put("isPaused", 0)
            },
        )
    }

    @Test
    fun `the v7 fixture is the current walks shape plus the doomed column`() {
        val db = openV7Database()

        assertEquals(currentWalkColumns() + "lastPullSyncAt", columnsOf(db))
    }

    @Test
    fun `migrating drops lastPullSyncAt and leaves every other column standing`() {
        val db = openV7Database()

        StreeterDatabase.MIGRATION_7_8.migrate(db)

        val columns = columnsOf(db)
        assertFalse("lastPullSyncAt survived the migration", "lastPullSyncAt" in columns)
        assertEquals(currentWalkColumns(), columns)
    }

    @Test
    fun `walks recorded before the migration survive it intact`() {
        val db = openV7Database()
        seedWalk(db, id = 1L, lastPullSyncAt = 1_700_000_000_000L)
        seedWalk(db, id = 2L, lastPullSyncAt = null)

        StreeterDatabase.MIGRATION_7_8.migrate(db)

        db.query("SELECT id, title, serverWalkId, gpsTraceSyncedAt, updatedAt FROM walks ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)

            cursor.moveToFirst()
            assertEquals(1L, cursor.getLong(0))
            assertEquals("Evening loop 1", cursor.getString(1))
            // The server identity a walk earned is exactly what must not be lost here (issue #52).
            assertEquals(43L, cursor.getLong(2))
            assertEquals(800L, cursor.getLong(3))
            assertEquals(900L, cursor.getLong(4))

            cursor.moveToNext()
            assertEquals(2L, cursor.getLong(0))
            assertEquals("Evening loop 2", cursor.getString(1))
            assertEquals(44L, cursor.getLong(2))
        }
    }

    @Test
    fun `the migration is registered under the version it upgrades to`() {
        // A migration the database builder never sees is a destructive fallback waiting to happen.
        assertEquals(7, StreeterDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, StreeterDatabase.MIGRATION_7_8.endVersion)
    }
}
