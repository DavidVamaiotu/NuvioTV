package com.nuvio.tv.ui.screens.player.autosync.bubble

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Sizes are the phone bubble's, scaled up about 1.6x so it reads from the couch.
private val GlassBody = Color(0xFF2A2A2E)
private val BubbleCorner = 36.dp
private val OrbSize = 48.dp
private val BubblePadding = 12.dp
/** How much video around the bubble is copied, so the edges have something to bend in. */
private val BackdropMargin = 18.dp
private val BackdropBlur = 7.dp
/** The glass edge: how wide it is, and how far beyond the bubble it reaches to bend light in. */
private val RefractionBand = 9.dp
private val RefractionReach = 12.dp
/** Above the player's title, seek bar and buttons while they show; else just inside the overscan. */
private val LiftWithControls = 200.dp
private const val TWO_PI = (2.0 * PI).toFloat()

/** How long the working bubble keeps its words before settling to just the droplet. */
private const val WORKING_LABEL_MS = 7_000L
/** A run that never reports back fades away after this. */
private const val WORKING_TIMEOUT_MS = 240_000L
private const val SUCCESS_HOLD_MS = 1_800L
/** A little longer than the phone: the explanation is read from across the room. */
private const val FAILURE_HOLD_MS = 8_000L

/** The theme colours the bubble tints itself with once a run ends. */
private class BubbleColors(val success: Color, val failure: Color)

/**
 * AutoSync's glass bubble, at the bottom centre of the player. Draws nothing, and runs no
 * animation, unless the setting is on and AutoSync has something to say. It is never focusable
 * and takes no input, so the remote always stays with the player; the failure card closes itself.
 */
@Composable
internal fun BoxScope.AutoSyncBubbleToastHost(controlsVisible: Boolean) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        AutoSyncBubbleToasts.ensureLoaded(context)
        AutoSyncBubbleToasts.attachHost()
        onDispose { AutoSyncBubbleToasts.detachHost() }
    }
    val enabled by AutoSyncBubbleToasts.enabled.collectAsState()
    val message by AutoSyncBubbleToasts.current.collectAsState()
    val current = message
    if (!enabled || current == null) return
    val safeBottom = NuvioTheme.spacing.screen.overscanVertical
    val lift = animateDpAsState(
        targetValue = if (controlsVisible) LiftWithControls else safeBottom,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "autoSyncBubbleLift",
    )
    val colors = BubbleColors(success = NuvioTheme.colors.Success, failure = NuvioTheme.colors.Error)
    key(current.session) {
        AutoSyncBubble(
            message = current,
            colors = colors,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2.74f)
                .offset { IntOffset(0, -lift.value.roundToPx()) },
        )
    }
}

