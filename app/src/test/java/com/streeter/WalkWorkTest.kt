package com.streeter

import com.streeter.domain.work.WalkWork
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the unique-work naming contract. A single Stop cancels Calculation by name,
 * so every call site must agree on these exact strings (issue #15).
 */
class WalkWorkTest {
    @Test
    fun `calculation work is named match underscore walkId`() {
        assertEquals("match_42", WalkWork.calculationName(42L))
    }

    @Test
    fun `sync work is named sync underscore walkId`() {
        assertEquals("sync_42", WalkWork.syncName(42L))
    }
}
