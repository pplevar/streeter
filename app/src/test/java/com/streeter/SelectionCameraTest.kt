package com.streeter

import com.streeter.ui.editpoints.MapInsets
import com.streeter.ui.editpoints.panToReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioral spec for the points editor's camera rule (issue #49).
 *
 * Selection highlights, it does not navigate: the camera moves only when the selected point
 * would otherwise be invisible, and it never changes zoom.
 */
class SelectionCameraTest {
    private val width = 1000f
    private val height = 2000f

    /** Sheet at the bottom, top bar at the top — the editor's real chrome, roughly. */
    private val chrome = MapInsets(top = 200f, bottom = 500f)

    private fun pan(
        x: Float,
        y: Float,
        insets: MapInsets = MapInsets(),
        marginPx: Float = 0f,
    ) = panToReveal(x, y, width, height, insets, marginPx)

    @Test
    fun `a point in the middle of an unobstructed map does not move the camera`() {
        assertNull(pan(x = 500f, y = 1000f))
    }

    @Test
    fun `a point inside the uncovered region does not move the camera`() {
        assertNull(pan(x = 500f, y = 1000f, insets = chrome))
    }

    @Test
    fun `a point off the left edge is panned right to the edge of the region`() {
        val result = pan(x = -50f, y = 1000f)

        assertEquals(50f, result?.dxPx)
        assertEquals(0f, result?.dyPx)
    }

    @Test
    fun `a point off the right edge is panned left to the edge of the region`() {
        val result = pan(x = 1200f, y = 1000f)

        assertEquals(-200f, result?.dxPx)
        assertEquals(0f, result?.dyPx)
    }

    @Test
    fun `a point off the top edge is panned down to the edge of the region`() {
        val result = pan(x = 500f, y = -80f)

        assertEquals(0f, result?.dxPx)
        assertEquals(80f, result?.dyPx)
    }

    @Test
    fun `a point off the bottom edge is panned up to the edge of the region`() {
        val result = pan(x = 500f, y = 2300f)

        assertEquals(0f, result?.dxPx)
        assertEquals(-300f, result?.dyPx)
    }

    @Test
    fun `a point off two edges is panned on both axes at once`() {
        val result = pan(x = -50f, y = -80f)

        assertEquals(50f, result?.dxPx)
        assertEquals(80f, result?.dyPx)
    }

    @Test
    fun `a point on screen but underneath the bottom sheet is panned up`() {
        // y = 1700 is inside the viewport, but the sheet covers everything below 1500.
        val result = pan(x = 500f, y = 1700f, insets = chrome)

        assertEquals(-200f, result?.dyPx)
    }

    @Test
    fun `a point on screen but underneath the top bar is panned down`() {
        val result = pan(x = 500f, y = 120f, insets = chrome)

        assertEquals(80f, result?.dyPx)
    }

    @Test
    fun `the margin keeps a revealed point clear of the region's edge`() {
        val result = pan(x = 500f, y = 1700f, insets = chrome, marginPx = 40f)

        // Up to the sheet's top edge, plus the margin.
        assertEquals(-240f, result?.dyPx)
    }

    @Test
    fun `a point already inside the margin is left alone`() {
        assertNull(pan(x = 500f, y = 1400f, insets = chrome, marginPx = 40f))
    }

    @Test
    fun `a point exactly on the region's edge is left alone`() {
        assertNull(pan(x = 500f, y = 1500f, insets = chrome))
    }

    @Test
    fun `chrome that leaves no uncovered region still aims the camera at a point it can show`() {
        val squeezed = MapInsets(top = 1200f, bottom = 1200f)

        val result = pan(x = 500f, y = 100f, insets = squeezed)

        // No region is left to land in, so the point is brought to its midpoint rather than
        // being left off screen.
        assertNotNull(result)
        assertEquals(900f, result?.dyPx)
    }
}