@Composable
private fun AutoSyncBubble(message: AutoSyncBubbleMessage, colors: BubbleColors, modifier: Modifier) {
    val kind = message.kind
    val kindState = rememberUpdatedState(kind)
    val scope = rememberCoroutineScope()

    val appear = remember { Animatable(0f) }
    val settle = remember { Animatable(0f) } // 0 = flowing liquid, 1 = still glass
    val mark = remember { Animatable(0f) } // the check or the X draws itself
    val shake = remember { Animatable(0f) } // one small wobble on failure
    val melt = remember { Animatable(0f) } // success: the bubble draws itself in and fades
    val leave = remember { Animatable(0f) } // failure or timeout: sinks away
    val clock = remember { mutableFloatStateOf(0f) }
    val bubblePath = remember { Path() }
    val dropPath = remember { Path() }
    var labelVisible by remember { mutableStateOf(true) }
    var cardOpen by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val tint = animateColorAsState(targetValue = kind.color(colors), animationSpec = tween(480), label = "tint")
    val tinted = animateFloatAsState(
        targetValue = if (kind == AutoSyncBubbleKind.Working) 0f else 1f,
        animationSpec = tween(480),
        label = "tinted",
    )

    fun dismiss() {
        if (leaving) return
        leaving = true
        scope.launch {
            cardOpen = false
            labelVisible = false
            delay(240)
            leave.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
            AutoSyncBubbleToasts.finished(message.id)
        }
    }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 260f))
    }
    // The liquid clock: only ticks while the bubble is flowing, then stops for good.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (kindState.value == AutoSyncBubbleKind.Working || settle.value < 1f) {
            withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f * 0.6f }
        }
    }
    LaunchedEffect(message.id) {
        if (kind != AutoSyncBubbleKind.Working) return@LaunchedEffect
        labelVisible = true
        delay(WORKING_LABEL_MS)
        labelVisible = false
        delay(WORKING_TIMEOUT_MS - WORKING_LABEL_MS)
        dismiss()
    }
    LaunchedEffect(kind) {
        when (kind) {
            AutoSyncBubbleKind.Working -> Unit
            AutoSyncBubbleKind.Success -> {
                labelVisible = true
                settle.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 200f))
                mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(SUCCESS_HOLD_MS)
                labelVisible = false
                delay(380)
                leaving = true
                melt.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                AutoSyncBubbleToasts.finished(message.id)
            }
            AutoSyncBubbleKind.Failure -> {
                labelVisible = true
                settle.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 200f))
                launch { shake.animateTo(1f, tween(480)) }
                mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(160)
                cardOpen = true
                delay(FAILURE_HOLD_MS)
                dismiss()
            }
        }
    }

    val density = LocalDensity.current
    val bounds = remember { BubbleWindowBounds() }
    val backdrop = rememberVideoBackdrop(bounds, with(density) { BackdropMargin.toPx() })
    // Only flips when the video copy appears or goes away, not on every new copy.
    val overVideo = remember(backdrop) { derivedStateOf { backdrop.value != null } }
    val backdropPath = remember { Path() }
    val innerPath = remember { Path() }
    SideEffect {
        bounds.sampleIntervalMs =
            if (labelVisible || cardOpen) BACKDROP_ACTIVE_INTERVAL_MS else BACKDROP_IDLE_INTERVAL_MS
    }
    // Blurring on the GPU needs Android 12; older boxes rely on the shrunk copy's own softness.
    val blur: RenderEffect? = remember(density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = with(density) { BackdropBlur.toPx() }
            BlurEffect(radius, radius, TileMode.Clamp)
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
            .graphicsLayer {
                val a = appear.value
                val l = leave.value
                val m = melt.value
                val liquid = 1f - settle.value
                val t = clock.floatValue
                // Melting away: a touch wider and shorter as it draws in, like a drop settling.
                val squash = 0.04f * sin(m * PI.toFloat())
                val scale = (0.72f + 0.28f * a) * (1f - 0.25f * l) * (1f - 0.5f * m)
                scaleX = scale * (1f + squash)
                scaleY = scale * (1f - squash)
                alpha = a.coerceIn(0f, 1f) * (1f - l) * (1f - m)
                // While it works, the whole bubble drifts a little, as if floating.
                val wobble = sin(shake.value * PI.toFloat() * 3f) * (1f - shake.value) * 3.dp.toPx()
                translationX = sin(t * 1.1f) * 1.2.dp.toPx() * liquid + wobble
                translationY = (1f - a) * 30.dp.toPx() + (l + m * 0.5f) * 15.dp.toPx() +
                    sin(t * 1.6f + 0.8f) * 0.9.dp.toPx() * liquid
                transformOrigin = TransformOrigin(0.5f, 0.6f)
            },
    ) {
        // The video behind, softly blurred and bent at the edges like thick glass, clipped to
        // the bubble's (flowing) outline. Nothing is drawn while there is no copy of the video.
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    if (backdrop.value == null) return@drawWithContent
                    buildLiquidOutline(
                        path = backdropPath,
                        width = size.width,
                        height = size.height,
                        corner = BubbleCorner.toPx().coerceAtMost(size.minDimension / 2f),
                        amplitude = 0.9.dp.toPx() * (1f - settle.value),
                        swell = 0.008f * sin(clock.floatValue * 2.1f) * (1f - settle.value),
                        time = clock.floatValue,
                    )
                    clipPath(backdropPath) { this@drawWithContent.drawContent() }
                },
        ) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { renderEffect = blur }
                    .drawBehind { drawRefractedBackdrop(backdrop.value, bounds.rect, innerPath) },
            )
        }
        BubbleContent(
            message = message,
            labelVisible = labelVisible,
            cardOpen = cardOpen,
            modifier = Modifier.liquidGlass(
                path = bubblePath,
                clock = clock,
                settle = settle,
                tint = tint,
                tinted = tinted,
                overVideo = overVideo,
            ),
            dropletModifier = Modifier.droplet(
                path = dropPath,
                kind = kindState,
                clock = clock,
                settle = settle,
                tint = tint,
                tinted = tinted,
                mark = mark,
            ),
        )
    }
}

