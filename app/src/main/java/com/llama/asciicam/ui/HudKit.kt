package com.llama.asciicam.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llama.asciicam.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The chrome's whole widget kit: a DOS terminal readout rendered in the same
 * pixel typeface the filter itself draws with — square white rules, hard
 * rectangles, uppercase pixel labels, bracketed numeric readouts, and inverted
 * (solid white on black) selection.
 *
 * The palette is deliberately restricted to exactly five colors — white,
 * black, and the app's "1mposter colors" triad (magenta/green/cyan, see
 * [com.llama.asciicam.pipeline.IMPOSTER_PALETTE_STOPS]) — so the interface
 * reads as branded with the same colors the art itself can be rendered in.
 * Unlike the previous hairline-HUD styling, structure here is carried by
 * *solid* white lines rather than tinted opacity ramps; opacity is reserved
 * for genuinely secondary or disabled states.
 */
object Hud {
    // Menu/panel backgrounds are solid black, full stop — panels are told
    // apart from the page behind them only by their border, not a lighter fill.
    val Bg = Color.Black
    val PanelBg = Color.Black

    /** Every frame, divider and rule: solid white, one device pixel-ish. */
    val Line = Color.White
    val LineDim = Color.White.copy(alpha = 0.35f)
    val TextPrimary = Color.White
    val TextDim = Color.White.copy(alpha = 0.72f)
    val TextFaint = Color.White.copy(alpha = 0.42f)
    val Accent = Color.White

    // The three "1mposter" hues, used only on the controls that genuinely
    // warrant a non-white accent: close/reset/record (magenta), redo and "on"
    // (green), undo (cyan).
    val Danger = Color(0xFFFA008B)
    val Positive = Color(0xFF11E60D)
    val Info = Color(0xFF0EE1F3)

    /**
     * The UI typeface: Modern DOS 9x16, the tall variant of the same family
     * the ASCII renderer draws with, so the chrome is visibly made of the
     * same pixels as the art.
     *
     * Deliberately *not* the 8x8 the renderer defaults to. On a 1600-unit em
     * this face advances 900 (9/16) with cap height 1000 and a full-em line
     * box (ascent 1200, descent -400), where the 8x8 advances 800 with cap
     * height 700 in a half-em box. So the same `fontSize` yields noticeably
     * taller letters and double the line height here — the sizes below are
     * tuned for these metrics and would need redoing if this ever changed
     * back.
     */
    val Pixel = FontFamily(Font(R.font.modern_dos_9x16))

    // FontWeight.Normal throughout, never Medium/Bold: this family ships a
    // single weight, so asking for a heavier one makes Android synthesize it
    // by smearing the glyphs sideways, which visibly softens a pixel face.
    // The scale is pegged to [Label], with each step's size set from the
    // reference layout's measured character widths relative to it.
    /** Uppercase pixel label — the sheet's base voice. */
    val Label = TextStyle(
        fontFamily = Pixel,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Normal,
    )

    /**
     * Section headers. Only a touch above [Label] — deliberately, even though
     * a header "wants" to be bigger: the longest of them ("02 // ▒ GRID &
     * FONT") has to survive on one line across the panel, and anything larger
     * wraps it. The genuinely long ones ("INPUT COLOR CORRECTION") still take
     * two lines, which is fine; these are the ones that shouldn't.
     */
    val LabelLarge = TextStyle(
        fontFamily = Pixel,
        fontSize = 15.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Normal,
    )
    val Readout = TextStyle(
        fontFamily = Pixel,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Normal,
    )

    /**
     * Text inside a framed control — segmented cells and buttons. Larger than
     * [Label]: these are the things being pressed, and the frame gives them
     * the room. Not larger still, though: the longest segment label
     * ("STIPPLING") has to fit a half-width cell on a narrow phone, and at
     * this size it lands at ~85% of the cell — about what the reference has.
     */
    val Control = TextStyle(
        fontFamily = Pixel,
        fontSize = 16.15.sp,
        letterSpacing = 0.4.sp,
        fontWeight = FontWeight.Normal,
    )
    val Title = TextStyle(
        fontFamily = Pixel,
        fontSize = 20.sp,
        letterSpacing = 2.5.sp,
        fontWeight = FontWeight.Normal,
    )

