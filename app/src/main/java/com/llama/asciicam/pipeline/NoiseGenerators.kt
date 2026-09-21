package com.llama.asciicam.pipeline

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic procedural noise, ported line-for-line from the original web
 * tool's `noiseHash` / `perlin2D` / `simplex2D` / `sparseConvolution` /
 * `worley` / `plasma` / `turbulence` / `generateNoiseValue` functions.
 */
object NoiseGenerators {

    /** Shared integer hash -> 0..1. */
    fun noiseHash(ix: Int, iy: Int, seed: Int): Float {
        var n = (ix * 374761393 + iy * 668265263 + seed * 2147483647)
        n = n xor (n ushr 13)
        n *= 1274126177
        n = n xor (n ushr 16)
        // unsigned 32-bit -> 0..1
        val u = n.toLong() and 0xFFFFFFFFL
        return (u / 4294967295.0).toFloat()
    }

    // Deterministic shuffled permutation table (seeded LCG identical to the JS version).
    private val NOISE_PERM: IntArray = run {
        val p = IntArray(256) { it }
        var seed = 1337L
        fun rnd(): Double {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            return seed / 0x7fffffff.toDouble()
        }
        for (i in 255 downTo 1) {
            val j = floor(rnd() * (i + 1)).toInt()
            val tmp = p[i]; p[i] = p[j]; p[j] = tmp
        }
        val perm = IntArray(512)
        for (i in 0 until 512) perm[i] = p[i and 255]
        perm
    }

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

