package com.tbwk.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.os.Environment
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ExportedFiles(
    val directory: File,
    val summaryFile: File,
    val spectrumFile: File,
    val pdfFile: File,
)

object AndroidExporter {
    fun export(context: Context, fileBaseName: String, worksheet: Worksheet): ExportedFiles {
        val exportDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "tbwk-exports"
        ).apply { mkdirs() }

        val baseName = fileBaseName.substringBeforeLast('.')
        val summaryFile = File(exportDir, "${baseName}_summary.csv")
        val spectrumFile = File(exportDir, "${baseName}_spectrum.csv")
        val pdfFile = File(exportDir, "${baseName}_spectra.pdf")

        summaryFile.writeText(buildSummaryCsv(worksheet))
        spectrumFile.writeText(buildSpectrumCsv(worksheet))
        writeSpectraPdf(worksheet, pdfFile)

        return ExportedFiles(exportDir, summaryFile, spectrumFile, pdfFile)
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

    private fun writeSpectraPdf(worksheet: Worksheet, outputFile: File) {
        val document = PdfDocument()
        worksheet.measurements.forEachIndexed { index, measurement ->
            val pageInfo = PdfDocument.PageInfo.Builder(842, 595, index + 1).create()
            val page = document.startPage(pageInfo)
            drawMeasurementPage(page.canvas, measurement, index)
            document.finishPage(page)
        }
        outputFile.outputStream().use(document::writeTo)
        document.close()
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

        val xMin = measurement.xValues.minOrNull() ?: 0.0
        val xMax = measurement.xValues.maxOrNull() ?: 1.0
        val yMin = measurement.yValues.minOrNull() ?: 0.0
        val yMax = measurement.yValues.maxOrNull() ?: 1.0
        val yPadding = maxOf(0.05, (yMax - yMin) * 0.08)
        val plotYMin = yMin - yPadding
        val plotYMax = yMax + yPadding

        val path = Path()
        measurement.xValues.indices.forEach { point ->
            val xValue = measurement.xValues[point]
            val yValue = measurement.yValues[point]
            val scaledX = left + (((xValue - xMin) / (xMax - xMin).coerceAtLeast(0.00001)) * width).toFloat()
            val scaledY = bottom - (((yValue - plotYMin) / (plotYMax - plotYMin).coerceAtLeast(0.00001)) * height).toFloat()
            if (point == 0) path.moveTo(scaledX, scaledY) else path.lineTo(scaledX, scaledY)
        }
        canvas.drawPath(path, linePaint)

        repeat(6) { tick ->
            val fraction = tick / 5f
            val xValue = xMin + (xMax - xMin) * fraction
            val yValue = plotYMax - (plotYMax - plotYMin) * fraction
            canvas.drawText("%.1f".format(xValue), left + width * fraction - 16f, bottom + 26f, axisPaint)
            canvas.drawText("%.2f".format(yValue), 16f, top + height * fraction + 6f, axisPaint)
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
}
