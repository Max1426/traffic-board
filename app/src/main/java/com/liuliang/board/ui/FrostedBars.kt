package com.liuliang.board.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

class BackdropSource(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    var ready by mutableStateOf(false)
}
val LocalPageInsets = staticCompositionLocalOf<PaddingValues> { PaddingValues() }

/** Include the page base so transparent pixels cannot expose sharp text below the glass. */
@Composable fun Modifier.backdropSource(source: BackdropSource): Modifier {
    val background = Background
    return onGloballyPositioned { source.origin = it.positionInRoot() }.drawWithContent {
        val drawingDensity = Density(density, fontScale)
        val target = drawContext
        val oldCanvas = target.canvas
        val oldSize = target.size
        val oldLayer = target.graphicsLayer
        val oldDensity = target.density
        source.layer.record(drawingDensity, layoutDirection, size.toIntSize()) {
            target.canvas = drawContext.canvas
            target.size = size
            target.graphicsLayer = source.layer
            target.density = drawingDensity
            try { this@drawWithContent.drawContent() }
            finally {
                target.canvas = oldCanvas; target.size = oldSize
                target.graphicsLayer = oldLayer; target.density = oldDensity
            }
        }
        source.ready = true
        drawLayer(source.layer)
    }.background(background)
}

object FrostProfile {
    val radii = listOf(0f, 4f, 8f, 16f, 24f, 36f, 48f)
    fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    fun strength(y: Float, height: Float, fade: Float, top: Boolean): Float = smooth(if (top) (height - y) / fade else y / fade)
    fun weights(strength: Float): List<Float> {
        val radius = radii.last() * strength.coerceIn(0f, 1f)
        val upper = radii.indexOfFirst { it >= radius }.coerceAtLeast(0)
        if (upper == 0) return radii.mapIndexed { i, _ -> if (i == 0) 1f else 0f }
        val mix = smooth((radius - radii[upper - 1]) / (radii[upper] - radii[upper - 1]))
        return radii.mapIndexed { i, _ -> when (i) { upper -> mix; upper - 1 -> 1 - mix; else -> 0f } }
    }
}

/** The Gaussian weights sum to an opaque image; fade only the tint, never that image. */
@Composable fun FrostedBar(source: BackdropSource, top: Boolean, blurSupported: Boolean = Build.VERSION.SDK_INT >= 31, content: @Composable ColumnScope.() -> Unit) {
    val levels = FrostProfile.radii.map { rememberGraphicsLayer() }
    val glass = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val background = Background
    val tintAlpha = if (LocalDarkAppearance.current) .55f else .65f
    Column(Modifier.fillMaxWidth().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (!it.isConsumed) it.consume() } }
    }.onGloballyPositioned { origin = it.positionInRoot() }.drawWithCache {
        val fade = 64.dp.toPx().coerceAtMost(size.height)
        val positions = (listOf(0f, size.height) + (0..64).map { if (top) size.height - fade + fade * it / 64 else fade * it / 64 }).distinct().sorted()
        val weights = positions.map { FrostProfile.weights(FrostProfile.strength(it, size.height, fade, top)) }
        val masks = levels.indices.map { level -> Brush.verticalGradient(*positions.mapIndexed { i, y -> y / size.height to Color.White.copy(alpha = weights[i][level]) }.toTypedArray()) }
        val bands = levels.indices.map { level ->
            val active = weights.indices.filter { weights[it][level] > 0f }
            positions[(active.first() - 1).coerceAtLeast(0)] to positions[(active.last() + 1).coerceAtMost(positions.lastIndex)]
        }
        val tint = Brush.verticalGradient(*positions.map { y -> y / size.height to background.copy(alpha = tintAlpha * FrostProfile.strength(y, size.height, fade, top)) }.toTypedArray())
        val plus = Paint().apply { blendMode = BlendMode.Plus }
        val drawingDensity = Density(density, fontScale)
        val direction = layoutDirection
        val barSize = size.toIntSize()
        onDrawWithContent {
            val delta = origin - source.origin
            glass.compositingStrategy = CompositingStrategy.Offscreen
            val renderBlur = blurSupported && source.ready && source.layer.size.width > 0
            // Record Gaussian layers before the composite; Compose 1.7 shares a recorder scope.
            if (renderBlur) levels.forEachIndexed { index, layer ->
                val radius = FrostProfile.radii[index].dp.toPx()
                val sampling = if (index == 0) 1f else if (index >= 3) .25f else .5f
                val margin = radius * 2
                val (bandTop, bandBottom) = bands[index]
                layer.renderEffect = if (radius == 0f) null else BlurEffect(radius * sampling, radius * sampling, TileMode.Clamp)
                layer.record(drawingDensity, direction, IntSize(ceil(size.width * sampling).toInt(), ceil((bandBottom - bandTop + 2 * margin) * sampling).toInt())) {
                    // Overscan outside the screen must be opaque too, including the status-bar edge.
                    drawRect(background)
                    scale(sampling, sampling, Offset.Zero) { translate(-delta.x, margin - delta.y - bandTop) { drawLayer(source.layer) } }
                }
            }
            glass.record(drawingDensity, direction, barSize) {
                if (renderBlur) levels.forEachIndexed { index, layer ->
                    val radius = FrostProfile.radii[index].dp.toPx()
                    val sampling = if (index == 0) 1f else if (index >= 3) .25f else .5f
                    val margin = radius * 2
                    val (bandTop, bandBottom) = bands[index]
                    clipRect(top = bandTop, bottom = bandBottom) {
                        drawContext.canvas.saveLayer(Rect(0f, bandTop, size.width, bandBottom), plus)
                        translate(top = bandTop) { scale(1 / sampling, 1 / sampling, Offset.Zero) { translate(top = -margin * sampling) { drawLayer(layer) } } }
                        drawRect(masks[index], blendMode = BlendMode.DstIn)
                        drawContext.canvas.restore()
                    }
                }
                drawRect(tint)
            }
            drawLayer(glass)
            drawContent()
        }
    }) {
        if (!top) Spacer(Modifier.height(64.dp))
        content()
        if (top) Spacer(Modifier.height(64.dp))
    }
}
