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
    /** This frame's drift offset from the cell center, as a fraction of cell
     * size — see [StipplePipeline]'s class doc for how a dot travels. */
    val offsetXFraction = FloatArray(cols * rows)
    val offsetYFraction = FloatArray(cols * rows)
    val colors = IntArray(cols * rows)

    /**
     * How merged each dot is with the neighbor to its east / to its south,
     * 0 (apart) to 1 (fully joined). Eased over
     * [StipplePipeline.MERGE_LERP_FRAMES] frames rather than switched on the
     * moment two dots come within range, so a neck grows and thins instead of
     * popping. Only these two directions: diagonal neighbors sit ~1.41 cells
     * apart, which at any sane density is further than dots can reach.
     */
    val mergeEast = FloatArray(cols * rows)
    val mergeSouth = FloatArray(cols * rows)

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
 * per-frame pipeline can afford at live camera framerates.
 *
 * Instead each grid cell owns one dot, and that dot *drifts*: every
 * [LERP_FRAMES] frames it picks a new target — the tone-weighted centroid of
 * its own neighborhood — and travels there in a straight line over those
 * frames. So dots slide toward whichever side of their cell carries more ink
 * (brighter, or darker when inverted) and away from the thin side, which is
 * what produces genuinely denser and sparser regions rather than a lattice
 * that only resizes.
 *
 * Two things keep that from looking mechanical. Dots don't share a cycle —
 * each has its own fixed phase within it ([cellPhase]), so at any frame only
 * about a fifth of them are setting off and the field never pulses in
 * lockstep. And each target is nudged by [CHAOS_CELLS] of per-dot,
 * per-journey randomness, so dots in identical surroundings still don't
 * arrive at identical places. The randomness is in *where* a dot is headed,
 * never in where it is drawn — it still walks a straight line at a steady
 * pace, so this reads as wander rather than the per-frame jitter it replaced.
 *
 * [MAX_DRIFT_CELLS] is the knob that decides how far any of this can go. Kept
 * safely under one cell on purpose — let dots travel much further and they
 * pile onto their neighbors, which collapses the even spacing stippling
 * depends on and reads as clumps and holes rather than tone.
 */
object StipplePipeline {

    const val MAX_COLS = 160

    /** Frames a dot takes to travel from its old position to its next target. */
    private const val LERP_FRAMES = 6

    /** Neighborhood a dot's target is drawn from, in cells each direction. */
    private const val NEIGHBORHOOD_RADIUS_CELLS = 2

    /** How far a dot may sit from its cell center, as a fraction of cell size. */
    private const val MAX_DRIFT_CELLS = 0.6f

    /** How much of [MAX_DRIFT_CELLS] is spent on per-journey randomness rather
     * than on the tone gradient. The tone-driven part is clamped to what's
     * left, so the two together always stay inside the cap. */
    private const val CHAOS_CELLS = 0.16f

    /** Frame counter wrap. A multiple of [LERP_FRAMES] so phases stay
     * continuous across the wrap. */
    private const val FRAME_WRAP = 60_000

    /**
     * How close two dots' edges must come, in screen pixels, before they start
     * to merge. Small on purpose: this is a kiss between neighbors, not a
     * field that fuses the whole picture into a blob.
     */
    const val MERGE_REACH_PX = 3f

    /** Frames a neck takes to grow in or thin out, matching the drift's easing
     * so nothing in the effect snaps. */
    const val MERGE_LERP_FRAMES = 5

    /**
     * Neck thickness at full merge, as a fraction of the smaller dot's
     * diameter. Below 1 so the dots still read as dots — the neck joins them,
     * it doesn't swallow them.
     */
    const val MERGE_NECK_FACTOR = 0.9f

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
        // The live view's cell size in px. Needed only to measure merging,
        // which is specified in screen pixels while everything else here is in
        // cell fractions. Exports scale the whole field, so necks scale with
        // it and the look is preserved rather than pixel-identical.
        cellSizePx: Float,
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

        val weight = state.stippleWeight
        for (i in 0 until n) {
            val v = lum[i].coerceIn(0f, 1f)
            weight[i] = if (inkIsDark) 1f - v else v
        }

        // ---- drift: each dot retargets on its own phase, then travels linearly ----
        state.stippleFrame = (state.stippleFrame + 1) % FRAME_WRAP
        advanceDrift(weight, cols, rows, state)

