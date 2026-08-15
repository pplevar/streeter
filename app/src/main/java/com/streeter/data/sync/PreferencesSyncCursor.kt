package com.streeter.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.streeter.domain.sync.SyncCursor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [SyncCursor], backed by app-private [SharedPreferences].
 *
 * The file and key names are the ones the pull worker and sync repository used before the seam
 * existed, so installs that already have a cursor and a client id keep them.
 */
@Singleton
class PreferencesSyncCursor
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SyncCursor {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Preferences I/O is blocking, and the seam's callers are coroutines: honour the `suspend`.
        override suspend fun pullSince(): Long =
            withContext(Dispatchers.IO) {
                prefs.getLong(KEY_PULL_CURSOR, 0L)
            }

        override suspend fun advancePullCursor(serverUpdatedAt: Long) =
            withContext(Dispatchers.IO) {
                if (serverUpdatedAt > prefs.getLong(KEY_PULL_CURSOR, 0L)) {
                    prefs.edit { putLong(KEY_PULL_CURSOR, serverUpdatedAt) }
                }
            }

        override suspend fun clientId(): String =
            withContext(Dispatchers.IO) {
                prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
                    prefs.edit { putString(KEY_CLIENT_ID, it) }
                }
            }

        private companion object {
            const val PREFS_NAME = "sync_prefs"
            const val KEY_PULL_CURSOR = "last_pull_sync_at"
            const val KEY_CLIENT_ID = "client_id"
        }
    }
