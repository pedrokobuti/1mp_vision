package com.llama.asciicam.pipeline

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/** Square-cell grid geometry for stippling — simpler than [GridGeometry]:
 * no font/glyph metrics involved, just a viewport-filling square grid. */
data class StippleGeometry(val cols: Int, val rows: Int, val cellSize: Float)

/**
 * Output of one Digital Stippling pass: parallel arrays, one slot per grid
 * cell in row-major order, analogous to [AsciiFrameResult].
 */
class StippleFrameResult(val cols: Int, val rows: Int) {
    val visible = BooleanArray(cols * rows)
    /** Dot radius as a fraction of the cell size (0 = invisible, ~0.5 = fills the cell). */
    val radiusFraction = FloatArray(cols * rows)
    /** This frame's jitter offset from the cell center, as a fraction of cell size. */
    val offsetXFraction = FloatArray(cols * rows)
    val offsetYFraction = FloatArray(cols * rows)
    val colors = IntArray(cols * rows)
    /** Mean post-color-adjust luminance across the frame (0..1), same role as
     * [AsciiFrameResult.avgLuminance] — drives the automatic Invert background. */
    var avgLuminance: Float = 0f
}

/**
 * "Digital Stippling" render mode: an alternative to ASCII characters that
 * renders the frame as dots whose size/density track local tone, evoking
 * classic weighted-Voronoi stippled portraiture.
 *
 * This is a real-time *approximation*, not true weighted-Voronoi stippling.
 * The reference technique (Secord's algorithm) relaxes thousands of points
 * via iterative Lloyd relaxation against the image's density field — solid
 * for a still image, but each relaxation pass is its own full pass over the
 * point set, repeated many times, which isn't something this app's CPU-only
 * per-frame pipeline can afford at live camera framerates. Instead, each
 * grid cell owns one dot whose radius tracks local tone and whose position
 * is re-jittered inside its cell every frame, so the field shimmers like
 * film grain rather than sitting on a visible rigid lattice.
 */
object StipplePipeline {

    const val MAX_COLS = 160

    /** How far a dot may sit from its cell center, as a fraction of cell size. */
    private const val JITTER_SPREAD = 0.6f

    fun computeGeometry(settings: AsciiSettings, sourceWidth: Int, sourceHeight: Int, viewportWidthPx: Float): StippleGeometry {
        val cols = settings.stippleDensity.coerceIn(10, MAX_COLS)
        val cellSize = (viewportWidthPx / cols).coerceAtLeast(0.5f)
        val srcAspect = if (sourceWidth > 0) sourceHeight.toFloat() / sourceWidth.toFloat() else 0.75f
        val rows = max(1, round(cols * srcAspect).toInt())
        return StippleGeometry(cols, rows, cellSize)
    }

    fun process(
        rawR: FloatArray,
        rawG: FloatArray,
        rawB: FloatArray,
        cols: Int,
        rows: Int,
        settings: AsciiSettings,
        state: PipelineState,
        dtSeconds: Float,
        applyTemporalSmoothing: Boolean,
    ): StippleFrameResult {
        state.ensureSize(cols, rows)
        val n = cols * rows

        val adjusted = computeAdjustedFrame(rawR, rawG, rawB, cols, rows, settings, state, dtSeconds, applyTemporalSmoothing)
        val lum = adjusted.lum; val r = adjusted.r; val g = adjusted.g; val b = adjusted.b

        val result = StippleFrameResult(cols, rows)

        var lumSum = 0f
        for (i in 0 until n) lumSum += lum[i]
        result.avgLuminance = if (n > 0) (lumSum / n).coerceIn(0f, 1f) else 0f

        // Ink weight: how much dot ("ink") a cell should carry. Default (not
        // inverted) is bright dots on black — ink follows brightness, so a
        // dark region simply fades to plain background. Invert Stippling
        // flips both the background and this weighting, giving dark dots on
        // white whose density follows darkness — the classic stippled look.
        val inkIsDark = settings.invertStippling
        val dotScale = (settings.stippleDotScale / 100f).coerceAtLeast(0f)
        val minVisible = 0.04f

        // Advances every frame, so each dot draws a fresh hash offset per
        // frame: the dot field shimmers instead of sitting on a fixed grid.
        // Wrapped well inside float-exact integer range for hashNoiseF.
        state.stippleFrame = (state.stippleFrame + 1) % 4096
        val jitterPhase = state.stippleFrame.toFloat()

        for (i in 0 until n) {
            val v = lum[i].coerceIn(0f, 1f)
            val weight = if (inkIsDark) 1f - v else v
            if (weight <= minVisible) {
                result.visible[i] = false
                result.radiusFraction[i] = 0f
            } else {
                result.visible[i] = true
                result.radiusFraction[i] = (weight.pow(0.8f) * 0.5f * dotScale).coerceIn(0f, 0.5f)
            }
            val x = i % cols
            val y = i / cols
            result.offsetXFraction[i] = (hashNoiseF(x, y, jitterPhase, 11f) - 0.5f) * JITTER_SPREAD
            result.offsetYFraction[i] = (hashNoiseF(x, y, jitterPhase, 53f) - 0.5f) * JITTER_SPREAD
            result.colors[i] = stippleCellColor(settings, r[i], g[i], b[i], v)
        }

        return result
    }

    private fun stippleCellColor(settings: AsciiSettings, r: Float, g: Float, b: Float, v: Float): Int {
        return when (settings.stippleColorMode) {
            ColorMode.SOURCE -> argbOf(255, r, g, b)
            // Mirrors AsciiPipeline.cellColor's MONO: dark ink on the pale
            // (inverted) background, near-white ink on the default black one.
            ColorMode.MONO -> if (settings.invertStippling) 0xFF17171A.toInt() else 0xFFE8E8EA.toInt()
            ColorMode.PALETTE -> paletteColor(settings.stipplePaletteStops, v)
            ColorMode.IMPOSTER -> discretePaletteColor(IMPOSTER_PALETTE_STOPS, v)
        }
    }

    /**
     * Background color for Digital Stippling: black by default (bright dots),
     * white while Invert Stippling is on (dark dots) — unlike Invert ASCII,
     * this doesn't need an automatic average-luminance gray, since flipping
     * straight to pure white is exactly the classic "ink on paper" stippling
     * look the reference images show.
     */
    fun backgroundArgbFor(settings: AsciiSettings): Int {
        return if (settings.invertStippling) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
}