    /** CP437 shade block, used as the section-header bullet and reset flourish. */
    const val BLOCK = "▒"

    /** Standard control height — every framed box lines up on this. */
    val ControlHeight = 40.dp

    /** How far a section's controls are indented past its header, so the
     * numbered headers hang into the margin as they do in the reference. */
    val ContentIndent = 8.dp
    /** Border weight for every frame in the kit. */
    val Stroke = 1.dp
}

/** Numbered section title: `00  //  ▒ EFFECT`. */
@Composable
fun HudSectionHeader(index: Int, title: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Hud.TextDim)) { append("%02d".format(index)) }
            // Single spaces, not double: those four extra characters are what
            // pushed "GRID & FONT" onto a second line.
            withStyle(SpanStyle(color = Hud.TextDim)) { append(" // ") }
            withStyle(SpanStyle(color = Hud.TextPrimary)) { append("${Hud.BLOCK} ") }
            withStyle(SpanStyle(color = Hud.TextPrimary)) { append(title.uppercase(Locale.US)) }
        },
        style = Hud.LabelLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp),
    )
}

/**
 * One section's controls. Deliberately frameless: in this styling the
 * numbered header is what separates sections, and boxing the contents as well
 * would put two competing rectangles around every control. Indented by
 * [Hud.ContentIndent] so the headers hang left of their own contents.
 */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Hud.ContentIndent, end = Hud.ContentIndent, bottom = 6.dp),
    ) { content() }
}

/**
 * Slider drawn as a plain measuring rule: one full-width line, a tick at each
 * end and at the midpoint, and a tall block thumb. Hand-drawn rather than a
 * restyled Material Slider so it matches the rest of the kit exactly and
 * doesn't depend on which Material3 slot API this Compose version ships.
 *
 * The readout doubles as a text field — tap the number to type an exact value,
 * which is then clamped to `min..max`.
 */
@Composable
fun HudSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    valueLabel: (Float) -> String = { "%.2f".format(Locale.US, it) },
    onChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextPrimary)
            HudValueReadout(value = value, min = min, max = max, valueLabel = valueLabel, onChange = onChange)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .trackDragInput { f -> onChange(min + f * (max - min)) },
        ) {
            val strokePx = with(density) { Hud.Stroke.toPx() }
            val midY = size.height / 2f
            // The rule itself, inset by half a stroke so the end ticks sit
            // fully inside the canvas instead of being clipped in half.
            val x0 = strokePx / 2f
            val x1 = size.width - strokePx / 2f
            drawLine(Hud.Line, Offset(x0, midY), Offset(x1, midY), strokeWidth = strokePx)

            val tickH = size.height * 0.45f
            for (f in listOf(0f, 0.5f, 1f)) {
                val x = x0 + (x1 - x0) * f
                drawLine(
                    color = Hud.Line,
                    start = Offset(x, midY - tickH / 2f),
                    end = Offset(x, midY + tickH / 2f),
                    strokeWidth = strokePx,
                )
            }

            val tw = with(density) { 3.dp.toPx() }
            val tx = (x0 + (x1 - x0) * fraction).coerceIn(tw / 2f, size.width - tw / 2f)
            drawRect(
                color = Hud.Accent,
                topLeft = Offset(tx - tw / 2f, 0f),
                size = Size(tw, size.height),
            )
        }
    }
}

