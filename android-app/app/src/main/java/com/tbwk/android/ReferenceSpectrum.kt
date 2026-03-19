package com.tbwk.android

import android.content.Context

enum class ReferenceNormalizationMode(val label: String) {
    PEAK_NORMALIZE("Peak Normalize"),
    AREA_NORMALIZE("Area Normalize"),
    FIT_TO_SAMPLE("Fit To Sample"),
}

data class ReferenceSpectrum(
    val id: String,
    val shortTitle: String,
    val title: String,
    val xValues: List<Double>,
    val yValues: List<Double>,
    val xUnits: String,
    val yUnits: String,
)

object ReferenceSpectrumLibrary {
    fun loadBundledSpectra(context: Context): List<ReferenceSpectrum> {
        val assetManager = context.assets
        val names = assetManager.list("reference_spectra").orEmpty()
            .filter { it.endsWith(".jdx", ignoreCase = true) }
            .sorted()

        return names.mapNotNull { fileName ->
            runCatching {
                assetManager.open("reference_spectra/$fileName").use { input ->
                    JCAMPDXParser.parse(
                        raw = input.bufferedReader().readText(),
                        fallbackId = fileName.substringBeforeLast('.')
                    )
                }
            }.getOrNull()
        }
    }
}

private object JCAMPDXParser {
    fun parse(raw: String, fallbackId: String): ReferenceSpectrum {
        val lines = raw.lineSequence().toList()
        var title = fallbackId.replace('_', ' ')
        var xUnits = "Wavelength (nm)"
        var yUnits = "Normalized reference"
        val xValues = mutableListOf<Double>()
        val yValues = mutableListOf<Double>()
        var inXYSection = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("##TITLE=") -> title = trimmed.removePrefix("##TITLE=")
                trimmed.startsWith("##XUNITS=") -> xUnits = trimmed.removePrefix("##XUNITS=")
                trimmed.startsWith("##YUNITS=") -> yUnits = trimmed.removePrefix("##YUNITS=")
                trimmed.startsWith("##XYPOINTS=") -> inXYSection = true
                trimmed.startsWith("##END=") -> break
                inXYSection -> {
                    val parts = trimmed.split(",").map { it.trim() }
                    val x = parts.getOrNull(0)?.toDoubleOrNull()
                    val y = parts.getOrNull(1)?.toDoubleOrNull()
                    if (x != null && y != null) {
                        xValues += x
                        yValues += y
                    }
                }
            }
        }

        require(xValues.isNotEmpty() && xValues.size == yValues.size) {
            "Could not parse reference spectrum $fallbackId"
        }

        return ReferenceSpectrum(
            id = fallbackId,
            shortTitle = shortTitleFor(fallbackId),
            title = title,
            xValues = xValues,
            yValues = yValues,
            xUnits = xUnits,
            yUnits = yUnits,
        )
    }

    private fun shortTitleFor(id: String): String {
        return when (id) {
            "dsDNA" -> "DNA"
            "RNA" -> "RNA"
            "guanidine_hydrochloride_GuHCl" -> "GuHCl"
            "guanidine_thiocyanate_GTC" -> "GTC"
            "protein_BSA" -> "BSA"
            "phenol" -> "Phenol"
            "ethanol" -> "Ethanol"
            "EDTA" -> "EDTA"
            else -> id
        }
    }
}
