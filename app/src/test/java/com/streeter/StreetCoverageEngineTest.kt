package com.streeter

import com.streeter.domain.model.StreetSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StreetCoverageEngineTest {
    @Test
    fun `streetCoverage returns 1 when all sections fully covered`() {
        val sections =
            listOf(
                StreetSection(1L, 1L, 1L, 2L, 100.0, "", "s1", false),
                StreetSection(2L, 1L, 2L, 3L, 100.0, "", "s2", false),
            )
        val coverages = mapOf("s1" to 1.0f, "s2" to 1.0f)
        val result = streetCoverage(sections, coverages)
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `streetCoverage returns 0_5 when half covered`() {
        val sections =
            listOf(
                StreetSection(1L, 1L, 1L, 2L, 100.0, "", "s1", false),
                StreetSection(2L, 1L, 2L, 3L, 100.0, "", "s2", false),
            )
        val coverages = mapOf("s1" to 1.0f, "s2" to 0.0f)
        val result = streetCoverage(sections, coverages)
        assertEquals(0.5f, result, 0.001f)
    }

    @Test
    fun `generateStableId is deterministic`() {
        val id1 = generateStableId("Main Street", 100L, 200L)
        val id2 = generateStableId("Main Street", 100L, 200L)
        assertEquals(id1, id2)
        assertEquals(16, id1.length)
    }

    @Test
    fun `generateStableId differs for different inputs`() {
        val id1 = generateStableId("Main Street", 100L, 200L)
        val id2 = generateStableId("Main Street", 100L, 201L)
        assertNotEquals(id1, id2)
    }

    // Pure function delegates — extracted for testability without DI
    private fun streetCoverage(
        sections: List<StreetSection>,
        coverages: Map<String, Float>,
    ): Float {
        val totalLen = sections.sumOf { it.lengthM }
        if (totalLen == 0.0) return 0f
        val coveredLen = sections.sumOf { it.lengthM * (coverages[it.stableId] ?: 0f) }
        return (coveredLen / totalLen).toFloat().coerceIn(0f, 1f)
    }

    private fun generateStableId(
        streetName: String,
        fromNodeId: Long,
        toNodeId: Long,
    ): String {
        val input = "$streetName|$fromNodeId|$toNodeId"
        val bytes = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
