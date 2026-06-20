package com.streeter.data.remote.auth

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the shared bearer token used to authenticate sync requests (issue #17, ADR-0002).
 *
 * The token is user-entered in Settings so it can be rotated without a rebuild, and is read on
 * every request via the Ktor `defaultRequest { }` block. Backed by plain app-private
 * [SharedPreferences] (excluded from auto-backup) — no User/Account concept, purely a transport gate.
 */
@Singleton
class SyncAuthTokenStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** The current bearer token, or blank when none is set. */
        var token: String
            get() = prefs.getString(KEY_TOKEN, "") ?: ""
            set(value) {
                prefs.edit().putString(KEY_TOKEN, value).apply()
            }

        companion object {
            const val PREFS_NAME = "streeter_sync_auth"
            const val KEY_TOKEN = "sync_auth_token"
        }
    }