        for (i in 0 until n) {
            val v = lum[i].coerceIn(0f, 1f)
            val w = weight[i]
            if (w <= minVisible) {
                result.visible[i] = false
                result.radiusFraction[i] = 0f
            } else {
                result.visible[i] = true
                // Ceiling is a whole cell, not the half-cell that made dots
                // just touch: at 0.5 the brightest dots were already at the
                // limit with the size slider at 100%, so most of the slider's
                // range did nothing. A full cell lets them genuinely merge at
                // the top of the range, which is what asking for 200% means.
                result.radiusFraction[i] = (w.pow(0.8f) * 0.5f * dotScale).coerceIn(0f, 1f)
            }
            result.offsetXFraction[i] = state.stippleOffX[i]
            result.offsetYFraction[i] = state.stippleOffY[i]
            result.colors[i] = stippleCellColor(settings, r[i], g[i], b[i], v)
        }

        advanceMerging(result, cols, rows, state, cellSizePx)

        return result
    }

    /**
     * Moves every dot one frame along its journey, and gives a new target to
     * the ones whose journey ends this frame.
     *
     * A dot's target is the tone-weighted centroid of the cells around it —
     * the direction its neighborhood's ink actually lies in — plus a small
     * random nudge. A flat neighborhood averages to no offset, so the dot
     * wanders only by that nudge, which is what keeps empty regions evenly
     * spaced instead of collapsing them.
     */
    private fun advanceDrift(weight: FloatArray, cols: Int, rows: Int, state: PipelineState) {
        val frame = state.stippleFrame
        val gradientCap = MAX_DRIFT_CELLS - CHAOS_CELLS
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val i = y * cols + x
                // Staggered: a dot's phase decides where in the cycle it is,
                // so the grid never sets off together.
                val step = (frame + cellPhase(x, y)) % LERP_FRAMES
                if (step == 0) {
                    var sumW = 0f; var sumWx = 0f; var sumWy = 0f
                    for (dy in -NEIGHBORHOOD_RADIUS_CELLS..NEIGHBORHOOD_RADIUS_CELLS) {
                        // Clamp to the edge rather than dropping out-of-bounds
                        // neighbors. Dropping them leaves the window lopsided
                        // along a border -- a cell in column 0 would only ever
                        // see neighbors to its right, so even a perfectly flat
                        // image pulled its dots inward, drawing a visible frame
                        // of displaced dots around the picture. Clamping keeps
                        // the offsets symmetric, so a flat neighborhood sums to
                        // no pull anywhere, edges included.
                        val ny = (y + dy).coerceIn(0, rows - 1)
                        for (dx in -NEIGHBORHOOD_RADIUS_CELLS..NEIGHBORHOOD_RADIUS_CELLS) {
                            val nx = (x + dx).coerceIn(0, cols - 1)
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
                        if (len > gradientCap) {
                            val s = gradientCap / len
                            tx *= s; ty *= s
                        }
                    }
                    // Seeded on the journey index, so a dot gets a different
                    // nudge each leg rather than a fixed personal offset.
                    val journey = (frame + cellPhase(x, y)) / LERP_FRAMES
                    tx += (cellRandom(x, y, journey, 1) - 0.5f) * 2f * CHAOS_CELLS
                    ty += (cellRandom(x, y, journey, 2) - 0.5f) * 2f * CHAOS_CELLS
                    // The nudge is per-axis, so on a diagonal it can push the
                    // total past the cap even though each part is within it.
                    val total = hypot(tx, ty)
                    if (total > MAX_DRIFT_CELLS) {
                        val s = MAX_DRIFT_CELLS / total
                        tx *= s; ty *= s
                    }

                    // The next leg starts wherever the dot actually is, so the
                    // path stays continuous across journeys.
                    state.stippleStartX[i] = state.stippleOffX[i]
                    state.stippleStartY[i] = state.stippleOffY[i]
                    state.stippleTargetX[i] = tx
                    state.stippleTargetY[i] = ty
                }
                // Reaches exactly 1.0 on the journey's final frame, so a dot
                // arrives just as its next target is chosen — no stall.
                val t = (step + 1f) / LERP_FRAMES
                state.stippleOffX[i] = state.stippleStartX[i] + (state.stippleTargetX[i] - state.stippleStartX[i]) * t
                state.stippleOffY[i] = state.stippleStartY[i] + (state.stippleTargetY[i] - state.stippleStartY[i]) * t
            }
        }
    }

    /**
     * Eases every neighbor pair's merge strength toward whether those two dots
     * are actually within reach of each other this frame, and writes the
     * result onto [result].
     *
     * "Within reach" is measured edge to edge, not center to center: two fat
     * dots touch at a center distance two thin ones would be strangers at, and
     * it's the gap between their rims that decides whether ink would bridge
     * them. A pair already overlapping sits at full strength.
     */
    private fun advanceMerging(
        result: StippleFrameResult,
        cols: Int,
        rows: Int,
        state: PipelineState,
        cellSizePx: Float,
    ) {
        val stepPerFrame = 1f / MERGE_LERP_FRAMES
        val reach = if (cellSizePx > 0f) MERGE_REACH_PX / cellSizePx else 0f // in cell units

        fun centerX(x: Int, i: Int) = x + 0.5f + result.offsetXFraction[i]
        fun centerY(y: Int, i: Int) = y + 0.5f + result.offsetYFraction[i]

        fun target(i: Int, j: Int, dx: Float, dy: Float): Float {
            if (!result.visible[i] || !result.visible[j]) return 0f
            if (reach <= 0f) return 0f
            val gap = hypot(dx, dy) - (result.radiusFraction[i] + result.radiusFraction[j])
            return when {
                gap <= 0f -> 1f
                gap >= reach -> 0f
                else -> 1f - gap / reach
            }
        }

        fun ease(current: Float, goal: Float): Float {
            val delta = goal - current
            return when {
                delta > stepPerFrame -> current + stepPerFrame
                delta < -stepPerFrame -> current - stepPerFrame
                else -> goal
            }
        }

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val i = y * cols + x
                val cx = centerX(x, i); val cy = centerY(y, i)

                if (x + 1 < cols) {
                    val j = i + 1
                    val t = target(i, j, centerX(x + 1, j) - cx, centerY(y, j) - cy)
                    state.stippleMergeEast[i] = ease(state.stippleMergeEast[i], t)
                } else {
                    state.stippleMergeEast[i] = 0f
                }

                if (y + 1 < rows) {
                    val j = i + cols
                    val t = target(i, j, centerX(x, j) - cx, centerY(y + 1, j) - cy)
                    state.stippleMergeSouth[i] = ease(state.stippleMergeSouth[i], t)
                } else {
                    state.stippleMergeSouth[i] = 0f
                }

                result.mergeEast[i] = state.stippleMergeEast[i]
                result.mergeSouth[i] = state.stippleMergeSouth[i]
            }
        }
    }

    /** Which frame of the shared cycle this cell sets off on — fixed for the
     * life of the grid, and scattered enough that neighbors rarely share it. */
    private fun cellPhase(x: Int, y: Int): Int = (mix(x, y, 0, 0) ushr 1) % LERP_FRAMES

    /** A 0..1 value from the cell, journey and [salt]. An integer hash rather
     * than [hashNoiseF]: this runs per cell per frame, and it needs no
     * continuity between journeys, only scatter. */
    private fun cellRandom(x: Int, y: Int, journey: Int, salt: Int): Float =
        (mix(x, y, journey, salt) and 0xFFFFFF) / 16777215f

    private fun mix(x: Int, y: Int, z: Int, salt: Int): Int {
        var h = x * 73856093 xor y * 19349663 xor z * 83492791 xor salt * 50331653
        h = h xor (h ushr 13)
        h *= 1274126177
        h = h xor (h ushr 16)
        return h and 0x7FFFFFFF
    }

    private fun stippleCellColor(settings: AsciiSettings, r: Float, g: Float, b: Float, v: Float): Int {
        return when (settings.stippleColorMode) {
            ColorMode.SOURCE -> argbOf(255, r, g, b)
            ColorMode.MONO -> settings.stippleMonoColorArgb
            ColorMode.PALETTE -> paletteColor(settings.stipplePaletteStops, v)
            ColorMode.IMPOSTER -> discretePaletteColor(IMPOSTER_PALETTE_STOPS, v)
        }
    }

    /**
     * Background color for Digital Stippling: black by default (bright dots
     * on black), and while Invert Stippling is on, the same automatic gray
     * Invert ASCII uses — the frame's average luminance at 0% saturation (see
     * [AsciiPipeline.backgroundArgbFor]) — so the paper tone tracks what the
     * camera is actually looking at rather than sitting at a fixed white.
     */
    fun backgroundArgbFor(settings: AsciiSettings, avgLuminance: Float): Int {
        if (!settings.invertStippling) return 0xFF000000.toInt()
        val gray = round(avgLuminance.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
}
