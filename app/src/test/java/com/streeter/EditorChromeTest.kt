package com.streeter

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.streeter.ui.editpoints.EditorChrome
import com.streeter.ui.editpoints.EditorMode
import com.streeter.ui.editpoints.MapInsets
import com.streeter.ui.editpoints.PointPan
import com.streeter.ui.editpoints.cameraCentreAfterPan
import com.streeter.ui.editpoints.editorMapInsets
import com.streeter.ui.editpoints.revealMarginPx
import com.streeter.ui.editpoints.sheetMetrics
import com.streeter.ui.editpoints.uncoveredCentre
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

    // --- What edit mode covers instead ------------------------------------------------------

    @Test
    fun `edit mode reserves the Done-Cancel bar instead of the sheet and pill`() {
        val insets = editorMapInsets(statusBarPx = 0f, navigationBarPx = 96f, density = density, mode = EditorMode.EDITING)

        // 88dp bar + 96px navigation bar. No sheet and no pill: edit mode hides both.
        assertEquals(176f + 96f, insets.bottom)
    }

    @Test
    fun `edit mode leaves the top inset alone — the app bar stays`() {
        val browsing = editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density)
        val editing = editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density, mode = EditorMode.EDITING)

        assertEquals(browsing.top, editing.top)
    }

    @Test
    fun `edit mode frees the map the sheet and pill were covering`() {
        val browsing = editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density)
        val editing = editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density, mode = EditorMode.EDITING)

        assertTrue(editing.bottom < browsing.bottom)
    }

    @Test
    fun `browsing is what the insets describe when no mode is named`() {
        assertEquals(
            editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density, mode = EditorMode.BROWSING),
            editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density),
        )
    }

    // --- Where the crosshair sits -------------------------------------------------------------

    @Test
    fun `the crosshair sits in the middle of what the chrome leaves uncovered`() {
        val centre = uncoveredCentre(1000f, 2000f, MapInsets(top = 200f, bottom = 400f))

        assertEquals(500f, centre.xPx)
        assertEquals(900f, centre.yPx) // midway between 200 and 1600
    }

    @Test
    fun `an uninset map puts the crosshair at the viewport centre`() {
        val centre = uncoveredCentre(1000f, 2000f, MapInsets())

        assertEquals(500f, centre.xPx)
        assertEquals(1000f, centre.yPx)
    }

    @Test
    fun `the crosshair is above the viewport centre when more chrome sits below than above`() {
        val insets = editorMapInsets(statusBarPx = 60f, navigationBarPx = 96f, density = density, mode = EditorMode.EDITING)

        val centre = uncoveredCentre(1000f, 2000f, insets)

        assertTrue(centre.yPx < 1000f)
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

    @Test
    fun `the edit-mode bar inset is the height of the bar the screen actually lays out`() {
        // A row of 48dp buttons with its own padding, sitting a gap above the navigation bar,
        // and EditPointsScreen lays it out from these same constants.
        assertEquals(88.dp, EditorChrome.EditBarInset)
    }

    @Test
    fun `adding the edit button leaves the pill the height the camera assumes`() {
        // The pill grew a fourth button sideways, not taller — ADR-0007's reveal logic consumes
        // this inset, and it must not shift under it.
        assertEquals(72.dp, EditorChrome.PillInset)
    }
}
