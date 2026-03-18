package com.tbwk.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ExportedFiles(
    val directoryDisplayPath: String,
    val summaryFileName: String,
    val spectrumFileName: String,
    val pdfFileName: String,
    val summaryUri: Uri,
    val spectrumUri: Uri,
    val pdfUri: Uri,
)

object AndroidExporter {
    fun export(context: Context, fileBaseName: String, worksheet: Worksheet): ExportedFiles {
        val baseName = fileBaseName.substringBeforeLast('.')
        val sanitizedBaseName = sanitizeForFileName(baseName)
        val relativeDirectory = "${Environment.DIRECTORY_DOCUMENTS}/Nanodrop2000_viewer/$sanitizedBaseName/"
        val summaryFileName = "${baseName}_summary.csv"
        val spectrumFileName = "${baseName}_spectrum.csv"
        val pdfFileName = "${baseName}_spectra.pdf"

        val summaryUri = writePublicDocument(
            context = context,
            relativeDirectory = relativeDirectory,
            displayName = summaryFileName,
            mimeType = "text/csv",
            bytes = buildSummaryCsv(worksheet).toByteArray()
        )
        val spectrumUri = writePublicDocument(
            context = context,
            relativeDirectory = relativeDirectory,
            displayName = spectrumFileName,
            mimeType = "text/csv",
            bytes = buildSpectrumCsv(worksheet).toByteArray()
        )
        val pdfUri = writePublicDocument(
            context = context,
            relativeDirectory = relativeDirectory,
            displayName = pdfFileName,
            mimeType = "application/pdf",
            bytes = buildSpectraPdfBytes(worksheet)
        )

        return ExportedFiles(
            directoryDisplayPath = "Documents/Nanodrop2000_viewer/$sanitizedBaseName",
            summaryFileName = summaryFileName,
            spectrumFileName = spectrumFileName,
            pdfFileName = pdfFileName,
            summaryUri = summaryUri,
            spectrumUri = spectrumUri,
            pdfUri = pdfUri,
        )
    }

    private fun buildSummaryCsv(worksheet: Worksheet): String {
        val formatter = isoFormatter()
        val rows = worksheet.measurements.mapIndexed { index, measurement ->
            buildMap {
                put("measurement_index", index.toString())
                put("sample_name", measurement.title)
                put("measurement_time", formatter.format(measurement.time))
                put("method_title", measurement.properties.methodTitle)
                put("method_description", measurement.properties.methodDescription)
                put("x_label", measurement.xLabel)
                put("y_label", measurement.yLabel)
                put("point_count", measurement.xValues.size.toString())

                measurement.properties.properties.toSortedMap().forEach { (key, property) ->
                    put(key, formatDouble(property.value.value))
                    property.value.unit?.let { put("${key}_unit", it) }
                    property.value.factor?.let { put("${key}_factor", formatDouble(it)) }
                    property.rawValue?.let { put("${key}_raw", formatDouble(it.value)) }
                }
            }
        }
        return rowsToCsv(rows)
    }

    private fun buildSpectrumCsv(worksheet: Worksheet): String {
        val formatter = isoFormatter()
        val rows = buildList {
            worksheet.measurements.forEachIndexed { index, measurement ->
                measurement.xValues.indices.forEach { point ->
                    add(
                        mapOf(
                            "measurement_index" to index.toString(),
                            "measurement_time" to formatter.format(measurement.time),
                            "sample_name" to measurement.title,
                            "x_label" to measurement.xLabel,
                            "x_value" to formatDouble(measurement.xValues[point]),
                            "y_label" to measurement.yLabel,
                            "y_value" to formatDouble(measurement.yValues[point]),
                        )
                    )
                }
            }
        }
        return rowsToCsv(rows)
    }

