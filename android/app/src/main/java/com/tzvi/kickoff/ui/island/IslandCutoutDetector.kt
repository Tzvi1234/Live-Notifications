package com.tzvi.kickoff.ui.island

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.tzvi.kickoff.core.model.IslandCutout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns Android's `DisplayCutout` into a first guess at where the camera is.
 *
 * The API only exists from Android 9, only reports anything on hardware that actually
 * has a cutout, and describes the region the system reserves rather than the lens - so
 * everything here is a suggestion the user then nudges by eye. A null result is normal
 * and means "no idea", not "broken".
 */
object IslandCutoutDetector {

    fun detect(view: View): IslandCutout? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val rect = Api28Impl.topCutoutRect(view) ?: return null
        val width = view.rootView.width.takeIf { it > 0 }
            ?: view.resources.displayMetrics.widthPixels.takeIf { it > 0 }
            ?: return null
        val density = view.resources.displayMetrics.density
        if (density <= 0f) return null

        return IslandCutout(
            enabled = true,
            centerXFraction = rect.exactCenterX() / width,
            centerYDp = (rect.exactCenterY() / density).roundToInt(),
            // Square holes and pills alike: the longer side is what has to be cleared.
            diameterDp = (max(rect.width(), rect.height()) / density).roundToInt(),
        ).sanitised()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private object Api28Impl {
        fun topCutoutRect(view: View): Rect? {
            val cutout = view.rootWindowInsets?.displayCutout ?: return null
            val displayHeight = view.rootView.height.takeIf { it > 0 }
                ?: view.resources.displayMetrics.heightPixels
            // A display can report several rects - a waterfall edge down each side, a
            // corner radius. The camera is one that touches the top edge, and where a
            // phone reports both a wide notch and the hole inside it, it is the smaller.
            return cutout.boundingRects
                .filter { it.top < displayHeight / 4 && !it.isEmpty }
                .minByOrNull { it.width().toLong() * it.height().toLong() }
        }
    }
}
