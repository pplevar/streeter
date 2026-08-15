package com.streeter.domain.sync

/**
 * The two facts Sync carries between runs: where the pull feed resumes, and who this device is.
 *
 * The pull cursor is the newest `serverUpdatedAt` this device has taken in. Sending it back as
 * `since` is what makes pull an incremental feed rather than a full re-download; it is the sole
 * owner of that value, so no caller reads or writes it by any other route (issue #54).
 *
 * The client id identifies this install to the server across reinstalls of the same data — minted
 * once, on first use, and stable forever after.
 *
 * Deliberately free of Android types so the sync module can be exercised on the JVM.
 */
interface SyncCursor {
    /** Where the next pull resumes from; `0` until the first pull lands anything. */
    suspend fun pullSince(): Long

    /**
     * Move the cursor forward to [serverUpdatedAt]. Never moves backwards: a lower value is
     * ignored, so an out-of-order or partially-applied page cannot rewind the feed.
     */
    suspend fun advancePullCursor(serverUpdatedAt: Long)

    /** This device's stable client id, minted on first call. */
    suspend fun clientId(): String
}
