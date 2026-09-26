package com.nuvio.tv.ui.reshaped.pillnav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 10-foot sizes: the phone pill scaled up for couch distance and kept inside the overscan-safe area. */
internal object PillNavTokens {
    val barHeight = 52.dp
    val barTopGap = 22.dp
    val barSideMargin = 48.dp
    val innerPadding = 5.dp
    val itemHorizontalPadding = 20.dp
    val actionsGap = 14.dp
    val iconItemSize = 42.dp
    val iconSize = 24.dp
    val avatarSize = 34.dp
    val labelSize = 18.sp
    const val unselectedAlpha = 0.72f
    const val focusedScale = 1.1f

    /** Top padding root screens other than Home need so they start below the pill (Home draws under it). */
    val contentTopPadding: Dp = barTopGap + barHeight + 8.dp
}

private val GlassBaseColor = Color(0xFF1C1C1E)
private val GlassFocusedColor = Color(0xFF2C2C30)

internal enum class PillNavEntryKind { Tab, Settings, Profile }

/** One focusable slot of the pill. [key] is the route for tabs and settings. */
internal data class PillNavEntry(
    val key: String,
    val kind: PillNavEntryKind,
    val route: String?,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null,
)

/** Focus and hide-on-scroll state shared between the pill and the scaffold. */
@Stable
internal class PillNavBarState {
    /** True while any pill item holds focus. */
    var hasFocus by mutableStateOf(false)
        internal set

    /** Key of the pill item focused last; kept after focus leaves so D-pad moves can resolve it. */
    var focusedKey by mutableStateOf<String?>(null)
        internal set

    /** Home only: hidden after the user moves down into the rows, back on the way up (phone's hide-on-scroll). */
    var hiddenByScroll by mutableStateOf(false)
        private set

    fun show() {
        hiddenByScroll = false
    }

    fun hide() {
        if (!hasFocus) hiddenByScroll = true
    }
}

/**
 * Floating glass pill at the top centre: text tabs for the sidebar destinations, the settings gear and the
 * profile picture. A glass lens slides to the selected item, or to the focused one while the pill has focus.
 * Left/Right move between items and never leave the pill; Up stays; Down calls [onExitDown].
 */
@Composable
internal fun PillNavigationBar(
    entries: List<PillNavEntry>,
    selectedKey: String?,
    state: PillNavBarState,
    requesterFor: (String) -> FocusRequester,
    hazeState: HazeState?,
    hidden: Boolean,
    isRtl: Boolean,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    onEntryClick: (PillNavEntry) -> Unit,
    onExitDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barFocused = state.hasFocus
    val hideFraction = animateFloatAsState(
        targetValue = if (hidden && !barFocused) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 320f),
        label = "pill_nav_hide",
    )
    val fullyHidden by remember { derivedStateOf { hideFraction.value > 0.99f } }
    val density = LocalDensity.current
    val hideDistancePx = with(density) { (PillNavTokens.barTopGap + PillNavTokens.barHeight).toPx() }

    // Only the selected item is reachable by focus search from the content, so D-pad Up always lands on it.
    val entryKeyForEntry = selectedKey?.takeIf { key -> entries.any { it.key == key } } ?: entries.firstOrNull()?.key
    val lensKey = if (barFocused) state.focusedKey ?: selectedKey else selectedKey

    val itemBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val indicator = remember { LiquidIndicator() }
    val lensTarget = lensKey?.let { itemBounds[it] }
    LaunchedEffect(lensKey, lensTarget) {
        val (x, width) = lensTarget ?: return@LaunchedEffect
        indicator.moveTo(x, x + width)
    }
    val selectedBounds = selectedKey?.let { itemBounds[it] }
    val showLens = lensTarget != null

    val accent = NuvioTheme.colors.Secondary
    val blur = hazeState != null
    val glassColor by animateColorAsState(
        targetValue = when {
            barFocused -> GlassFocusedColor.copy(alpha = if (blur) 0.76f else 0.95f)
            else -> GlassBaseColor.copy(alpha = if (blur) 0.62f else 0.90f)
        },
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "pill_nav_glass",
    )
    val rimBrush = remember(accent, barFocused) {
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (barFocused) 0.85f else 0.50f),
                Color.White.copy(alpha = if (barFocused) 0.12f else 0.06f),
                accent.copy(alpha = if (barFocused) 0.40f else 0.20f),
            )
        )
    }
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = PillNavTokens.barTopGap, start = PillNavTokens.barSideMargin, end = PillNavTokens.barSideMargin),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .height(PillNavTokens.barHeight)
                .widthIn(max = 880.dp)
                .graphicsLayer {
                    val h = hideFraction.value
                    translationY = -h * hideDistancePx
                    alpha = 1f - h
                    scaleX = 1f - 0.04f * h
                    scaleY = 1f - 0.04f * h
                }
                .clip(shape)
                .then(
                    if (hazeState != null && !fullyHidden) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 24.dp
                            noiseFactor = 0f
                            inputScale = HazeInputScale.Fixed(0.66f)
                        }
                    } else {
                        Modifier
                    }
                )
                .background(glassColor, shape)
                .border(width = if (barFocused) 1.5.dp else 1.dp, brush = rimBrush, shape = shape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(PillNavTokens.innerPadding)
                    .drawBehind {
                        if (barFocused && selectedBounds != null && lensKey != selectedKey) {
                            drawSelectedMarker(selectedBounds)
                        }
                        if (showLens) drawLiquidIndicator(indicator, brighter = barFocused)
                    }
                    .onFocusChanged { state.hasFocus = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> true
                            Key.DirectionDown -> {
                                onExitDown()
                                true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> {
                                val index = entries.indexOfFirst { it.key == state.focusedKey }
                                val forward = (event.key == Key.DirectionRight) != isRtl
                                val target = entries.getOrNull(if (forward) index + 1 else index - 1)
                                if (index >= 0 && target != null) {
                                    runCatching { requesterFor(target.key).requestFocus() }
                                }
                                true
                            }
                            else -> false
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEachIndexed { index, entry ->
                    if (entry.kind != PillNavEntryKind.Tab && index > 0 && entries[index - 1].kind == PillNavEntryKind.Tab) {
                        Spacer(Modifier.width(PillNavTokens.actionsGap))
                    }
                    PillItem(
                        entry = entry,
                        selected = entry.key == selectedKey,
                        focusable = barFocused || entry.key == entryKeyForEntry,
                        requester = requesterFor(entry.key),
                        onFocused = { state.focusedKey = entry.key },
                        onClick = { onEntryClick(entry) },
                        onBounds = { x, width ->
                            val bounds = x to width
                            if (itemBounds[entry.key] != bounds) itemBounds[entry.key] = bounds
                        },
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                    )
                }
            }
        }
    }
}

