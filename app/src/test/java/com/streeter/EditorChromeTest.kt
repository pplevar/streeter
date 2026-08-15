package com.streeter

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.streeter.ui.editpoints.EditorChrome
import com.streeter.ui.editpoints.PointPan
import com.streeter.ui.editpoints.cameraCentreAfterPan
import com.streeter.ui.editpoints.editorMapInsets
import com.streeter.ui.editpoints.revealMarginPx
import com.streeter.ui.editpoints.sheetMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for the points editor's chrome geometry (issue #55).
 *
 * These are the decisions ADR-0005 and ADR-0007 describe that used to live inline in
 * `EditPointsScreen` and could not be reached by a test: which insets the reveal calculation
 * is given, how chrome dimensions become pixels, and how a pan offset becomes a camera target.
 */
class EditorChromeTest {
    /** A plain 2x screen, so a dp value's pixel value is easy to read in the assertions. */
    private val density = Density(density = 2f)

    // --- The sheet's two resting heights -------------------------------------------------

    @Test
    fun `the expanded sheet takes a fixed fraction of the screen`() {
        val metrics = sheetMetrics(screenHeight = 800.dp, density = density)

        // 55% of 800dp = 440dp, inside the clamp, at 2px per dp.
        assertEquals(880f, metrics.expandedPx)
    }

    @Test
    fun `a tall screen does not push the sheet past its maximum`() {
        val metrics = sheetMetrics(screenHeight = 2000.dp, density = density)

        assertEquals(960f, metrics.expandedPx) // 480dp
    }

    @Test
    fun `a short screen does not shrink the sheet below its minimum`() {
        val metrics = sheetMetrics(screenHeight = 400.dp, density = density)

        assertEquals(560f, metrics.expandedPx) // 280dp
    }

    @Test
    fun `the peek height is the same content height whatever the screen`() {
        assertEquals(192f, sheetMetrics(400.dp, density).peekPx) // 96dp
        assertEquals(192f, sheetMetrics(2000.dp, density).peekPx)
    }

    // --- Which snap a dragged sheet belongs to --------------------------------------------

    @Test
    fun `a sheet resting at peek is collapsed and one resting at full height is expanded`() {
        val metrics = sheetMetrics(screenHeight = 800.dp, density = density)

        assertFalse(metrics.isExpanded(metrics.peekPx))
        assertTrue(metrics.isExpanded(metrics.expandedPx))
    }

    @Test
    fun `a sheet dragged past the midpoint counts as expanded`() {
        val metrics = sheetMetrics(screenHeight = 800.dp, density = density)
        val midpoint = (metrics.peekPx + metrics.expandedPx) / 2f

        assertFalse(metrics.isExpanded(midpoint))
        assertTrue(metrics.isExpanded(midpoint + 1f))
        assertFalse(metrics.isExpanded(midpoint - 1f))
    }

    // --- The insets the reveal calculation is given ----------------------------------------

    @Test
    fun `the top inset is the status bar plus the app bar over it`() {
        val insets = editorMapInsets(statusBarPx = 60f, navigationBarPx = 0f, density = density)

        assertEquals(60f + 128f, insets.top) // status bar + 64dp app bar
    }

    @Test
    fun `the bottom inset covers the sheet at peek, the navigation bar and the pill above it`() {
        val insets = editorMapInsets(statusBarPx = 0f, navigationBarPx = 96f, density = density)

        // 96dp peek + 96px navigation bar + 72dp pill.
        assertEquals(192f + 96f + 144f, insets.bottom)
    }

    @Test
    fun `three-button navigation reserves more of the map than gesture navigation`() {
        val gesture = editorMapInsets(statusBarPx = 60f, navigationBarPx = 32f, density = density)
        val threeButton = editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density)

        assertEquals(64f, threeButton.bottom - gesture.bottom)
        assertEquals(gesture.top, threeButton.top)
    }

    @Test
    fun `the map is never inset sideways`() {
        val insets = editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density)

        assertEquals(0f, insets.left)
        assertEquals(0f, insets.right)
    }

    @Test
    fun `the reveal margin is the same breathing room whatever the screen density`() {
        assertEquals(48f, revealMarginPx(density)) // 24dp
        assertEquals(72f, revealMarginPx(Density(density = 3f)))
    }

    // --- Turning a pan offset into a camera target -----------------------------------------

    @Test
    fun `moving the point right moves the camera centre left by the same amount`() {
        val centre = cameraCentreAfterPan(PointPan(dxPx = 50f, dyPx = 0f), 1000f, 2000f)

        assertEquals(450f, centre.xPx)
        assertEquals(1000f, centre.yPx)
    }

    @Test
    fun `moving the point up moves the camera centre down by the same amount`() {
        val centre = cameraCentreAfterPan(PointPan(dxPx = 0f, dyPx = -200f), 1000f, 2000f)

        assertEquals(500f, centre.xPx)
        assertEquals(1200f, centre.yPx)
    }

    @Test
    fun `a pan on both axes is inverted on both axes`() {
        val centre = cameraCentreAfterPan(PointPan(dxPx = -80f, dyPx = 40f), 1000f, 2000f)

        assertEquals(580f, centre.xPx)
        assertEquals(960f, centre.yPx)
    }

    // --- The hand-measured constants still describe the chrome ------------------------------

    @Test
    fun `the pill inset is the height of the pill the screen actually lays out`() {
        // The pill is a row of icon buttons with its own padding, sitting a gap above the sheet,
        // and EditPointsScreen lays it out from these same constants. Resize any of them and
        // this hand-measured total no longer holds.
        assertEquals(72.dp, EditorChrome.PillInset)
    }

    @Test
    fun `the top bar inset is the height the screen gives its app bar`() {
        // EditPointsScreen passes this to TopAppBar as its expanded height, so the bar the user
        // sees and the inset the camera is given cannot drift apart.
        assertEquals(64.dp, EditorChrome.TopBarHeight)
    }
}