/** Bracketed `[ 036 ]` readout that becomes a text field when tapped. */
@Composable
private fun HudValueReadout(
    value: Float,
    min: Float,
    max: Float,
    valueLabel: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    // A TextFieldValue rather than a String: a String carries no caret, so the
    // field opens with it at position zero and every edit starts by walking it
    // to the end. This lets the caret start where typing actually begins.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    // onFocusChanged fires once with isFocused=false as the field attaches,
    // before focus is requested; without this latch that first callback would
    // close the editor before a character could be typed.
    var hasTakenFocus by remember { mutableStateOf(false) }

    fun commit() {
        if (!editing) return
        editing = false
        val parsed = draft.text.trim().replace(',', '.').toFloatOrNull()
        if (parsed != null && parsed.isFinite()) onChange(parsed.coerceIn(min, max))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("[", style = Hud.Readout, color = Hud.TextDim)
        Spacer(Modifier.width(5.dp))
        if (editing) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = Hud.Readout.copy(color = Hud.Accent, textAlign = TextAlign.End),
                cursorBrush = SolidColor(Hud.Accent),
                keyboardOptions = KeyboardOptions(
                    // A number pad has no minus key on most Android keyboards,
                    // so ranges that go negative use the text keyboard instead.
                    keyboardType = if (min < 0f) KeyboardType.Text else KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .width(70.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) hasTakenFocus = true
                        else if (hasTakenFocus) commit()
                    },
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            DisposableEffect(Unit) { onDispose { commit() } }
        } else {
            Text(
                valueLabel(value),
                style = Hud.Readout,
                color = Hud.Accent,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    val text = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
                    // Caret parked after the last digit, so backspace deletes
                    // the value straight away instead of doing nothing.
                    draft = TextFieldValue(text, selection = TextRange(text.length))
                    hasTakenFocus = false
                    editing = true
                },
            )
        }
        Spacer(Modifier.width(5.dp))
        Text("]", style = Hud.Readout, color = Hud.TextDim)
    }
}

/**
 * Tap-to-jump plus drag-to-scrub on a horizontal track, reporting position as
 * a 0..1 fraction of the track's width.
 *
 * One gesture loop rather than a `detectTapGestures` + `detectHorizontalDrag`
 * pair: stacked detectors on the same element race for the same pointer, and
 * the tap detector's press handler can swallow the down event the drag
 * detector needs. Taking the down here and then dragging from it makes both
 * behaviours come from the same stream. Width comes from the pointer scope's
 * own `size`, which is always the laid-out size — not from a value captured
 * during a draw pass, which wouldn't exist yet on a first touch.
 */
private fun Modifier.trackDragInput(onFraction: (Float) -> Unit): Modifier =
    pointerInput(Unit) {
        val width = { size.width.toFloat().coerceAtLeast(1f) }
        awaitEachGesture {
            val down = awaitFirstDown()
            onFraction((down.position.x / width()).coerceIn(0f, 1f))
            down.consume()
            drag(down.id) { change ->
                onFraction((change.position.x / width()).coerceIn(0f, 1f))
                change.consume()
            }
        }
    }

/** On/off control: label, square check box, and the state spelled out. */
@Composable
fun HudToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onChange(!checked) }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudCheckbox(checked)
            Spacer(Modifier.width(10.dp))
            Text(
                if (checked) "ON" else "OFF",
                style = Hud.Readout,
                color = if (checked) Hud.TextPrimary else Hud.TextDim,
            )
        }
    }
}

/**
 * Square check control: an outlined box that fills solid white when on.
 * Paired with the ON/OFF readout so state is legible two ways — the filled
 * block carries it at a glance, the word removes any doubt.
 */
@Composable
fun HudCheckbox(checked: Boolean) {
    val density = LocalDensity.current
    Canvas(Modifier.size(16.dp)) {
        val strokePx = with(density) { Hud.Stroke.toPx() }
        val inset = strokePx / 2f
        val box = Size(size.width - inset * 2, size.height - inset * 2)
        if (checked) {
            drawRect(Hud.Accent, topLeft = Offset(inset, inset), size = box)
        } else {
            drawRect(Hud.Line, topLeft = Offset(inset, inset), size = box, style = Stroke(width = strokePx))
        }
    }
}

