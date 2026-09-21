package com.llama.asciicam.pipeline

/**
 * Ways of warping the sampling grid before anything is drawn from it.
 *
 * [displayName] rather than relying on the enum name: several of these are
 * two words, and a menu reading "DOMAIN_WARP" or "Zig_zag" looks like a bug.
 */
enum class DistortionType(val displayName: String) {
    NONE("None"),

    // --- the original six, ported 1:1 from the web tool's distortField() ---
    SINE("Sine"),
    CIRCULAR("Ripple"),
    NOISE("Noise"),
    TWIRL("Twirl"),
    PINCH("Pinch"),
    GLITCH("Glitch"),

    // --- lens shapes: the ways real optics bend a frame ---
    BARREL("Barrel"),
    PINCUSHION("Pincushion"),
    FISHEYE("Fisheye"),

    // --- geometric ---
    SHEAR("Shear"),
    ZIGZAG("Zigzag"),
    MOSAIC("Mosaic"),
    MIRROR("Mirror"),
    KALEIDOSCOPE("Kaleidoscope"),

    // --- rotational / flowing ---
    VORTEX("Vortex"),
    POLAR("Polar"),
    SMEAR("Smear"),
    WOBBLE("Wobble"),
}

/** Character-selection strategy: gradient ramp by luminance, or a literal word laid over the grid. */
enum class CharSource { RAMP, WORD }

/** How each cell's glyph is colored. */
enum class ColorMode { SOURCE, PALETTE, IMPOSTER, MONO }

/** Top-level render mode: ASCII characters, or "Digital Stippling" dots. */
enum class RenderMode { ASCII, STIPPLING }

/**
 * How edge-detected cells are colored, separately from [ColorMode] — the same
 * four choices, by the same names, so "Mono" means one picked color in both
 * places rather than being called something else on each.
 *
 * There's no "off": setting this to match [ColorMode] is what makes outlines
 * blend into the rest, and an extra mode meaning "same as the other setting"
 * only invites the two to disagree.
 */
enum class EdgeColorMode { SOURCE, PALETTE, IMPOSTER, MONO }

/** Where pixels for this frame come from. */
enum class MediaSource { CAMERA, IMAGE, NOISE }

/**
 * Procedural noise algorithms. The first eight are ported from the web tool's
 * `generateNoiseValue()`; the rest are the standard library of the field —
 * fractal stacks, cell patterns and the classic texture recipes built on top
 * of them. See [NoiseGenerators] for what each one actually does.
 */
enum class NoiseType(val displayName: String) {
    // --- gradient noise, the smooth workhorses ---
    PERLIN("Perlin"),
    SIMPLEX("Simplex"),
    VALUE("Value"),

    // --- fractal stacks of the above ---
    FBM("Fractal"),
    TURBULENCE("Turbulence"),
    RIDGED("Ridged"),
    BILLOW("Billow"),
    DOMAIN_WARP("Domain Warp"),

    // --- cell / distance patterns ---
    CELLULAR("Cellular"),
    VORONOI("Voronoi"),
    CRACKLE("Crackle"),
    ALLIGATOR("Alligator"),

    // --- texture recipes ---
    MARBLE("Marble"),
    WOOD("Wood"),
    CURL("Curl"),
    PLASMA("Plasma"),
    SPARSE("Sparse"),

    // --- unstructured static ---
    WHITE("White"),
    BLUE("Blue"),
    PINK("Pink"),
}

/**
 * Available typefaces — **monospaced only, deliberately**.
 *
 * ASCII art relies on every cell being the same width: the grid places each
 * glyph at a fixed column pitch, so a proportional face leaves ragged gaps
 * around narrow letters and crowds wide ones, and the image stops reading as
 * a grid at all. Proportional system families (sans, serif, casual, cursive)
 * were briefly offered here and looked wrong for exactly that reason.
 *
 * Italic variants were also briefly offered and removed: a slanted glyph
 * leans into its neighboring cell rather than staying inside its own column,
 * which reads as visibly wrong for the same reason a proportional face does.
 *
 * Variety instead comes from weight variants of the two monospaced families
 * Android guarantees, which stay fixed-pitch and upright.
 *
 * [systemFamily] is the family name passed to `Typeface.create`; null means
 * the bundled font resource instead.
 */
enum class FontChoice(
    val displayName: String,
    val systemFamily: String? = null,
    val systemStyle: Int = 0, // Typeface.NORMAL
) {
    MODERN_DOS("Modern DOS 8x8"),
    MONOSPACE("Mono", "monospace"),
    MONOSPACE_BOLD("Mono Bold", "monospace", 1), // Typeface.BOLD
    SERIF_MONO("Serif Mono", "serif-monospace"),
    SERIF_MONO_BOLD("Serif Mono Bold", "serif-monospace", 1),
}

