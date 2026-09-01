package com.tzvi.kickoff.core.model

/**
 * Where this phone's front camera sits, so the island can lay its content out *around*
 * the hole instead of behind it.
 *
 * Android reports a `DisplayCutout` and that is what the "detect" button reads, but the
 * reported bounding box is the region the system reserves, not the lens: on a lot of
 * phones it is a few dp bigger, off-centre, or absent entirely under a software overlay.
 * So the numbers stay editable and the calibration screen shows a ring the user lines up
 * against the real thing by eye. Detection is the starting point, not the answer.
 */
data class IslandCutout(
    /** Off until the user has actually calibrated; the island then floats as one pill. */
    val enabled: Boolean = false,
    /** Horizontal centre as a fraction of the screen width: 0 is the left edge, 1 the right. */
    val centerXFraction: Float = 0.5f,
    /** Vertical centre, in dp measured from the very top of the display. */
    val centerYDp: Int = 26,
    /** Diameter of the circle to keep clear, in dp. */
    val diameterDp: Int = 34,
) {
    /** Total width the island must leave empty: the hole plus breathing room either side. */
    val clearanceDp: Int get() = diameterDp + 2 * CLEARANCE_DP

    fun sanitised(): IslandCutout = copy(
        centerXFraction = centerXFraction.coerceIn(0f, 1f),
        centerYDp = centerYDp.coerceIn(MIN_CENTER_Y, MAX_CENTER_Y),
        diameterDp = diameterDp.coerceIn(MIN_DIAMETER, MAX_DIAMETER),
    )

    companion object {
        /** What every device gets until somebody calibrates: a centred, disabled guess. */
        val Unset = IslandCutout()

        const val MIN_CENTER_Y = 8
        const val MAX_CENTER_Y = 96
        const val MIN_DIAMETER = 14
        const val MAX_DIAMETER = 72

        /** Gap left between the circle and the nearest crest or digit. */
        const val CLEARANCE_DP = 9
    }
}