/**
 * Horizontal segmented selector — one frame around the whole run with hairline
 * dividers between cells (rather than a gapped row of separate boxes), so it
 * reads as a single control. The selected cell inverts to solid white.
 */
@Composable
fun <T> HudSegmented(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Hud.ControlHeight)
            .border(Hud.Stroke, Hud.Line),
    ) {
        options.forEachIndexed { i, (label, value) ->
            if (i > 0) {
                Box(Modifier.width(Hud.Stroke).fillMaxHeight().background(Hud.Line))
            }
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) Hud.Accent else Color.Transparent)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(Locale.US),
                    style = Hud.Control,
                    color = if (isSelected) Color.Black else Hud.TextPrimary,
                )
            }
        }
    }
}

/** Full-width framed action button. [tint] colors the label only — every
 * button keeps the same white frame, so an accent reads as emphasis on the
 * action rather than a differently-shaped control. */
@Composable
fun HudButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    tint: Color = Hud.TextPrimary,
    style: TextStyle = Hud.Control,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Hud.ControlHeight)
            .background(if (emphasized) Hud.Accent else Color.Transparent)
            .border(Hud.Stroke, Hud.Line)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(Locale.US),
            style = style,
            color = if (emphasized) Color.Black else tint,
            maxLines = 1,
        )
    }
}

/** [Hud.Control] shrunk to fit a two-up button row — a pair of half-width
 * buttons has barely half the room a full-width one does, and at the normal
 * size a label like "PNG EXPORT" is silently clipped mid-word. */
val HudButtonCompact: TextStyle = Hud.Control.copy(fontSize = 12.5.sp)

/** Labelled dropdown styled as a framed readout field with a caret glyph. */
@Composable
fun <T> HudDropdown(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Hud.ControlHeight)
                    .border(Hud.Stroke, Hud.Line)
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(display(selected).uppercase(Locale.US), style = Hud.Readout, color = Hud.TextPrimary)
                Text("▼", style = Hud.Readout, color = Hud.TextPrimary)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Hud.PanelBg),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                display(option).uppercase(Locale.US),
                                style = Hud.Readout,
                                color = if (option == selected) Hud.Accent else Hud.TextDim,
                            )
                        },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

/** Single-line text input styled to match the readouts. */
@Composable
fun HudTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label.uppercase(Locale.US), style = Hud.Label, color = Hud.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Hud.ControlHeight)
                .border(Hud.Stroke, Hud.Line)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Hud.Readout.copy(color = Hud.TextPrimary),
                cursorBrush = SolidColor(Hud.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A swatch showing [argb] that opens a full picker when tapped — the entry
 * point to [HudColorPickerDialog] wherever a color is editable.
 */
@Composable
fun HudColorSwatch(
    argb: Int,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    onChange: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .background(Color(argb))
            .border(Hud.Stroke, Hud.Line)
            .clickable { picking = true },
    )
    if (picking) {
        HudColorPickerDialog(
            initial = argb,
            onDismiss = { picking = false },
            onConfirm = { picked -> onChange(picked); picking = false },
        )
    }
}

/**
 * The picker itself: a saturation/brightness field with a hue strip beneath
 * it, the arrangement every image editor uses, plus a hex box for typing an
 * exact value.
 *
 * Hue is separated from the square deliberately — the three dimensions of a
 * color don't fit on a flat surface, and splitting off the one that's
 * circular (hue) leaves the two that aren't (saturation, brightness) as a
 * plain XY area you can sweep with a thumb.
 */
@Composable
fun HudColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val hsv = remember(initial) { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by remember(initial) { mutableStateOf(hsv[0]) }
    var sat by remember(initial) { mutableStateOf(hsv[1]) }
    var value by remember(initial) { mutableStateOf(hsv[2]) }

    val current = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    var hexDraft by remember(initial) { mutableStateOf(String.format(Locale.US, "#%06X", initial and 0xFFFFFF)) }
    // Typing in the hex box drives the square; dragging the square rewrites
    // the box. Without this the two halves of the dialog would disagree.
    fun syncHexFromHsv(color: Int) {
        hexDraft = String.format(Locale.US, "#%06X", color and 0xFFFFFF)
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Hud.PanelBg)
                .border(Hud.Stroke, Hud.Line)
                .padding(16.dp),
        ) {
            Column {
                Text("COLOR", style = Hud.LabelLarge, color = Hud.TextPrimary)
                Spacer(Modifier.height(12.dp))

                // Saturation across, brightness down — the standard layout.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .border(Hud.Stroke, Hud.Line)
                        .pointerInput(Unit) {
                            fun report(x: Float, y: Float) {
                                sat = (x / size.width.toFloat()).coerceIn(0f, 1f)
                                value = 1f - (y / size.height.toFloat()).coerceIn(0f, 1f)
                                syncHexFromHsv(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                report(down.position.x, down.position.y)
                                down.consume()
                                drag(down.id) { change ->
                                    report(change.position.x, change.position.y)
                                    change.consume()
                                }
                            }
                        },
                ) {
                    val pure = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    // White -> full hue left to right, then black over the top:
                    // two gradients multiply out to the usual SV square.
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

                    val px = sat * this.size.width
                    val py = (1f - value) * this.size.height
                    // Ringed in both black and white so the marker stays
                    // visible over any color underneath it.
                    drawCircle(Color.Black, radius = 7f, center = Offset(px, py), style = Stroke(width = 3f))
                    drawCircle(Color.White, radius = 7f, center = Offset(px, py), style = Stroke(width = 1.5f))
                }

                Spacer(Modifier.height(12.dp))

                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .border(Hud.Stroke, Hud.Line)
                        .pointerInput(Unit) {
                            fun report(x: Float) {
                                hue = (x / size.width.toFloat()).coerceIn(0f, 1f) * 360f
                                syncHexFromHsv(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                report(down.position.x)
                                down.consume()
                                drag(down.id) { change -> report(change.position.x); change.consume() }
                            }
                        },
                ) {
                    drawRect(
                        Brush.horizontalGradient(
                            (0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) },
                        ),
                    )
                    val hx = (hue / 360f) * this.size.width
                    drawRect(
                        Color.White,
                        topLeft = Offset(hx - 1.5f, 0f),
                        size = Size(3f, this.size.height),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(Hud.ControlHeight)
                            .background(Color(current))
                            .border(Hud.Stroke, Hud.Line),
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        HudTextField("Hex", hexDraft) { typed ->
                            hexDraft = typed
                            val parsed = runCatching {
                                AndroidColor.parseColor(if (typed.startsWith("#")) typed else "#$typed")
                            }.getOrNull()
                            if (parsed != null) {
                                val out = FloatArray(3)
                                AndroidColor.colorToHSV(parsed, out)
                                hue = out[0]; sat = out[1]; value = out[2]
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        HudButton("Cancel", style = HudButtonCompact, onClick = onDismiss)
                    }
                    Box(Modifier.weight(1f)) {
                        HudButton("OK", emphasized = true, style = HudButtonCompact) { onConfirm(current) }
                    }
                }
            }
        }
    }
}

/** Small dim caption used for hints under a control. */
@Composable
fun HudCaption(text: String) {
    Text(
        text.uppercase(Locale.US),
        style = Hud.Label.copy(fontSize = 12.sp, letterSpacing = 0.3.sp),
        color = Hud.TextDim,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/** Formats a float the way the reference sheet renders telemetry: zero-padded. */
fun hudInt(value: Float, digits: Int = 3): String =
    value.roundToInt().let {
        val s = kotlin.math.abs(it).toString().padStart(digits, '0')
        if (it < 0) "-$s" else s
    }