    private fun rowsToCsv(rows: List<Map<String, String>>): String {
        val headers = rows.flatMap { it.keys }.distinct().sorted()
        val lines = buildList {
            add(headers.joinToString(","))
            rows.forEach { row ->
                add(headers.joinToString(",") { csvEscape(row[it].orEmpty()) })
            }
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun buildSpectraPdfBytes(worksheet: Worksheet): ByteArray {
        val document = PdfDocument()
        worksheet.measurements.forEachIndexed { index, measurement ->
            val pageInfo = PdfDocument.PageInfo.Builder(842, 595, index + 1).create()
            val page = document.startPage(pageInfo)
            drawMeasurementPage(page.canvas, measurement, index)
            document.finishPage(page)
        }
        return ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            document.close()
            output.toByteArray()
        }
    }

    private fun drawMeasurementPage(canvas: Canvas, measurement: Measurement, index: Int) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 16f
        }
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 226, 234)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(102, 75, 85, 99)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 108, 189)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        canvas.drawColor(Color.WHITE)
        canvas.drawText("${index + 1}. ${measurement.title}", 40f, 40f, titlePaint)
        canvas.drawText(
            "Method: ${measurement.properties.methodTitle}   Time: ${displayFormatter().format(measurement.time)}",
            40f,
            70f,
            bodyPaint
        )

        val left = 80f
        val top = 110f
        val width = 700f
        val height = 380f
        val right = left + width
        val bottom = top + height

        repeat(6) { tick ->
            val fraction = tick / 5f
            val x = left + width * fraction
            val y = top + height * fraction
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawRect(left, top, right, bottom, borderPaint)

        val xMax = 350.0
        val plotXMin = 220.0
        val plotXMax = xMax
        val plotYMin = 0.0
        val plotYMax = ((measurement.yValues.maxOrNull() ?: 0.0) + 1.0).coerceAtLeast(1.0)

        listOf(230.0, 260.0, 280.0).forEach { marker ->
            if (marker in plotXMin..plotXMax) {
                val markerX = left + (((marker - plotXMin) / (plotXMax - plotXMin).coerceAtLeast(0.00001)) * width).toFloat()
                canvas.drawLine(markerX, top, markerX, bottom, markerPaint)
            }
        }

        val path = Path()
        measurement.xValues.indices.forEach { point ->
            val xValue = measurement.xValues[point]
            val yValue = measurement.yValues[point]
            val scaledX = left + (((xValue - plotXMin) / (plotXMax - plotXMin).coerceAtLeast(0.00001)) * width).toFloat()
            val scaledY = bottom - (((yValue - plotYMin) / (plotYMax - plotYMin).coerceAtLeast(0.00001)) * height).toFloat()
            if (point == 0) path.moveTo(scaledX, scaledY) else path.lineTo(scaledX, scaledY)
        }
        canvas.drawPath(path, linePaint)

        repeat(6) { tick ->
            val fraction = tick / 5f
            val xValue = plotXMin + (plotXMax - plotXMin) * fraction
            val yValue = plotYMax - (plotYMax - plotYMin) * fraction
            canvas.drawText("%.1f".format(xValue), left + width * fraction - 16f, bottom + 26f, axisPaint)
            canvas.drawText("%.2f".format(yValue), 16f, top + height * fraction + 6f, axisPaint)
        }

        listOf(230, 260, 280).forEach { marker ->
            val markerX = left + (((marker - plotXMin) / (plotXMax - plotXMin).coerceAtLeast(0.00001)) * width).toFloat()
            canvas.drawText(marker.toString(), markerX - 10f, bottom + 44f, axisPaint)
        }

        canvas.drawText(measurement.xLabel, left + 250f, bottom + 55f, bodyPaint)
        canvas.drawText(measurement.yLabel, left, bottom + 80f, bodyPaint)
    }

    private fun formatDouble(value: Double): String {
        return if (value == value.toLong().toDouble()) "%.1f".format(value) else "%.15g".format(value)
    }

    private fun isoFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

    private fun displayFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

    private fun writePublicDocument(
        context: Context,
        relativeDirectory: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri {
        deleteExistingDocument(context, relativeDirectory, displayName)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
        }

        val collection = MediaStore.Files.getContentUri("external")
        val uri = context.contentResolver.insert(collection, values)
            ?: error("Failed to create $displayName in public Documents.")

        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Failed to write $displayName in public Documents.")

        return uri
    }

    private fun deleteExistingDocument(
        context: Context,
        relativeDirectory: String,
        displayName: String,
    ) {
        val collection = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(relativeDirectory, displayName)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val itemUri = Uri.withAppendedPath(collection, id.toString())
                context.contentResolver.delete(itemUri, null, null)
            }
        }
    }

    private fun sanitizeForFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "tbwk_export" }
}
