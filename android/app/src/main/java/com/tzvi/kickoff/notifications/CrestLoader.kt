package com.tzvi.kickoff.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Team crests, sized and cached for use inside notifications.
 *
 * Three constraints shape this:
 *  - hardware bitmaps cannot be parcelled, so decoding must opt out of them;
 *  - the system strips a RemoteViews hierarchy whose bitmaps exceed 5MB, silently
 *    leaving a blank card, so crests are hard-capped at [CREST_PX];
 *  - a match posts an update every few seconds for ninety minutes, so the decoded
 *    bitmap is cached per team and the *same instance* is reused on every update
 *    (identical bitmaps are also deduped inside the RemoteViews bitmap cache).
 */
@Singleton
class CrestLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) {
    private val cache = LruCache<String, Bitmap>(MAX_CACHED)

    suspend fun load(url: String?, fallbackText: String): Bitmap = withContext(Dispatchers.IO) {
        val key = cacheKey(url, fallbackText)
        cache.get(key)?.let { return@withContext it }

        val bitmap = url?.let { fetch(it) } ?: placeholder(fallbackText)
        cache.put(key, bitmap)
        bitmap
    }

    /**
     * What is already decoded, for the caller that must not wait.
     *
     * A live card is posted every few seconds and cannot be held behind a crest CDN, so
     * the posting path asks for this first and only falls back to [load] - on its own
     * deadline - on the one update per match that can actually miss.
     */
    fun cached(url: String?, fallbackText: String): Bitmap? =
        cache.get(cacheKey(url, fallbackText))

    private fun cacheKey(url: String?, fallbackText: String): String =
        url ?: "fallback:$fallbackText"

    suspend fun loadIcon(url: String?, fallbackText: String): IconCompat =
        IconCompat.createWithBitmap(load(url, fallbackText))

    private suspend fun fetch(url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            // A HARDWARE-config bitmap throws when parcelled into a RemoteViews.
            .allowHardware(false)
            .size(CREST_PX, CREST_PX)
            .build()
        imageLoader.execute(request).image?.toBitmap(CREST_PX, CREST_PX)
    }.getOrNull()

    /** A flat monogram disc, so a missing crest still reads as a team rather than a hole. */
    private fun placeholder(text: String): Bitmap {
        val size = CREST_PX
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_BG }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        val label = text.take(3).uppercase()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * if (label.length >= 3) 0.34f else 0.44f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(label, size / 2f, size / 2f + bounds.height() / 2f, textPaint)
        return bitmap
    }

    private fun createBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun clear() = cache.evictAll()

    private companion object {
        /**
         * The largest a crest is ever drawn is the scoreboard's 40dp slot, which is
         * 120px at xxhdpi; 128px covers that without upscaling, and covers the 24dp each
         * crest gets inside the live card's composed pair twice over. Two of these is
         * ~131KB against a 5MB budget, and they are reused for the whole match.
         */
        const val CREST_PX = 128
        const val MAX_CACHED = 24
        const val PLACEHOLDER_BG = 0xFF3A4A3C.toInt()
    }
}