/**
 * One hex color stop for the "palette" color mode.
 * `position` is stored implicitly by list order — the stops are always evenly
 * distributed across [0,1], matching the original tool's palette-stop editor.
 */
data class PaletteStop(val hex: String)

/**
 * Fixed 5-color "Imposter colors" palette (black/magenta/green/cyan/white),
 * selectable as [ColorMode.IMPOSTER] for the main glyph color and — mapped in
 * reverse order, so it never matches the main color at the same brightness —
 * as [EdgeColorMode.IMPOSTER] for edge-detected cells. Unlike [PaletteStop]
 * lists under [ColorMode.PALETTE], this one isn't user-editable.
 */
val IMPOSTER_PALETTE_STOPS = listOf(
    PaletteStop("#000000"),
    PaletteStop("#FA008B"),
    PaletteStop("#11E60D"),
    PaletteStop("#0EE1F3"),
    PaletteStop("#FFFFFF"),
)

/**
 * Central mutable-ish settings bag. Ranges/defaults mirror the original web
 * tool's `<input>` elements exactly (see task spec). Backed by Compose
 * `mutableStateOf` fields in [com.llama.asciicam.ui.SettingsViewModel]; this
 * class is the plain-data snapshot passed into the pure pipeline functions.
 */
data class AsciiSettings(
    // Top-level effect switch. STIPPLING uses its own independent settings
    // below (stipple*) rather than reusing the ASCII-specific ones (font,
    // charSource, edge detection, block merge, colorMode/paletteStops, etc.),
    // mirroring how edgePaletteStops is already independent from paletteStops.
    val renderMode: RenderMode = RenderMode.ASCII,

    // Grid geometry. Font size isn't a separate setting: AsciiPipeline.computeGridGeometry
    // solves for it so the grid always fills the live viewport width for the
    // current `cols` — cols is the only density/zoom control.
    val cols: Int = 110, // range 20..180 — capped below the web tool's 300 (see AsciiPipeline.MAX_COLS)
    val lineSpacingPercent: Int = 50, // range 20..150
    val charSpacingPercent: Int = 100, // range 50..300
    val font: FontChoice = FontChoice.MODERN_DOS,

    // Character source
    val charSource: CharSource = CharSource.RAMP,
    val rampString: String = " .:-=+*#%@░▒▓",
    val wordString: String = "#1MPO$",
    val fillChars: String = ".+$",
    val stableWord: Boolean = false,
    val wordHoldTimeSeconds: Float = 1.0f, // range 0.2..5.0

    // Edge detection
    val edgeDetectEnabled: Boolean = true,
    val edgeThreshold: Int = 35, // 0..100
    val edgeStrength: Int = 100, // 0..200
    // Defaults to SOURCE to match [colorMode]'s default, so outlines start out
    // looking like everything else — what the old "off" mode did.
    val edgeColorMode: EdgeColorMode = EdgeColorMode.SOURCE,
    val edgeColorArgb: Int = 0xFFFFFFFF.toInt(),
    val edgePaletteStops: List<PaletteStop> = listOf(PaletteStop("#000000"), PaletteStop("#5B8CFF"), PaletteStop("#FFFFFF")),

    // Distortion
    val distortionType: DistortionType = DistortionType.NONE,
    val distortionAmount: Int = 40, // 0..100
    val distortionSpeed: Int = 100, // -300..300

    // Per-type distortion controls. Each one is shown only for the types it
    // actually means something to (see SettingsPanel's distortionSection), so
    // a warp exposes its own shape rather than everything sharing one Amount.
    /** How much of the frame a centred effect covers, as a percent of its
     * shorter side. Applies to everything that works outward from a point:
     * ripple, twirl, pinch, the three lens shapes, kaleidoscope, vortex, polar. */
    val distortionRadiusPercent: Int = 100, // 10..200
    /** Where that centre sits, as a percent across and down the frame. */
    val distortionCenterXPercent: Int = 50, // 0..100
    val distortionCenterYPercent: Int = 50, // 0..100
    /** Mirrored wedges for kaleidoscope. */
    val distortionSides: Int = 6, // 3..16
    /** Wavelength for the wave-shaped warps: sine, ripple, zigzag, wobble.
     * Higher means more, tighter waves across the frame. */
    val distortionFrequencyPercent: Int = 100, // 10..400
    /** Chunk size for the blocky warps — glitch bands, mosaic tiles, and the
     * grain of the noise and smear displacements. */
    val distortionBlockPercent: Int = 30, // 1..100

    // Color adjustment
    val brightness: Int = 0, // -100..100
    val contrast: Int = 0, // -100..100
    val exposure: Int = 0, // -100..100
    val saturation: Int = 100, // 0..200
    val gamma: Int = 100, // 20..300
    // While on, background is the automatic average-luminance gray at 0%
    // saturation (see AsciiPipeline.backgroundArgbFor) instead of black.
    val invert: Boolean = false,

    // Color mode
    val colorMode: ColorMode = ColorMode.SOURCE,
    // The single ink color used by [ColorMode.MONO]. Applied verbatim — no
    // automatic flip when `invert` is on, since silently overriding a color
    // the user picked would be worse than letting them pick a dark one.
    val monoColorArgb: Int = 0xFFFFFFFF.toInt(),
    val paletteStops: List<PaletteStop> = listOf(PaletteStop("#000000"), PaletteStop("#5B8CFF"), PaletteStop("#FFFFFF")),

    // Block merge
    val merge2x2: Boolean = false,
    val merge3x3: Boolean = false,

    // Source
    val mediaSource: MediaSource = MediaSource.CAMERA,
    val useFrontCamera: Boolean = false,

    // Noise source controls
    val noiseType: NoiseType = NoiseType.PERLIN,
    val noiseScale: Float = 8f, // "feature size", range ~1..40
    val noiseSpeed: Float = 1f, // range 0..5
    /** Heading the noise field travels along, in degrees: 0 = right, 90 = up,
     * 180 = left, 270 = down. Applied uniformly to every noise type. */
    val noiseAngleDegrees: Int = 0, // 0..359
    val noiseFrozen: Boolean = false,

    // Per-type noise controls, shown only for the types they apply to.
    /** Layers in the fractal stacks (Fractal, Turbulence, Ridged, Billow,
     * Domain Warp, Pink). More layers means finer detail on top of the same
     * broad shape — and more work per cell, which is why it's capped. */
    val noiseOctaves: Int = 5, // 1..8
    /** How much each successive fractal layer contributes, as a percent.
     * Low values leave only the broad shape; high values make it grainy. */
    val noiseRoughness: Int = 50, // 0..100 -> gain 0..1
    /** How much finer each successive layer is than the last. 200 = each
     * layer is twice the frequency, the usual choice. */
    val noiseLacunarity: Int = 200, // 150..400 -> 1.5..4.0
    /** How far Domain Warp drags its own coordinates before sampling. */
    val noiseWarpPercent: Int = 100, // 0..300
    /** How far cell centres wander from their grid slots, for the cell
     * patterns (Cellular, Voronoi, Crackle, Alligator). At 0 they sit on a
     * regular lattice; at 100 they're scattered. */
    val noiseCellJitter: Int = 100, // 0..100
    /** Ring and vein spacing for Marble and Wood. */
    val noiseVeinPercent: Int = 100, // 10..400

    // ---- Digital Stippling (only used while renderMode == STIPPLING) ----
    // Dot grid density, analogous to `cols` above but its own control since
    // stippling's per-cell work (no glyph selection, no Sobel) is cheaper.
    val stippleDensity: Int = 90, // range 20..160
    // Dot size, as a percent of a cell's natural fit-the-grid size.
    val stippleDotScale: Int = 110, // range 30..200
    // Default: black background + bright dots (dot "ink" follows brightness —
    // brighter source = more/bigger dots, plain black elsewhere). On: white
    // background + dark dots, ink follows darkness instead — the traditional
    // stippled-portrait look (denser in shadows).
    val invertStippling: Boolean = false,
    val stippleColorMode: ColorMode = ColorMode.MONO,
    /** Stippling's own [ColorMode.MONO] ink color, independent of [monoColorArgb]. */
    val stippleMonoColorArgb: Int = 0xFFFFFFFF.toInt(),
    val stipplePaletteStops: List<PaletteStop> = listOf(PaletteStop("#000000"), PaletteStop("#5B8CFF"), PaletteStop("#FFFFFF")),
) {
    companion object {
        // Camera mode default cols is capped below the web default (110) to keep
        // the per-frame CPU pipeline (color adjust + Sobel + char selection +
        // block merge, all on the JVM, no SIMD) comfortably inside a frame
        // budget on mid-range phones. Users can still raise it via the slider.
        const val CAMERA_DEFAULT_COLS = 36
    }
}