@Composable
private fun PillItem(
    entry: PillNavEntry,
    selected: Boolean,
    focusable: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onBounds: (Float, Float) -> Unit,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (focused) PillNavTokens.focusedScale else 1f,
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "pill_nav_item_scale",
    )
    val contentAlpha = if (focused || selected) 1f else PillNavTokens.unselectedAlpha
    val isTab = entry.kind == PillNavEntryKind.Tab
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .then(if (isTab) Modifier else Modifier.width(PillNavTokens.iconItemSize))
            .onPlaced { onBounds(it.positionInParent().x, it.size.width.toFloat()) }
            .focusRequester(requester)
            .focusProperties { canFocus = focusable }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = contentAlpha
            }
            .then(if (isTab) Modifier.padding(horizontal = PillNavTokens.itemHorizontalPadding) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (entry.kind) {
            PillNavEntryKind.Tab -> Text(
                text = entry.label,
                color = Color.White,
                fontSize = PillNavTokens.labelSize,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
            PillNavEntryKind.Settings -> when {
                entry.icon != null -> Icon(
                    imageVector = entry.icon,
                    contentDescription = entry.label,
                    tint = Color.White,
                    modifier = Modifier.size(PillNavTokens.iconSize),
                )
                entry.iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(entry.iconRes),
                    contentDescription = entry.label,
                    tint = Color.White,
                    modifier = Modifier.size(PillNavTokens.iconSize),
                )
            }
            PillNavEntryKind.Profile -> ProfileAvatarCircle(
                name = entry.label,
                colorHex = activeProfileColorHex,
                size = PillNavTokens.avatarSize,
                avatarImageUrl = activeProfileAvatarImageUrl,
                imageCrossfade = false,
            )
        }
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val sizePx = with(LocalDensity.current) { PillNavTokens.iconSize.roundToPx() }
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(rawIconRes)
            .size(sizePx)
            .build()
    )
}

/**
 * The glass lens as two independently sprung edges, as on the phone: the leading edge races ahead and the
 * trailing edge follows, so the lens stretches in flight and settles back into a pill.
 */
@Stable
private class LiquidIndicator {
    val left = Animatable(0f)
    val right = Animatable(0f)
    var placed by mutableStateOf(false)
        private set
    private var restWidth = 1f

    suspend fun moveTo(targetLeft: Float, targetRight: Float) {
        restWidth = (targetRight - targetLeft).coerceAtLeast(1f)
        if (!placed) {
            left.snapTo(targetLeft)
            right.snapTo(targetRight)
            placed = true
            return
        }
        val movingRight = targetLeft > left.value
        val lead = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
        val trail = spring<Float>(dampingRatio = 0.86f, stiffness = 210f)
        coroutineScope {
            launch { left.animateTo(targetLeft, if (movingRight) trail else lead) }
            launch { right.animateTo(targetRight, if (movingRight) lead else trail) }
        }
    }

    /** 0 at rest, towards 1 while stretched in flight. */
    fun stretch(): Float = ((right.value - left.value) / restWidth - 1f).coerceIn(0f, 1.5f) / 1.5f
}

private fun DrawScope.drawSelectedMarker(bounds: Pair<Float, Float>) {
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f),
        topLeft = Offset(bounds.first, 0f),
        size = Size(bounds.second, size.height),
        cornerRadius = CornerRadius(size.height / 2f),
    )
}

private fun DrawScope.drawLiquidIndicator(indicator: LiquidIndicator, brighter: Boolean) {
    if (!indicator.placed) return
    val boost = if (brighter) 1.4f else 1f
    val stretch = indicator.stretch()
    val squash = 1f - 0.16f * stretch
    val height = size.height * squash
    val top = (size.height - height) / 2f
    val width = indicator.right.value - indicator.left.value
    if (width <= 0f) return
    val topLeft = Offset(indicator.left.value, top)
    val radius = CornerRadius(height / 2f)
    // Glass lens: a bright top falling to a soft base, with a specular rim.
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.30f * boost),
            0.55f to Color.White.copy(alpha = 0.16f * boost),
            1f to Color.White.copy(alpha = 0.22f * boost),
            startY = top,
            endY = top + height,
        ),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = radius,
    )
    val rim = 1.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = (0.62f * boost).coerceAtMost(1f)),
            0.5f to Color.White.copy(alpha = 0.08f),
            1f to Color.White.copy(alpha = 0.28f * boost),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(topLeft.x + rim / 2f, top + rim / 2f),
        size = Size(width - rim, height - rim),
        cornerRadius = CornerRadius((height - rim) / 2f),
        style = Stroke(rim),
    )
}
