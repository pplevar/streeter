package com.streeter.ui.editpoints

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The points editor's chrome dimensions, and the geometry derived from them.
 *
 * ADR-0005 (edge-to-edge inset policy) and ADR-0007 (selection highlights, it does not
 * navigate) both turn on where map content sits relative to this chrome. Everything here is
 * `@Composable`-free so the policy is enforced by JVM tests rather than by measurement on a
 * device: `EditPointsScreen` reads `WindowInsets`, hands the raw system insets to these
 * functions and applies what it gets back.
 *
 * The constants are shared with the composables that lay the chrome out, so a chrome change
 * moves the inset the camera is given with it.
 */
object EditorChrome {
    /** Content height of the sheet at peek. Unchanged by navigation mode (ADR-0005). */
    val SheetPeekHeight = 96.dp

    /** Share of the screen the expanded sheet takes, before clamping. */
    const val SHEET_EXPANDED_FRACTION = 0.55f

    val SheetExpandedHeightMin = 280.dp
    val SheetExpandedHeightMax = 480.dp

    /** Touch target of each of the pill's three icon buttons. */
    val PillButtonSize = 48.dp

    /** Padding inside the pill, around its buttons. */
    val PillPadding = 4.dp

    /** Gap the pill keeps above the sheet it is stacked on (ADR-0005 rule 4). */
    val PillGap = 16.dp

    /** Height the prev/delete/next pill and its gap claim above the sheet. */
    val PillInset = PillButtonSize + PillPadding * 2 + PillGap

    /** Height of the top app bar; the status-bar inset is added on top of it. */
    val TopBarHeight = 64.dp

    /** Breathing room kept between a revealed point and the edge of the uncovered map area. */
    val RevealMargin = 24.dp

    /** The expanded sheet's content height on a screen [screenHeight] tall. */
    fun sheetExpandedHeight(screenHeight: Dp): Dp =
        (screenHeight * SHEET_EXPANDED_FRACTION)
            .coerceIn(SheetExpandedHeightMin, SheetExpandedHeightMax)
}

/**
 * The sheet's two resting heights in pixels, and which of them a given height belongs to.
 * These are *content* heights: the sheet paints taller than this, by the navigation-bar inset
 * (ADR-0005 rule 1).
 */
data class SheetMetrics(
    val peekPx: Float,
    val expandedPx: Float,
) {
    /** A sheet dragged past the midpoint between the two snaps settles as expanded. */
    fun isExpanded(heightPx: Float): Boolean = heightPx > (peekPx + expandedPx) / 2f
}

/** The sheet's resting heights, in pixels, on a screen [screenHeight] tall. */
fun sheetMetrics(
    screenHeight: Dp,
    density: Density,
): SheetMetrics =
    with(density) {
        SheetMetrics(
            peekPx = EditorChrome.SheetPeekHeight.toPx(),
            expandedPx = EditorChrome.sheetExpandedHeight(screenHeight).toPx(),
        )
    }

/**
 * The map edges the editor's chrome covers when a point is selected, for [panToReveal].
 *
 * The sheet snaps to peek on every selection, so peek — not the sheet's current height — is
 * what the marker must clear, with the pill above it and the navigation bar below. The top is
 * the status bar plus the app bar drawn over it. Nothing covers the map sideways.
 */
fun editorMapInsets(
    statusBarPx: Float,
    navigationBarPx: Float,
    density: Density,
): MapInsets =
    with(density) {
        MapInsets(
            top = statusBarPx + EditorChrome.TopBarHeight.toPx(),
            bottom = EditorChrome.SheetPeekHeight.toPx() + navigationBarPx + EditorChrome.PillInset.toPx(),
        )
    }

/** A point on the map view, in pixels from its top-left corner. */
data class ScreenPoint(
    val xPx: Float,
    val yPx: Float,
)

/**
 * Where the camera's centre must land to move the selected point by [pan] on screen.
 *
 * Moving the point by (dx, dy) means moving the camera the opposite way, so the offset is
 * subtracted from the viewport's centre. The caller reads the result back through the map's
 * projection to get a coordinate.
 */
fun cameraCentreAfterPan(
    pan: PointPan,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): ScreenPoint =
    ScreenPoint(
        xPx = viewportWidthPx / 2f - pan.dxPx,
        yPx = viewportHeightPx / 2f - pan.dyPx,
    )
