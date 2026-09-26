package com.nuvio.tv.ui.screens.player.autosync.bubble

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** A small, soft copy of the video behind the bubble, and where it was taken from (window px). */
internal class BubbleBackdropFrame(val image: ImageBitmap, val windowRect: Rect)

/** Where the bubble sits in the window, in px, before its animations move it. */
internal class BubbleWindowBounds {
    @Volatile
    var rect: Rect? = null

    /** How often to copy the video: while words show, and more rarely once only the droplet is left. */
    @Volatile
    var sampleIntervalMs: Long = BACKDROP_ACTIVE_INTERVAL_MS
}

/** TV boxes are weak: about 8 copies a second while words show, 2 once only the droplet is left. */
internal const val BACKDROP_ACTIVE_INTERVAL_MS = 125L
internal const val BACKDROP_IDLE_INTERVAL_MS = 500L

/** Protected (DRM) video can't be copied; stop trying after this many failures in a row. */
private const val MAX_FAILURES = 6

/**
 * Copies the patch of video behind the bubble from the player's SurfaceView (ExoPlayer and mpv
 * both draw into one) with PixelCopy, shrunk so it is already soft and cheap. Only runs while the
 * bubble is on screen and needs nothing from the player: it finds the video surface in the view
 * tree. A copy of part of a SurfaceView needs Android 8; older boxes get the plain frosted glass.
 */
@Composable
internal fun rememberVideoBackdrop(bounds: BubbleWindowBounds, marginPx: Float): State<BubbleBackdropFrame?> {
    val view = LocalView.current
    val frame = remember { mutableStateOf<BubbleBackdropFrame?>(null) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return frame
    LaunchedEffect(view) {
        val handler = Handler(Looper.getMainLooper())
        // Android 12+ blurs the copy on the GPU, so it can be a little sharper; older versions
        // rely on the shrink alone for the softness.
        val shrink = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 4 else 6
        // Two reused buffers: one on screen while the next copy lands in the other.
        val buffers = arrayOfNulls<Bitmap>(2)
        var next = 0
        var failures = 0
        var surface: SurfaceView? = null
        var lookups = 0
        val location = IntArray(2)
        while (isActive && failures < MAX_FAILURES) {
            delay(bounds.sampleIntervalMs)
            if (surface == null || !surface.isAttachedToWindow || lookups++ % 16 == 0) {
                surface = findVideoSurface(view.rootView)
            }
            val target = surface ?: continue
            val holderSurface = target.holder.surface
            if (holderSurface == null || !holderSurface.isValid || target.width <= 0) continue
            val rect = bounds.rect ?: continue
            target.getLocationInWindow(location)
            val left = max(0, (rect.left - marginPx).roundToInt() - location[0])
            val top = max(0, (rect.top - marginPx).roundToInt() - location[1])
            val right = minOf(target.width, (rect.right + marginPx).roundToInt() - location[0])
            val bottom = minOf(target.height, (rect.bottom + marginPx).roundToInt() - location[1])
            if (right - left < shrink || bottom - top < shrink) continue
            val width = (right - left) / shrink
            val height = (bottom - top) / shrink
            val buffer = buffers[next]?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                copyVideo(target, android.graphics.Rect(left, top, right, bottom), buffer, handler)
            } else {
                PixelCopy.ERROR_SOURCE_INVALID
            }
            if (result == PixelCopy.SUCCESS) {
                failures = 0
                frame.value = BubbleBackdropFrame(
                    image = buffer.asImageBitmap(),
                    windowRect = Rect(
                        left = (left + location[0]).toFloat(),
                        top = (top + location[1]).toFloat(),
                        right = (right + location[0]).toFloat(),
                        bottom = (bottom + location[1]).toFloat(),
                    ),
                )
                next = 1 - next
            } else {
                failures++
            }
        }
        // Protected content or a surface that can't be read: plain frosted glass from here on.
        frame.value = null
    }
    return frame
}

/** Copies [source] of [surface] into [into], shrinking it to fit; returns PixelCopy's result. */
@RequiresApi(Build.VERSION_CODES.O)
private suspend fun copyVideo(
    surface: SurfaceView,
    source: android.graphics.Rect,
    into: Bitmap,
    handler: Handler,
): Int = suspendCancellableCoroutine { continuation ->
    try {
        PixelCopy.request(
            surface,
            source,
            into,
            { code -> if (continuation.isActive) continuation.resume(code) },
            handler,
        )
    } catch (error: IllegalArgumentException) {
        if (continuation.isActive) continuation.resume(PixelCopy.ERROR_SOURCE_INVALID)
    }
}

/**
 * The largest visible SurfaceView in the window: the video. GL surfaces (subtitle overlays and
 * the like) are only used when nothing else is there.
 */
private fun findVideoSurface(root: View): SurfaceView? {
    var best: SurfaceView? = null
    var bestArea = 0L
    var fallback: SurfaceView? = null
    var fallbackArea = 0L
    fun visit(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is SurfaceView) {
            if (!view.isShown) return
            val area = view.width.toLong() * view.height
            if (view is GLSurfaceView) {
                if (area > fallbackArea) {
                    fallback = view
                    fallbackArea = area
                }
            } else if (area > bestArea) {
                best = view
                bestArea = area
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
    }
    visit(root)
    return best ?: fallback
}