@Composable
private fun BubbleContent(
    message: AutoSyncBubbleMessage,
    labelVisible: Boolean,
    cardOpen: Boolean,
    modifier: Modifier,
    dropletModifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(BubblePadding),
    ) {
        Spacer(Modifier.size(OrbSize).then(dropletModifier))
        AnimatedVisibility(
            visible = labelVisible,
            enter = fadeIn(tween(240, delayMillis = 80)) +
                expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 360f), expandFrom = Alignment.Start),
            exit = fadeOut(tween(140)) +
                shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f), shrinkTowards = Alignment.Start),
        ) {
            BubbleLabel(message = message, cardOpen = cardOpen)
        }
    }
}

@Composable
private fun BubbleLabel(message: AutoSyncBubbleMessage, cardOpen: Boolean) {
    Row {
        Spacer(Modifier.width(16.dp))
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .heightIn(min = OrbSize)
                .widthIn(max = if (cardOpen) 480.dp else 400.dp)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f))
                .padding(end = 12.dp),
        ) {
            Text(
                text = "AutoSync",
                color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.6f),
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            )
            AnimatedContent(
                targetState = message.headline,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 3 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 3 }) using
                        SizeTransform(clip = false)
                },
                label = "autoSyncBubbleHeadline",
            ) { headline ->
                Text(
                    text = headline,
                    color = NuvioTheme.colors.TextPrimary,
                    fontSize = 21.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (cardOpen) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val detail = message.detail
            if (cardOpen && detail != null) {
                Text(
                    text = detail,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                    lineHeight = 25.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 3.dp),
                )
            }
        }
    }
}

private fun AutoSyncBubbleKind.color(colors: BubbleColors): Color = when (this) {
    AutoSyncBubbleKind.Working -> Color.White
    AutoSyncBubbleKind.Success -> colors.success
    AutoSyncBubbleKind.Failure -> colors.failure
}

/**
 * The glass body, drawn behind the bubble's content. Its brushes and strokes are built once per
 * size and tint (drawWithCache), so while the outline flows each frame only rebuilds the path.
 */
private fun Modifier.liquidGlass(
    path: Path,
    clock: FloatState,
    settle: Animatable<Float, *>,
    tint: State<Color>,
    tinted: State<Float>,
    overVideo: State<Boolean>,
): Modifier = drawWithCache {
    val video = overVideo.value
    val tintAmount = tinted.value
    val tintColor = tint.value
    val corner = BubbleCorner.toPx().coerceAtMost(size.minDimension / 2f)
    val radius = CornerRadius(corner)
    val shadowStep = 1.8.dp.toPx()
    val shadow = Color.Black.copy(alpha = 0.05f)
    val body = GlassBody.copy(alpha = if (video) 0.20f else 0.55f)
    val tintBrush = if (tintAmount > 0f) {
        Brush.horizontalGradient(
            0f to tintColor.copy(alpha = 0.22f * tintAmount),
            1f to Color.Transparent,
            endX = size.height * 3f,
        )
    } else {
        null
    }
    // Frost, brighter at the top where the light comes from.
    val frost = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (video) 0.16f else 0.22f),
        0.5f to Color.White.copy(alpha = if (video) 0.07f else 0.12f),
        1f to Color.White.copy(alpha = if (video) 0.10f else 0.14f),
    )
    // Rim: bright on the upper left, a softer second highlight on the lower right.
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.60f),
        0.3f to Color.White.copy(alpha = 0.14f),
        0.7f to Color.White.copy(alpha = 0.08f),
        1f to Color.White.copy(alpha = 0.32f),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val rimStroke = Stroke(width = 1.5.dp.toPx())
    val inset = 4.dp.toPx()
    val innerStroke = Stroke(width = 2.dp.toPx())
    val innerColor = Color.White.copy(alpha = 0.07f)
    val innerTopLeft = Offset(inset, inset)
    val innerSize = Size(size.width - inset * 2f, size.height - inset * 2f)
    val innerRadius = CornerRadius((corner - inset).coerceAtLeast(0f))
    val amplitude = 0.9.dp.toPx()

    onDrawBehind {
        val liquid = 1f - settle.value
        val flowing = liquid > 0.002f
        if (flowing) {
            val time = clock.floatValue
            buildLiquidOutline(
                path = path,
                width = size.width,
                height = size.height,
                corner = corner,
                amplitude = amplitude * liquid,
                swell = 0.008f * sin(time * 2.1f) * liquid,
                time = time,
            )
        }

        fun fillBrush(brush: Brush) {
            if (flowing) drawPath(path, brush) else drawRoundRect(brush, cornerRadius = radius)
        }

        fun fillColor(color: Color) {
            if (flowing) drawPath(path, color) else drawRoundRect(color, cornerRadius = radius)
        }

        // Soft shadow: the same outline, nudged down, in three faint steps.
        for (step in 1..3) {
            translate(top = step * shadowStep) { fillColor(shadow) }
        }
        // Over the blurred video the glass stays clear; without it the body carries the frost.
        fillColor(body)
        tintBrush?.let { fillBrush(it) }
        fillBrush(frost)
        if (flowing) drawPath(path, rim, style = rimStroke) else drawRoundRect(rim, cornerRadius = radius, style = rimStroke)
        // A faint refraction line just inside the rim.
        if (!flowing && innerSize.width > 0f && innerSize.height > 0f) {
            drawRoundRect(
                color = innerColor,
                topLeft = innerTopLeft,
                size = innerSize,
                cornerRadius = innerRadius,
                style = innerStroke,
            )
        }
    }
}

/**
 * The copied video, drawn like light through a thick glass lens: very slightly magnified in the
 * middle, and along the edge a band that shows the video from just beyond the bubble, squeezed
 * in, the way a glass edge bends what is behind it. The blur on top softens the seam.
 */
private fun DrawScope.drawRefractedBackdrop(frame: BubbleBackdropFrame?, bubble: Rect?, innerPath: Path) {
    frame ?: return
    bubble ?: return
    val image = frame.image
    // Where the copy lies, in the bubble's own coordinates.
    val left = frame.windowRect.left - bubble.left
    val top = frame.windowRect.top - bubble.top
    val right = frame.windowRect.right - bubble.left
    val bottom = frame.windowRect.bottom - bubble.top
    val cx = size.width / 2f
    val cy = size.height / 2f

    fun draw(scaleX: Float, scaleY: Float) {
        val l = cx + (left - cx) * scaleX
        val t = cy + (top - cy) * scaleY
        val r = cx + (right - cx) * scaleX
        val b = cy + (bottom - cy) * scaleY
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
            dstSize = IntSize((r - l).roundToInt().coerceAtLeast(1), (b - t).roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Low,
        )
    }

    draw(1.04f, 1.04f)
    val band = RefractionBand.toPx()
    val reach = RefractionReach.toPx()
    if (size.width <= band * 2f || size.height <= band * 2f) return
    innerPath.reset()
    innerPath.addRoundRect(
        RoundRect(
            left = band,
            top = band,
            right = size.width - band,
            bottom = size.height - band,
            cornerRadius = CornerRadius(
                (BubbleCorner.toPx().coerceAtMost(size.minDimension / 2f) - band).coerceAtLeast(0f),
            ),
        ),
    )
    clipPath(innerPath, clipOp = ClipOp.Difference) {
        draw(1f / (1f + 2f * reach / size.width), 1f / (1f + 2f * reach / size.height))
    }
}

