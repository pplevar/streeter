package com.streeter.domain.time

import javax.inject.Inject

/**
 * The wall clock, behind a seam.
 *
 * Anything that decides *how long* something took — a walk's duration across pause and resume
 * above all — reads time through this, so the decision can be driven from a test instead of
 * depending on real elapsed seconds.
 */
fun interface Clock {
    /** Milliseconds since the Unix epoch, as [System.currentTimeMillis] reports them. */
    fun nowMillis(): Long
}

/** The real clock. */
class SystemClock
    @Inject
    constructor() : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }
