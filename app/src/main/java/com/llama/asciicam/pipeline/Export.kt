package com.llama.asciicam.pipeline

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export helpers: render the current [AsciiFrameResult] to a PNG bitmap
 * (mirrors the original tool's PNG snapshot export), a raw row-major
 * character grid (mirrors its .txt export), or record it live to an MP4
 * (see [VideoRecorder], which reuses [drawFrameInto] below).
 */
object Export {

    private fun timestampName(ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "asciicam_$ts.$ext"
    }

    /**
     * Draws one frame onto an arbitrary [Canvas] at [outWidth]x[outHeight] —
     * shared by [renderToBitmap] (a Bitmap-backed Canvas) and [VideoRecorder]
     * (a video encoder's input Surface's locked Canvas), so a recorded frame
     * looks exactly like a PNG snapshot of the same moment. [paint] and
     * [baselineRatio] are precomputed by the caller (typeface loading and
     * glyph-metrics measurement aren't free — a video recorder calls this
     * once per frame and shouldn't redo them every time).
     */
    internal fun drawFrameInto(
        canvas: Canvas,
        frame: AsciiFrameResult,
        geometry: GridGeometry,
        paint: Paint,
        baselineRatio: Float,
        outWidth: Int,
        outHeight: Int,
        backgroundArgb: Int,
    ) {
        canvas.drawColor(backgroundArgb)

        val contentW = geometry.cols * geometry.cellW
        val contentH = geometry.rows * geometry.rowPitch
        if (contentW <= 0f || contentH <= 0f) return
        val scale = minOf(outWidth / contentW, outHeight / contentH)
        val offsetX = (outWidth - contentW * scale) / 2f
        val offsetY = (outHeight - contentH * scale) / 2f

        val save = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        val cols = geometry.cols
        val rows = geometry.rows
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = y * cols + x
                val span = frame.span[idx]
                if (span <= 0) continue
                val ch = frame.chars[idx]
                if (ch == ' ') continue
                val fontSize = geometry.fontSizePx * span
                paint.textSize = fontSize
                paint.color = frame.colors[idx]
                val cx = (x + span / 2f) * geometry.cellW
                val cy = (y + span / 2f) * geometry.rowPitch
                canvas.drawText(ch.toString(), cx, cy - baselineRatio * fontSize, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    /** Renders the frame into a standalone bitmap at [outWidth]x[outHeight] pixels. */
    fun renderToBitmap(
        context: Context,
        frame: AsciiFrameResult,
        geometry: GridGeometry,
        font: FontChoice,
        backgroundArgb: Int,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val typeface = GlyphMetrics.typefaceFor(context, font)
        val baselineRatio = GlyphMetrics.measureBaselineOffsetRatio(typeface)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }
        drawFrameInto(canvas, frame, geometry, paint, baselineRatio, outWidth, outHeight, backgroundArgb)
        return bmp
    }

    /** Draws one [StippleFrameResult] onto an arbitrary [Canvas] — the Digital
     * Stippling counterpart of [drawFrameInto], reused for PNG export. */
    internal fun drawStippleFrameInto(
        canvas: Canvas,
        frame: StippleFrameResult,
        geometry: StippleGeometry,
        paint: Paint,
        outWidth: Int,
        outHeight: Int,
        backgroundArgb: Int,
    ) {
        canvas.drawColor(backgroundArgb)

        val cellSize = geometry.cellSize
        val contentW = geometry.cols * cellSize
        val contentH = geometry.rows * cellSize
        if (contentW <= 0f || contentH <= 0f) return
        val scale = minOf(outWidth / contentW, outHeight / contentH)
        val offsetX = (outWidth - contentW * scale) / 2f
        val offsetY = (outHeight - contentH * scale) / 2f

        val save = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        drawStippleField(canvas, frame, cellSize, paint)
        canvas.restoreToCount(save)
    }

    /**
     * The Digital Stippling field itself, in cell-space coordinates — shared
     * verbatim by the live viewfinder, PNG export and the video recorder, so
     * all three are the same picture rather than three drawing routines that
     * have to be kept in step.
     *
     * Two passes. First the necks: where [StippleFrameResult.mergeEast] /
     * [StippleFrameResult.mergeSouth] say two neighbors have come within
     * reach, a round-capped stroke between their centers fuses them. Then the
     * dots on top. Drawing necks underneath means a partly-grown one reads as
     * ink welling up between two dots rather than a bar laid across them.
     *
     * This approximates metaballs rather than evaluating a scalar field:
     * a true implementation samples the field per *pixel*, which at this grid
     * size is millions of evaluations a frame — far past what this CPU-only
     * pipeline can afford live. At the few-pixel reach dots merge over, a
     * capsule between centers is very close to the real isosurface anyway.
     */
    internal fun drawStippleField(
        canvas: Canvas,
        frame: StippleFrameResult,
        cellSize: Float,
        paint: Paint,
    ) {
        val cols = frame.cols
        val rows = frame.rows

        fun centerX(x: Int, i: Int) = (x + 0.5f + frame.offsetXFraction[i]) * cellSize
        fun centerY(y: Int, i: Int) = (y + 0.5f + frame.offsetYFraction[i]) * cellSize

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val i = y * cols + x
                if (!frame.visible[i]) continue
                val r1 = frame.radiusFraction[i] * cellSize
                val cx = centerX(x, i)
                val cy = centerY(y, i)

                val east = frame.mergeEast[i]
                if (east > 0f && x + 1 < cols) {
                    val j = i + 1
                    if (frame.visible[j]) {
                        val w = 2f * east * minOf(r1, frame.radiusFraction[j] * cellSize) * StipplePipeline.MERGE_NECK_FACTOR
                        if (w >= 0.5f) {
                            paint.color = frame.colors[i]
                            paint.strokeWidth = w
                            canvas.drawLine(cx, cy, centerX(x + 1, j), centerY(y, j), paint)
                        }
                    }
                }

                val south = frame.mergeSouth[i]
                if (south > 0f && y + 1 < rows) {
                    val j = i + cols
                    if (frame.visible[j]) {
                        val w = 2f * south * minOf(r1, frame.radiusFraction[j] * cellSize) * StipplePipeline.MERGE_NECK_FACTOR
                        if (w >= 0.5f) {
                            paint.color = frame.colors[i]
                            paint.strokeWidth = w
                            canvas.drawLine(cx, cy, centerX(x, j), centerY(y + 1, j), paint)
                        }
                    }
                }
            }
        }

        paint.style = Paint.Style.FILL
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val i = y * cols + x
                if (!frame.visible[i]) continue
                val radius = frame.radiusFraction[i] * cellSize
                if (radius <= 0f) continue
                paint.color = frame.colors[i]
                canvas.drawCircle(centerX(x, i), centerY(y, i), radius, paint)
            }
        }
    }

    /** Renders a Digital Stippling frame into a standalone bitmap at [outWidth]x[outHeight] pixels. */
    fun renderStippleToBitmap(
        frame: StippleFrameResult,
        geometry: StippleGeometry,
        backgroundArgb: Int,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        drawStippleFrameInto(canvas, frame, geometry, paint, outWidth, outHeight, backgroundArgb)
        return bmp
    }

    /** Saves [bitmap] as a PNG into MediaStore Pictures/AsciiCam. Returns true on success. */
    fun savePng(context: Context, bitmap: Bitmap): Boolean {
        val name = timestampName("png")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AsciiCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Writes the raw row-major character grid as plain text into MediaStore Documents/AsciiCam. */
    fun saveTxt(context: Context, frame: AsciiFrameResult): Boolean {
        val sb = StringBuilder(frame.cols * frame.rows + frame.rows)
        for (y in 0 until frame.rows) {
            for (x in 0 until frame.cols) {
                val idx = y * frame.cols + x
                sb.append(if (frame.span[idx] <= 0) ' ' else frame.chars[idx])
            }
            sb.append('\n')
        }
        val name = timestampName("txt")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/AsciiCam")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Files.getContentUri("external")
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
