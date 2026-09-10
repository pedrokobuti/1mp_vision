package com.llama.asciicam.pipeline

import kotlin.math.hypot
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
    /** Current eased drift offset from the cell center, as a fraction of cell
     * size — see [StipplePipeline]'s class doc for how it migrates. */
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
 * grid cell owns one dot whose radius tracks local tone directly, and whose
 * *position* drifts toward the tone-weighted centroid of its own
 * neighborhood (see [RETARGET_CYCLE_FRAMES]/[NEIGHBORHOOD_RADIUS_CELLS]) —
 * one cheap local step, not a full relaxation, but it's what actually makes
 * dots migrate to cluster densely in "heavy" (bright, or dark when inverted)
 * regions and thin out elsewhere, rather than just resizing in place. A new
 * target is picked once every [RETARGET_CYCLE_FRAMES] frames and the dot
 * eases toward it over those frames (see [PipelineState.stippleOffX]) so
 * motion reads as a drift, not a per-frame jitter.
 */
object StipplePipeline {

    const val MAX_COLS = 160

    /** How many frames a dot spends easing from its old position to its next
     * target before a new target is picked. */
    private const val RETARGET_CYCLE_FRAMES = 15

    /** Neighborhood a dot's next target is pulled from, in cells each
     * direction (so a (2*R+1)^2 window). Keeps drift local — a dot migrates
     * within its own neighborhood rather than crossing the whole frame. */
    private const val NEIGHBORHOOD_RADIUS_CELLS = 2

    /** Hard cap on how far (in cells) a dot's target can be from its home
     * cell center, so a very lopsided local neighborhood can't send it miles
     * from where it started. */
    private const val MAX_DRIFT_CELLS = 1.3f

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

        // Ink weight: how much dot ("ink") a cell should carry, and where
        // dots want to migrate — toward higher weight. Default (not
        // inverted) is bright dots on black — ink follows brightness, so a
        // dark region simply fades to plain background. Invert Stippling
        // flips both the background and this weighting, giving dark dots on
        // white whose density follows darkness — the classic stippled look.
        val inkIsDark = settings.invertStippling
        val dotScale = (settings.stippleDotScale / 100f).coerceAtLeast(0f)
        val minVisible = 0.04f

        val weight = state.stippleWeight
        for (i in 0 until n) {
            val v = lum[i].coerceIn(0f, 1f)
            weight[i] = if (inkIsDark) 1f - v else v
        }

        // ---- position: retarget once per RETARGET_CYCLE_FRAMES-frame cycle, ease between ----
        val cycleFrame = state.stippleCycleFrame
        if (cycleFrame == 0) {
            val startX = state.stippleStartX; val startY = state.stippleStartY
            val targetX = state.stippleTargetX; val targetY = state.stippleTargetY
            val offX = state.stippleOffX; val offY = state.stippleOffY
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val i = y * cols + x
                    var sumW = 0f; var sumWx = 0f; var sumWy = 0f
                    for (dy in -NEIGHBORHOOD_RADIUS_CELLS..NEIGHBORHOOD_RADIUS_CELLS) {
                        val ny = y + dy
                        if (ny < 0 || ny >= rows) continue
                        for (dx in -NEIGHBORHOOD_RADIUS_CELLS..NEIGHBORHOOD_RADIUS_CELLS) {
                            val nx = x + dx
                            if (nx < 0 || nx >= cols) continue
                            val w = weight[ny * cols + nx]
                            sumW += w
                            sumWx += w * dx
                            sumWy += w * dy
                        }
                    }
                    var tx = 0f; var ty = 0f
                    if (sumW > 1e-4f) {
                        tx = sumWx / sumW
                        ty = sumWy / sumW
                        val len = hypot(tx, ty)
                        if (len > MAX_DRIFT_CELLS) {
                            val s = MAX_DRIFT_CELLS / len
                            tx *= s; ty *= s
                        }
                    }
                    // Next cycle eases from wherever the dot actually is now
                    // (the target it just finished reaching) to the new pull.
                    startX[i] = offX[i]; startY[i] = offY[i]
                    targetX[i] = tx; targetY[i] = ty
                }
            }
        }
        val t = (cycleFrame + 1f) / RETARGET_CYCLE_FRAMES
        for (i in 0 until n) {
            state.stippleOffX[i] = state.stippleStartX[i] + (state.stippleTargetX[i] - state.stippleStartX[i]) * t
            state.stippleOffY[i] = state.stippleStartY[i] + (state.stippleTargetY[i] - state.stippleStartY[i]) * t
        }
        state.stippleCycleFrame = (cycleFrame + 1) % RETARGET_CYCLE_FRAMES

        for (i in 0 until n) {
            val v = lum[i].coerceIn(0f, 1f)
            val w = weight[i]
            if (w <= minVisible) {
                result.visible[i] = false
                result.radiusFraction[i] = 0f
            } else {
                result.visible[i] = true
                result.radiusFraction[i] = (w.pow(0.8f) * 0.5f * dotScale).coerceIn(0f, 0.5f)
            }
            result.offsetXFraction[i] = state.stippleOffX[i]
            result.offsetYFraction[i] = state.stippleOffY[i]
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