    private val GRAD2 = arrayOf(
        floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f),
        floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
    )
    private fun grad2(hash: Int, x: Float, y: Float): Float {
        val g = GRAD2[hash and 7]
        return g[0] * x + g[1] * y
    }

    fun perlin2D(x: Float, y: Float): Float {
        val X = floor(x).toInt() and 255
        val Y = floor(y).toInt() and 255
        val xf = x - floor(x)
        val yf = y - floor(y)
        val u = fade(xf)
        val v = fade(yf)
        val p = NOISE_PERM
        val aa = p[p[X] + Y]
        val ab = p[p[X] + Y + 1]
        val ba = p[p[X + 1] + Y]
        val bb = p[p[X + 1] + Y + 1]
        val x1 = lerp(grad2(aa, xf, yf), grad2(ba, xf - 1, yf), u)
        val x2 = lerp(grad2(ab, xf, yf - 1), grad2(bb, xf - 1, yf - 1), u)
        return lerp(x1, x2, v) * 0.7f + 0.5f
    }

    private val SIMPLEX_F2 = (0.5 * (sqrt(3.0) - 1)).toFloat()
    private val SIMPLEX_G2 = ((3 - sqrt(3.0)) / 6).toFloat()
    private val SIMPLEX_GRAD = arrayOf(
        floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f),
        floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f),
        floatArrayOf(0f, 1f), floatArrayOf(0f, -1f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
    )
    private fun simplexGrad(i: Int, j: Int): Int {
        val idx = (NOISE_PERM[i and 255] + j) and 511
        return NOISE_PERM[idx] % 12
    }

    fun simplex2D(xin: Float, yin: Float): Float {
        val s = (xin + yin) * SIMPLEX_F2
        val i = floor(xin + s).toInt()
        val j = floor(yin + s).toInt()
        val t = (i + j) * SIMPLEX_G2
        val X0 = i - t
        val Y0 = j - t
        val x0 = xin - X0
        val y0 = yin - Y0
        val i1: Int; val j1: Int
        if (x0 > y0) { i1 = 1; j1 = 0 } else { i1 = 0; j1 = 1 }
        val x1 = x0 - i1 + SIMPLEX_G2
        val y1 = y0 - j1 + SIMPLEX_G2
        val x2 = x0 - 1 + 2 * SIMPLEX_G2
        val y2 = y0 - 1 + 2 * SIMPLEX_G2
        val gi0 = simplexGrad(i, j)
        val gi1 = simplexGrad(i + i1, j + j1)
        val gi2 = simplexGrad(i + 1, j + 1)
        fun contrib(gi: Int, x: Float, y: Float): Float {
            var t0 = 0.5f - x * x - y * y
            if (t0 < 0) return 0f
            t0 *= t0
            val g = SIMPLEX_GRAD[gi]
            return t0 * t0 * (g[0] * x + g[1] * y)
        }
        val n = contrib(gi0, x0, y0) + contrib(gi1, x1, y1) + contrib(gi2, x2, y2)
        return n * 35f + 0.5f
    }

    fun sparseConvolution(x: Float, y: Float): Float {
        val cellSize = 1f
        val cx = floor(x / cellSize).toInt()
        val cy = floor(y / cellSize).toInt()
        var sum = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val gx = cx + dx
                val gy = cy + dy
                if (noiseHash(gx, gy, 101) > 0.35f) continue
                val ix = (gx + noiseHash(gx, gy, 202)) * cellSize
                val iy = (gy + noiseHash(gx, gy, 303)) * cellSize
                val ddx = x - ix
                val ddy = y - iy
                val dist = sqrt(ddx * ddx + ddy * ddy)
                if (dist < 1f) {
                    val weight = noiseHash(gx, gy, 404) * 2f - 1f
                    sum += weight * cos(dist * Math.PI.toFloat() / 2f)
                }
            }
        }
        return min(1f, max(0f, sum * 0.5f + 0.5f))
    }

    data class Worley(val f1: Float, val f2: Float, val cellId: Float)

    /** [jitter] scales how far each cell's point strays from its slot centre:
     * 0 pins every point to a regular lattice, 1 scatters it anywhere in its
     * cell (the classic Worley arrangement, and the default). */
    fun worley(x: Float, y: Float, jitter: Float = 1f): Worley {
        val cx = floor(x).toInt()
        val cy = floor(y).toInt()
        var f1 = 1e9f
        var f2 = 1e9f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val gx = cx + dx
                val gy = cy + dy
                val px = gx + 0.5f + (noiseHash(gx, gy, 11) - 0.5f) * jitter
                val py = gy + 0.5f + (noiseHash(gx, gy, 22) - 0.5f) * jitter
                val ddx = x - px
                val ddy = y - py
                val d = ddx * ddx + ddy * ddy
                if (d < f1) { f2 = f1; f1 = d } else if (d < f2) { f2 = d }
            }
        }
        return Worley(sqrt(f1), sqrt(f2), noiseHash(cx, cy, 33))
    }

    fun plasma(x: Float, y: Float, t: Float): Float {
        val v = sin(x + t) + sin(y * 1.3f - t * 0.8f) +
            sin((x + y) * 0.7f + t * 1.4f) + sin(sqrt(x * x + y * y) * 1.1f - t * 1.7f)
        return v / 4f * 0.5f + 0.5f
    }

    fun turbulence(x: Float, y: Float, octaves: Int = 4, gain: Float = 0.5f, lacunarity: Float = 2f): Float {
        var sum = 0f
        var amp = 0.5f
        var freq = 1f
        for (i in 0 until octaves) {
            sum += (perlin2D(x * freq, y * freq) - 0.5f) * amp
            amp *= gain
            freq *= lacunarity
        }
        return min(1f, max(0f, sum + 0.5f))
    }

    // ---- fractal stacks -------------------------------------------------
    // All three walk the same octave loop — successively finer, successively
    // quieter layers of Perlin — and differ only in what they do with each
    // layer. That one choice is what separates rolling hills from cloud puffs
    // from mountain ridges, which is why they're worth having as separate
    // looks rather than one "fractal" setting.

    /** Perlin recentred to -0.5..0.5, the signed form the fractals stack. */
    private fun perlinSigned(x: Float, y: Float): Float = perlin2D(x, y) - 0.5f

    /** Fractal Brownian motion: plain summed octaves. Soft, cloudy, the
     * general-purpose one. */
    fun fbm(x: Float, y: Float, octaves: Int = 5, gain: Float = 0.5f, lacunarity: Float = 2f): Float {
        var sum = 0f; var amp = 0.5f; var freq = 1f; var norm = 0f
        for (i in 0 until octaves) {
            sum += perlinSigned(x * freq, y * freq) * amp
            norm += amp; amp *= gain; freq *= lacunarity
        }
        if (norm <= 0f) return 0.5f
        return (sum / (norm * 2f) + 0.5f).coerceIn(0f, 1f)
    }

    /** Ridged multifractal: each octave inverted and squared, so the zero
     * crossings become sharp creases. Reads as mountain ridges or lightning. */
    fun ridged(x: Float, y: Float, octaves: Int = 5, gain: Float = 0.5f, lacunarity: Float = 2f): Float {
        var sum = 0f; var amp = 0.5f; var freq = 1f; var norm = 0f
        for (i in 0 until octaves) {
            val n = 1f - abs(perlinSigned(x * freq, y * freq) * 2f)
            sum += n * n * amp
            norm += amp; amp *= gain; freq *= lacunarity
        }
        if (norm <= 0f) return 0f
        return (sum / norm).coerceIn(0f, 1f)
    }

    /** Billow: absolute value per octave, so troughs fold up into bulges.
     * Puffy, cumulus-like. */
    fun billow(x: Float, y: Float, octaves: Int = 5, gain: Float = 0.5f, lacunarity: Float = 2f): Float {
        var sum = 0f; var amp = 0.5f; var freq = 1f; var norm = 0f
        for (i in 0 until octaves) {
            sum += abs(perlinSigned(x * freq, y * freq) * 2f) * amp
            norm += amp; amp *= gain; freq *= lacunarity
        }
        if (norm <= 0f) return 0f
        return (sum / norm).coerceIn(0f, 1f)
    }

    /** Noise sampled through coordinates that are themselves displaced by
     * noise — the field drags itself sideways, giving the eroded, river-like
     * swirls a single pass can't produce. */
    fun domainWarp(x: Float, y: Float, octaves: Int = 4, gain: Float = 0.5f, lacunarity: Float = 2f, warp: Float = 4f): Float {
        val wx = fbm(x, y, 3, gain, lacunarity) - 0.5f
        val wy = fbm(x + 5.2f, y + 1.3f, 3, gain, lacunarity) - 0.5f
        return fbm(x + wx * warp, y + wy * warp, octaves, gain, lacunarity)
    }

    /** Smoothed lattice of hashed values. Perlin's blockier ancestor — coarser
     * and more obviously grid-born, which is the point of keeping it. */
    fun valueNoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val u = fade(x - xi); val v = fade(y - yi)
        val a = noiseHash(xi, yi, 7); val b = noiseHash(xi + 1, yi, 7)
        val c = noiseHash(xi, yi + 1, 7); val d = noiseHash(xi + 1, yi + 1, 7)
        return lerp(lerp(a, b, u), lerp(c, d, u), v)
    }

    /** Veins: turbulence folded through a sine, the classic marble recipe. */
    fun marble(x: Float, y: Float, veins: Float = 1f): Float {
        val t = turbulence(x, y) - 0.5f
        return (sin((x + t * 6f) * 1.5f * veins) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    /** Growth rings: distance from origin, wobbled by turbulence, wrapped. */
    fun wood(x: Float, y: Float, rings: Float = 1f): Float {
        val d = sqrt(x * x + y * y)
        val r = (d + (turbulence(x, y) - 0.5f) * 2f) * 2f * rings
        return r - floor(r)
    }

    /** Magnitude of the field's own gradient, which traces the flow lines a
     * curl field would follow — wispy and smoke-like. */
    fun curl(x: Float, y: Float): Float {
        val e = 0.1f
        val dx = perlin2D(x, y + e) - perlin2D(x, y - e)
        val dy = perlin2D(x + e, y) - perlin2D(x - e, y)
        return (sqrt(dx * dx + dy * dy) / (2f * e) * 0.5f).coerceIn(0f, 1f)
    }

    /** Static with its local average subtracted — a high-pass that suppresses
     * the clumps plain white noise leaves, which is what "blue" noise means:
     * energy pushed into the fine detail. */
    fun blueNoise(ix: Int, iy: Int, seed: Int): Float {
        val c = noiseHash(ix, iy, seed)
        var avg = 0f
        for (dy in -1..1) for (dx in -1..1) {
            if (dx != 0 || dy != 0) avg += noiseHash(ix + dx, iy + dy, seed)
        }
        return ((c - avg / 8f) * 1.5f + 0.5f).coerceIn(0f, 1f)
    }

    /** Octaves with 1/f falloff — heavier at the coarse end, the opposite
     * balance to [blueNoise]. */
    fun pinkNoise(x: Float, y: Float, octaves: Int = 5, gain: Float = 0.5f, lacunarity: Float = 2f): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        for (i in 0 until octaves) {
            sum += valueNoise(x * freq, y * freq) * amp
            norm += amp; amp *= gain; freq *= lacunarity
        }
        if (norm <= 0f) return 0.5f
        return (sum / norm).coerceIn(0f, 1f)
    }

    /**
     * [cellX]/[cellY] are raw grid-cell coordinates (only WHITE uses these directly,
     * one hashed value per cell by design — it's meant to look like per-pixel static
     * regardless of grid resolution). [physX]/[physY] are cell coordinates normalized
     * to a fixed reference column count (see [GridSources.sampleNoise]) so that the
     * *visual* feature size the other noise types produce, driven by [scale], stays
     * constant on screen as `cols` changes — otherwise the same `scale` value packs
     * more or fewer noise cells into the same physical width depending on `cols`,
     * and "changing Columns" would look like it's also changing the noise Scale.
     * [t] is the (speed-scaled) noise clock. [driftX]/[driftY] slide the whole
     * field along the user's chosen heading (see [GridSources.sampleNoise]);
     * every type takes the same offset, which is what makes one direction
     * control work across all of them. Each type used to bake its own drift
     * direction into these coordinates, which is why the field only ever
     * travelled one way no matter the setting.
     */
    /**
     * The per-type knobs, bundled rather than passed as eight more arguments.
     * Built once per frame in [GridSources.sampleNoise] and read by whichever
     * types care — each field is ignored by the types it means nothing to.
     */
    data class NoiseParams(
        val octaves: Int = 5,
        val gain: Float = 0.5f,
        val lacunarity: Float = 2f,
        val warp: Float = 4f,
        val cellJitter: Float = 1f,
        val veins: Float = 1f,
    )

    fun generateNoiseValue(
        type: NoiseType,
        cellX: Int,
        cellY: Int,
        physX: Float,
        physY: Float,
        t: Float,
        scale: Float,
        driftX: Float,
        driftY: Float,
        params: NoiseParams = NoiseParams(),
    ): Float {
        val nx = physX / scale + driftX
        val ny = physY / scale + driftY
        return when (type) {
            // Static is per-cell by design, so it scrolls in whole cells
            // rather than sub-cell amounts, and reseeds over time on top.
            NoiseType.WHITE -> noiseHash(
                cellX + floor(driftX).toInt(),
                cellY + floor(driftY).toInt(),
                floor(t * 8).toInt(),
            )
            NoiseType.BLUE -> blueNoise(
                cellX + floor(driftX).toInt(),
                cellY + floor(driftY).toInt(),
                floor(t * 8).toInt(),
            )
            NoiseType.PERLIN -> perlin2D(nx, ny)
            NoiseType.SIMPLEX -> simplex2D(nx, ny)
            NoiseType.VALUE -> valueNoise(nx, ny)
            NoiseType.PINK -> pinkNoise(nx, ny, params.octaves, params.gain, params.lacunarity)
            NoiseType.SPARSE -> sparseConvolution(nx, ny)
            NoiseType.ALLIGATOR -> {
                val w = worley(nx, ny, params.cellJitter)
                val edge = min(1f, (w.f2 - w.f1) * 3f)
                val mottle = w.cellId * 0.6f + 0.2f
                edge * 0.7f + mottle * 0.3f
            }
            NoiseType.CELLULAR -> min(1f, worley(nx, ny, params.cellJitter).f1)
            NoiseType.VORONOI -> worley(nx, ny, params.cellJitter).cellId
            NoiseType.CRACKLE -> {
                val w = worley(nx, ny, params.cellJitter)
                // Only the seam between cells survives; everything else is flat.
                (1f - min(1f, (w.f2 - w.f1) * 6f)).coerceIn(0f, 1f)
            }
            NoiseType.PLASMA -> plasma(nx, ny, t)
            NoiseType.TURBULENCE -> turbulence(nx, ny, params.octaves, params.gain, params.lacunarity)
            NoiseType.FBM -> fbm(nx, ny, params.octaves, params.gain, params.lacunarity)
            NoiseType.RIDGED -> ridged(nx, ny, params.octaves, params.gain, params.lacunarity)
            NoiseType.BILLOW -> billow(nx, ny, params.octaves, params.gain, params.lacunarity)
            NoiseType.DOMAIN_WARP -> domainWarp(nx, ny, params.octaves, params.gain, params.lacunarity, params.warp)
            NoiseType.MARBLE -> marble(nx, ny, params.veins)
            NoiseType.WOOD -> wood(nx, ny, params.veins)
            NoiseType.CURL -> curl(nx, ny)
        }
    }

    /** How fast the field slides along its heading, per unit of noise clock. */
    const val DRIFT_RATE = 0.25f

    const val NOISE_ASPECT_W = 4
    const val NOISE_ASPECT_H = 3

    /** Reference column count for [GridSources.sampleNoise]'s physical-coordinate
     * normalization — matches [AsciiSettings.CAMERA_DEFAULT_COLS] so the default
     * "Scale" behavior is unchanged from before this normalization was added. */
    const val REFERENCE_COLS = 40f
}
