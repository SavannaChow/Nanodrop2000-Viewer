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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class ViewerUiState(
    val fileName: String? = null,
    val worksheet: Worksheet? = null,
    val selectedIndex: Int = 0,
    val referenceSpectra: List<ReferenceSpectrum> = emptyList(),
    val selectedReferenceIds: Set<String> = emptySet(),
    val referenceNormalizationMode: ReferenceNormalizationMode = ReferenceNormalizationMode.PEAK_NORMALIZE,
    val availableUpdate: AppUpdateInfo? = null,
    val latestVersion: String? = null,
    val updateStatusMessage: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val exportMessage: String? = null,
)

class MainViewModel : ViewModel() {
    var uiState by mutableStateOf(ViewerUiState())
        private set
    private var hasCheckedForUpdates = false
    private var transientMessageJob: Job? = null

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
                val firstSortedIndex = worksheet.measurements
                    .mapIndexed { index, measurement -> index to measurement }
                    .minWithOrNull(compareBy<Pair<Int, Measurement>> { it.second.time }.thenBy { it.first })
                    ?.first ?: 0
                uiState = ViewerUiState(
                    fileName = fileName,
                    worksheet = worksheet,
                    selectedIndex = firstSortedIndex,
                    referenceSpectra = ReferenceSpectrumLibrary.loadBundledSpectra(context),
                    selectedReferenceIds = emptySet(),
                    referenceNormalizationMode = ReferenceNormalizationMode.PEAK_NORMALIZE,
                    availableUpdate = uiState.availableUpdate,
                    updateStatusMessage = uiState.updateStatusMessage,
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
        return worksheet.measurements
            .mapIndexed { index, measurement -> index to measurement }
            .sortedWith(compareBy<Pair<Int, Measurement>> { it.second.time }.thenBy { it.first })
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

    fun exportCurrentWorksheet(context: Context, directoryUri: Uri) {
        val worksheet = uiState.worksheet ?: return
        val fileName = uiState.fileName ?: "tbwk_export"

        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null, exportMessage = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    AndroidExporter.export(context, directoryUri, fileName, worksheet)
                }
            }.onSuccess { exported ->
                uiState = uiState.copy(
                    isLoading = false,
                    exportMessage = "Exported files to ${exported.directoryDisplayPath}",
                )
                clearTransientMessagesLater()
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
        val items = linkedMapOf(
            "Sample" to measurement.title,
        )

        measurement.properties.properties.toSortedMap().forEach { (key, property) ->
            val unit = property.value.unit?.let { " $it" }.orEmpty()
            items[key] = if (key == "Nucleic Acid") {
                "${property.value.value.roundToInt()}$unit"
            } else {
                "${formatDouble(property.value.value)}$unit"
            }
        }

        val preferredOrder = listOf(
            "Sample",
            "Nucleic Acid",
            "260/280",
            "260/230",
            "A260",
            "A280",
        )

        val ordered = mutableListOf<Pair<String, String>>()
        preferredOrder.forEach { key ->
            items.remove(key)?.let { value -> ordered += key to value }
        }

        items.forEach { (key, value) ->
            ordered += key to value
        }

        return ordered
    }

    fun selectedMeasurement(): Measurement? {
        val worksheet = uiState.worksheet ?: return null
        return worksheet.measurements.getOrNull(uiState.selectedIndex)
    }

    fun selectedReferenceSpectra(): List<ReferenceSpectrum> {
        return uiState.referenceSpectra.filter { it.id in uiState.selectedReferenceIds }
    }

    fun toggleReferenceSpectrum(id: String) {
        val updated = uiState.selectedReferenceIds.toMutableSet()
        if (!updated.add(id)) {
            updated.remove(id)
        }
        uiState = uiState.copy(selectedReferenceIds = updated)
    }

    fun setReferenceNormalizationMode(mode: ReferenceNormalizationMode) {
        uiState = uiState.copy(referenceNormalizationMode = mode)
    }

    fun checkForUpdatesIfNeeded() {
        if (hasCheckedForUpdates) return
        hasCheckedForUpdates = true
        checkForUpdates(showNoUpdateMessage = false)
    }

    fun checkForUpdates(showNoUpdateMessage: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                }
            }.onSuccess { result ->
                uiState = when {
                    result.update != null -> uiState.copy(
                        availableUpdate = result.update,
                        latestVersion = result.latestVersion,
                        updateStatusMessage = "Update ${result.update.version} is available.",
                    )
                    showNoUpdateMessage -> uiState.copy(
                        availableUpdate = null,
                        latestVersion = result.latestVersion,
                        updateStatusMessage = "You are up to date.",
                    )
                    else -> uiState.copy(
                        availableUpdate = null,
                        latestVersion = result.latestVersion,
                    )
                }
                if (result.update == null && showNoUpdateMessage) {
                    clearTransientMessagesLater()
                }
            }.onFailure {
                if (showNoUpdateMessage) {
                    uiState = uiState.copy(updateStatusMessage = "Unable to check for updates.")
                    clearTransientMessagesLater()
                }
            }
        }
    }

    private fun clearTransientMessagesLater() {
        transientMessageJob?.cancel()
        val currentUpdateMessage = uiState.updateStatusMessage
        val currentExportMessage = uiState.exportMessage
        transientMessageJob = viewModelScope.launch {
            delay(3500)
            uiState = uiState.copy(
                updateStatusMessage = if (uiState.updateStatusMessage == currentUpdateMessage) null else uiState.updateStatusMessage,
                exportMessage = if (uiState.exportMessage == currentExportMessage) null else uiState.exportMessage,
            )
        }
    }

    private fun formatDouble(value: Double): String {
        return "%.2f".format(value)
    }
}
