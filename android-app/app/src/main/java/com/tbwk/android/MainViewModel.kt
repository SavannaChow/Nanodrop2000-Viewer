package com.tbwk.android

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ViewerUiState(
    val fileName: String? = null,
    val worksheet: Worksheet? = null,
    val selectedIndex: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val exportMessage: String? = null,
)

class MainViewModel : ViewModel() {
    var uiState by mutableStateOf(ViewerUiState())
        private set

    fun loadUri(context: Context, uri: Uri) {
        val contentResolver = context.contentResolver
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null, exportMessage = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(TbwkParser::parse)
                        ?: error("Could not open selected file.")
                }
            }.onSuccess { worksheet ->
                val fileName = DocumentFile.fromSingleUri(context, uri)?.name
                    ?: uri.lastPathSegment
                    ?: "Selected file"
                uiState = ViewerUiState(
                    fileName = fileName,
                    worksheet = worksheet,
                    selectedIndex = 0,
                    isLoading = false,
                    errorMessage = null,
                    exportMessage = null,
                )
            }.onFailure { throwable ->
                uiState = uiState.copy(
                    isLoading = false,
                    errorMessage = throwable.message ?: "Failed to read TBWK file.",
                    exportMessage = null,
                )
            }
        }
    }

    fun visibleMeasurements(): List<Pair<Int, Measurement>> {
        val worksheet = uiState.worksheet ?: return emptyList()
        return worksheet.measurements.mapIndexed { index, measurement -> index to measurement }
    }

    fun selectSample(index: Int) {
        val worksheet = uiState.worksheet ?: return
        if (index !in worksheet.measurements.indices) return
        uiState = uiState.copy(selectedIndex = index)
    }

    fun moveSelection(delta: Int) {
        val visible = visibleMeasurements()
        if (visible.isEmpty()) return
        val currentPosition = visible.indexOfFirst { it.first == uiState.selectedIndex }.let {
            if (it < 0) 0 else it
        }
        val nextPosition = (currentPosition + delta).coerceIn(0, visible.lastIndex)
        selectSample(visible[nextPosition].first)
    }

    fun exportCurrentWorksheet(context: Context) {
        val worksheet = uiState.worksheet ?: return
        val fileName = uiState.fileName ?: "tbwk_export"

        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null, exportMessage = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    AndroidExporter.export(context, fileName, worksheet)
                }
            }.onSuccess { exported ->
                uiState = uiState.copy(
                    isLoading = false,
                    exportMessage = "Exported files to ${exported.directoryDisplayPath}",
                )
            }.onFailure { throwable ->
                uiState = uiState.copy(
                    isLoading = false,
                    errorMessage = throwable.message ?: "Failed to export CSV/PDF.",
                )
            }
        }
    }

    fun summaryItems(): List<Pair<String, String>> {
        val measurement = selectedMeasurement() ?: return emptyList()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        val items = mutableListOf(
            "Sample" to measurement.title,
            "Time" to formatter.format(measurement.time),
        )

        measurement.properties.properties.toSortedMap().forEach { (key, property) ->
            val unit = property.value.unit?.let { " $it" }.orEmpty()
            items += key to "${formatDouble(property.value.value)}$unit"
        }

        return items
    }

    fun selectedMeasurement(): Measurement? {
        val worksheet = uiState.worksheet ?: return null
        return worksheet.measurements.getOrNull(uiState.selectedIndex)
    }

    private fun formatDouble(value: Double): String {
        return "%.2f".format(value)
    }
}