/**
 * The rounded outline of [width] x [height], each point pushed along its normal by two slow waves
 * travelling in opposite directions, and the whole swollen sideways by [swell] (narrower when
 * negative), so the bubble reads as liquid rather than a shape that shakes.
 */
private fun buildLiquidOutline(
    path: Path,
    width: Float,
    height: Float,
    corner: Float,
    amplitude: Float,
    swell: Float,
    time: Float,
) {
    path.reset()
    val straightX = (width - 2f * corner).coerceAtLeast(0f)
    val straightY = (height - 2f * corner).coerceAtLeast(0f)
    val arc = corner * PI.toFloat() / 2f
    val perimeter = 2f * straightX + 2f * straightY + 4f * arc
    if (perimeter <= 0f) return
    val cx = width / 2f
    val cy = height / 2f
    val steps = 72
    for (i in 0..steps) {
        val u = (i % steps).toFloat() / steps
        var s = u * perimeter
        var x: Float
        var y: Float
        var nx: Float
        var ny: Float
        // Walk the outline clockwise from the top-left end of the top edge.
        if (s < straightX) {
            x = corner + s; y = 0f; nx = 0f; ny = -1f
        } else if (run { s -= straightX; s < arc }) {
            val a = -PI.toFloat() / 2f + s / corner
            nx = cos(a); ny = sin(a); x = width - corner + nx * corner; y = corner + ny * corner
        } else if (run { s -= arc; s < straightY }) {
            x = width; y = corner + s; nx = 1f; ny = 0f
        } else if (run { s -= straightY; s < arc }) {
            val a = s / corner
            nx = cos(a); ny = sin(a); x = width - corner + nx * corner; y = height - corner + ny * corner
        } else if (run { s -= arc; s < straightX }) {
            x = width - corner - s; y = height; nx = 0f; ny = 1f
        } else if (run { s -= straightX; s < arc }) {
            val a = PI.toFloat() / 2f + s / corner
            nx = cos(a); ny = sin(a); x = corner + nx * corner; y = height - corner + ny * corner
        } else if (run { s -= arc; s < straightY }) {
            x = 0f; y = height - corner - s; nx = -1f; ny = 0f
        } else {
            s -= straightY
            val a = PI.toFloat() + s / corner
            nx = cos(a); ny = sin(a); x = corner + nx * corner; y = corner + ny * corner
        }
        val wave = amplitude * (
            0.6f * sin(TWO_PI * 2f * u + time * 2.3f) +
                0.4f * sin(TWO_PI * 3f * u - time * 1.7f + 1.1f)
            )
        x = cx + (x + nx * wave - cx) * (1f + swell)
        y = cy + (y + ny * wave - cy) * (1f - swell)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/**
 * The droplet beside the words: a small glass lens. While working its outline flows and a
 * highlight glides around its rim; the result settles it into a clean circle filled with green
 * or red, and the check or X draws in. Brushes are built once per tint, not per frame.
 */
private fun Modifier.droplet(
    path: Path,
    kind: State<AutoSyncBubbleKind>,
    clock: FloatState,
    settle: Animatable<Float, *>,
    tint: State<Color>,
    tinted: State<Float>,
    mark: Animatable<Float, *>,
): Modifier = drawWithCache {
    val tintAmount = tinted.value
    val tintColor = tint.value
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f
    val lens = Brush.radialGradient(
        0f to Color.White.copy(alpha = 0.32f),
        1f to Color.White.copy(alpha = 0.12f),
        center = center + Offset(-radius * 0.35f, -radius * 0.45f),
        radius = radius * 1.6f,
    )
    val fill = if (tintAmount > 0f) {
        Brush.radialGradient(
            0f to tintColor.copy(alpha = 0.95f * tintAmount),
            1f to tintColor.copy(alpha = 0.80f * tintAmount),
            center = center + Offset(-radius * 0.3f, -radius * 0.4f),
            radius = radius * 1.5f,
        )
    } else {
        null
    }
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.8f),
        0.45f to Color.White.copy(alpha = 0.1f),
        1f to Color.White.copy(alpha = 0.45f),
        start = center - Offset(radius, radius),
        end = center + Offset(radius, radius),
    )
    val rimStroke = Stroke(width = 1.5.dp.toPx())
    val inner = radius * 0.62f
    val arcTopLeft = center - Offset(inner, inner)
    val arcSize = Size(inner * 2f, inner * 2f)
    val arcStroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
    val markStroke = 3.5.dp.toPx()

    onDrawBehind {
        val liquid = 1f - settle.value
        val time = clock.floatValue
        path.reset()
        val steps = 48
        for (i in 0..steps) {
            val theta = (i % steps).toFloat() / steps * TWO_PI
            val r = radius * (
                1f + liquid * (
                    0.022f * sin(3f * theta + time * 2.1f) +
                        0.014f * sin(2f * theta - time * 1.5f + 0.7f)
                    )
                ) * (1f - 0.03f * liquid)
            val x = center.x + cos(theta) * r
            val y = center.y + sin(theta) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(path, lens)
        fill?.let { drawPath(path, it) }
        drawPath(path, rim, style = rimStroke)
        // The gliding highlight: the loading cue, calm and continuous.
        if (liquid > 0f) {
            drawArc(
                color = Color.White.copy(alpha = 0.7f * liquid),
                startAngle = time * 170f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = arcStroke,
            )
        }

        val m = mark.value.coerceIn(0f, 1f)
        if (m > 0f) {
            if (kind.value == AutoSyncBubbleKind.Failure) {
                val d = radius * 0.30f
                drawTrimmed(center.x - d, center.y - d, center.x + d, center.y + d, Float.NaN, Float.NaN, (m * 2f).coerceAtMost(1f), markStroke)
                if (m > 0.5f) {
                    drawTrimmed(center.x + d, center.y - d, center.x - d, center.y + d, Float.NaN, Float.NaN, (m - 0.5f) * 2f, markStroke)
                }
            } else {
                drawTrimmed(
                    center.x - radius * 0.38f, center.y + radius * 0.02f,
                    center.x - radius * 0.11f, center.y + radius * 0.29f,
                    center.x + radius * 0.40f, center.y - radius * 0.27f,
                    m,
                    markStroke,
                )
            }
        }
    }
}

/**
 * Draws the first [progress] of the line through (x0, y0), (x1, y1) and, unless NaN, (x2, y2), as
 * a pen would. Takes plain coordinates so drawing the mark allocates no lists per frame.
 */
private fun DrawScope.drawTrimmed(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    progress: Float,
    stroke: Float,
) {
    if (progress <= 0f) return
    val first = Offset(x1 - x0, y1 - y0).getDistance()
    val second = if (x2.isNaN()) 0f else Offset(x2 - x1, y2 - y1).getDistance()
    var remaining = (first + second) * progress
    if (first > 0f && remaining > 0f) {
        val f = (remaining / first).coerceAtMost(1f)
        drawLine(
            color = Color.White,
            start = Offset(x0, y0),
            end = Offset(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        remaining -= first
    }
    if (second > 0f && remaining > 0f) {
        val f = (remaining / second).coerceAtMost(1f)
        drawLine(
            color = Color.White,
            start = Offset(x1, y1),
            end = Offset(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
