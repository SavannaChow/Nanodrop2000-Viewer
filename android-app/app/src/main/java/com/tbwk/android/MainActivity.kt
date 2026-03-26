package com.tbwk.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var lastHandledUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                val picker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.loadUri(this, it) }
                }
                val exportPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    uri?.let {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }
                        viewModel.exportCurrentWorksheet(this, it)
                    }
                }
                val saveEditedPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri ->
                    uri?.let { viewModel.saveEditedWorksheet(this, it) }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F7FB)
                ) {
                    ViewerScreen(
                        viewModel = viewModel,
                        onOpenFile = { picker.launch(arrayOf("*/*")) },
                        onExport = { exportPicker.launch(null) },
                        onSaveEdited = { saveEditedPicker.launch(viewModel.suggestedEditedFileName()) },
                        onDownloadUpdate = { url ->
                            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri == lastHandledUri) return

        lastHandledUri = uri
        viewModel.loadUri(this, uri)
    }
}

@Composable
private fun ViewerScreen(
    viewModel: MainViewModel,
    onOpenFile: () -> Unit,
    onExport: () -> Unit,
    onSaveEdited: () -> Unit,
    onDownloadUpdate: (String) -> Unit,
) {
    val state = viewModel.uiState
    val measurement = viewModel.selectedMeasurement()
    val selectedReferenceSpectra = viewModel.selectedReferenceSpectra()
    val visibleMeasurements = viewModel.visibleMeasurements()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var renameDraft by remember(measurement?.title) { mutableStateOf(measurement?.title.orEmpty()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkForUpdatesIfNeeded()
    }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ControlPanel(
                state = state,
                visibleMeasurements = visibleMeasurements,
                isLandscape = true,
                onOpenFile = onOpenFile,
                onExport = onExport,
                onMoveSelection = viewModel::moveSelection,
                onSelectSample = viewModel::selectSample,
                onRenameSample = { index, title ->
                    viewModel.selectSample(index)
                    renameDraft = title
                    showRenameDialog = true
                },
                onDeleteSample = { index, _ ->
                    viewModel.selectSample(index)
                    showDeleteDialog = true
                },
                onDownloadUpdate = onDownloadUpdate,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
            )

            RightPanel(
                measurement = measurement,
                summaryItems = viewModel.summaryItems(),
                referenceSpectra = selectedReferenceSpectra,
                referenceNormalizationMode = state.referenceNormalizationMode,
                hasAvailableUpdate = state.availableUpdate != null,
                hasEditedChanges = state.hasEditedChanges,
                currentVersion = BuildConfig.VERSION_NAME,
                latestVersion = state.latestVersion,
                availableReferenceSpectra = state.referenceSpectra,
                selectedReferenceIds = state.selectedReferenceIds,
                onOpenFile = onOpenFile,
                onExport = onExport,
                onSaveEdited = onSaveEdited,
                showTopActions = false,
                onToggleReferenceSpectrum = viewModel::toggleReferenceSpectrum,
                onRenameSelectedSample = {
                    renameDraft = measurement?.title.orEmpty()
                    showRenameDialog = true
                },
                onDeleteSelectedSample = { showDeleteDialog = true },
                onCycleReferenceNormalization = {
                    viewModel.setReferenceNormalizationMode(
                        when (state.referenceNormalizationMode) {
                            ReferenceNormalizationMode.PEAK_NORMALIZE -> ReferenceNormalizationMode.AREA_NORMALIZE
                            ReferenceNormalizationMode.AREA_NORMALIZE -> ReferenceNormalizationMode.FIT_TO_SAMPLE
                            ReferenceNormalizationMode.FIT_TO_SAMPLE -> ReferenceNormalizationMode.PEAK_NORMALIZE
                        }
                    )
                },
                onCheckUpdates = { viewModel.checkForUpdates(showNoUpdateMessage = true) },
                onDownloadUpdate = onDownloadUpdate,
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val totalHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val dividerHeight = 12.dp
            val dividerHeightPx = with(LocalDensity.current) { dividerHeight.toPx() }
            val minTopPx = with(LocalDensity.current) { 220.dp.toPx() }
            val minBottomPx = with(LocalDensity.current) { 220.dp.toPx() }
            val availableHeight = (totalHeightPx - dividerHeightPx).coerceAtLeast(minTopPx + minBottomPx)
            val topHeightPx = (availableHeight * 0.6f).coerceIn(minTopPx, availableHeight - minBottomPx)
            val bottomHeightPx = (availableHeight - topHeightPx).coerceAtLeast(minBottomPx)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                RightPanel(
                    measurement = measurement,
                    summaryItems = viewModel.summaryItems(),
                    referenceSpectra = selectedReferenceSpectra,
                    referenceNormalizationMode = state.referenceNormalizationMode,
                    hasAvailableUpdate = state.availableUpdate != null,
                    hasEditedChanges = state.hasEditedChanges,
                    currentVersion = BuildConfig.VERSION_NAME,
                latestVersion = state.latestVersion,
                availableReferenceSpectra = state.referenceSpectra,
                selectedReferenceIds = state.selectedReferenceIds,
                onOpenFile = onOpenFile,
                onExport = onExport,
                onSaveEdited = onSaveEdited,
                showTopActions = true,
                onToggleReferenceSpectrum = viewModel::toggleReferenceSpectrum,
                    onRenameSelectedSample = {
                        renameDraft = measurement?.title.orEmpty()
                        showRenameDialog = true
                    },
                    onDeleteSelectedSample = { showDeleteDialog = true },
                    onCycleReferenceNormalization = {
                        viewModel.setReferenceNormalizationMode(
                            when (state.referenceNormalizationMode) {
                                ReferenceNormalizationMode.PEAK_NORMALIZE -> ReferenceNormalizationMode.AREA_NORMALIZE
                                ReferenceNormalizationMode.AREA_NORMALIZE -> ReferenceNormalizationMode.FIT_TO_SAMPLE
                                ReferenceNormalizationMode.FIT_TO_SAMPLE -> ReferenceNormalizationMode.PEAK_NORMALIZE
                            }
                        )
                    },
                    onCheckUpdates = { viewModel.checkForUpdates(showNoUpdateMessage = true) },
                    onDownloadUpdate = onDownloadUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { topHeightPx.toDp() })
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dividerHeight)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(4.dp)
                            .background(Color(0xFFD0D7E2), RoundedCornerShape(999.dp))
                    )
                }

                ControlPanel(
                    state = state,
                    visibleMeasurements = visibleMeasurements,
                    isLandscape = false,
                    onOpenFile = onOpenFile,
                    onExport = onExport,
                    onMoveSelection = viewModel::moveSelection,
                    onSelectSample = viewModel::selectSample,
                    onRenameSample = { index, title ->
                        viewModel.selectSample(index)
                        renameDraft = title
                        showRenameDialog = true
                    },
                    onDeleteSample = { index, _ ->
                        viewModel.selectSample(index)
                        showDeleteDialog = true
                    },
                    onDownloadUpdate = onDownloadUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { bottomHeightPx.toDp() })
                )
            }
        }
    }

    if (showRenameDialog && measurement != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename sample") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    label = { Text("Sample name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSelectedMeasurement(renameDraft)
                        showRenameDialog = false
                    },
                    enabled = renameDraft.trim().isNotEmpty()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDialog && measurement != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete sample?") },
            text = { Text("Remove \"${measurement.title}\" from the edited copy. The original TBWK file will not be overwritten.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedMeasurement()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ControlPanel(
    state: ViewerUiState,
    visibleMeasurements: List<Pair<Int, Measurement>>,
    isLandscape: Boolean,
    onOpenFile: () -> Unit,
    onExport: () -> Unit,
    onMoveSelection: (Int) -> Unit,
    onSelectSample: (Int) -> Unit,
    onRenameSample: (Int, String) -> Unit,
    onDeleteSample: (Int, String) -> Unit,
    onDownloadUpdate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp)
    ) {
        if (isLandscape) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PanelHeader(
                    state = state,
                    showActions = true,
                    onOpenFile = onOpenFile,
                    onExport = onExport,
                )
                PanelMessages(state, onDownloadUpdate)
                SampleList(
                    visibleMeasurements = visibleMeasurements,
                    selectedIndex = state.selectedIndex,
                    onSelectSample = onSelectSample,
                    onRenameSample = onRenameSample,
                    onDeleteSample = onDeleteSample,
                    modifier = Modifier.weight(1f)
                )
                SelectionButtons(onMoveSelection)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PanelHeader(
                    state = state,
                    showActions = false,
                    onOpenFile = onOpenFile,
                    onExport = onExport,
                )
                PanelMessages(state, onDownloadUpdate)
                SampleList(
                    visibleMeasurements = visibleMeasurements,
                    selectedIndex = state.selectedIndex,
                    onSelectSample = onSelectSample,
                    onRenameSample = onRenameSample,
                    onDeleteSample = onDeleteSample,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                SelectionButtons(onMoveSelection)
            }
        }
    }
}

@Composable
private fun PanelHeader(
    state: ViewerUiState,
    showActions: Boolean,
    onOpenFile: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showActions) {
            BoxWithConstraints {
                val buttonFontSize = when {
                    maxWidth < 320.dp -> 9.sp
                    maxWidth < 420.dp -> 10.sp
                    else -> 12.sp
                }
                val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenFile,
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = buttonPadding,
                    ) {
                        ActionButtonText("Import", buttonFontSize)
                    }
                    Button(
                        onClick = onExport,
                        modifier = Modifier.weight(1f).height(40.dp),
                        enabled = state.worksheet != null && !state.isLoading,
                        contentPadding = buttonPadding,
                    ) {
                        ActionButtonText("Export", buttonFontSize)
                    }
                }
            }
        }

        if (state.selectedReferenceIds.isNotEmpty()) {
            Text(
                text = state.referenceSpectra
                    .filter { it.id in state.selectedReferenceIds }
                    .joinToString(", ") { it.shortTitle },
                color = Color(0xFF4E5B75),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = state.fileName ?: "No file selected",
            color = Color(0xFF4E5B75),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionButtonText(label: String, fontSize: androidx.compose.ui.unit.TextUnit) {
    Text(
        text = label,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        fontSize = fontSize,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PanelMessages(
    state: ViewerUiState,
    onDownloadUpdate: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.errorMessage?.let {
            Text(it, color = Color(0xFFB42318))
        }

        state.exportMessage?.let {
            Text(it, color = Color(0xFF027A48))
        }

        state.updateStatusMessage?.let {
            if (state.availableUpdate != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F1FF)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = it,
                            color = Color(0xFF175CD3),
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                        )
                        TextButton(onClick = { onDownloadUpdate(state.availableUpdate.downloadUrl) }) {
                            Text("Download", maxLines = 1)
                        }
                    }
                }
            } else {
                Text(it, color = Color(0xFF175CD3))
            }
        }

        if (state.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Working...")
            }
        }
    }
}

@Composable
private fun SelectionButtons(onMoveSelection: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onMoveSelection(-1) }, modifier = Modifier.weight(1f)) {
            Text("Previous")
        }
        Button(onClick = { onMoveSelection(1) }, modifier = Modifier.weight(1f)) {
            Text("Next")
        }
    }
}

@Composable
private fun SampleList(
    visibleMeasurements: List<Pair<Int, Measurement>>,
    selectedIndex: Int,
    onSelectSample: (Int) -> Unit,
    onRenameSample: (Int, String) -> Unit,
    onDeleteSample: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var openSwipeIndex by remember { mutableStateOf<Int?>(null) }
    val selectedPosition = remember(visibleMeasurements, selectedIndex) {
        visibleMeasurements.indexOfFirst { it.first == selectedIndex }
    }

    LaunchedEffect(selectedPosition) {
        if (selectedPosition >= 0) {
            listState.animateScrollToItem(selectedPosition)
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(visibleMeasurements, key = { _, item -> item.first }) { displayIndex, item ->
            val index = item.first
            val measurement = item.second
            val selected = index == selectedIndex
            val background = if (selected) Color(0xFF0F6CBD) else Color(0xFFF4F6FA)
            val textColor = if (selected) Color.White else Color(0xFF172033)
            val renameWidthDp = 94.dp
            val deleteWidthDp = 88.dp
            val renameWidthPx = with(LocalDensity.current) { renameWidthDp.toPx() }
            val deleteWidthPx = with(LocalDensity.current) { deleteWidthDp.toPx() }
            val totalActionWidthPx = renameWidthPx + deleteWidthPx
            val totalActionWidthDp = renameWidthDp + deleteWidthDp
            val revealThresholdPx = totalActionWidthPx * 0.35f
            val offsetX = remember(index) { Animatable(0f) }

            LaunchedEffect(openSwipeIndex) {
                if (openSwipeIndex != index && offsetX.value != 0f) {
                    offsetX.animateTo(0f, animationSpec = tween(durationMillis = 180))
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .width(totalActionWidthDp)
                        .height(44.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(renameWidthDp)
                            .fillMaxHeight()
                            .background(
                                Color(0xFFD92D20),
                                RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                            )
                            .clickable {
                                onSelectSample(index)
                                onRenameSample(index, measurement.title)
                                openSwipeIndex = null
                                coroutineScope.launch {
                                    offsetX.animateTo(0f, animationSpec = tween(durationMillis = 160))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Rename",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(deleteWidthDp)
                            .fillMaxHeight()
                            .background(
                                Color(0xFFB42318),
                                RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                            )
                            .clickable {
                                onSelectSample(index)
                                onDeleteSample(index, measurement.title)
                                openSwipeIndex = null
                                coroutineScope.launch {
                                    offsetX.animateTo(0f, animationSpec = tween(durationMillis = 160))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Delete",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .background(background, RoundedCornerShape(16.dp))
                        .pointerInput(index, openSwipeIndex) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    coroutineScope.launch { offsetX.stop() }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    val current = offsetX.value
                                    val next = when {
                                        current == 0f && dragAmount > 0f -> 0f
                                        else -> (current + dragAmount).coerceIn(-totalActionWidthPx, 0f)
                                    }
                                    coroutineScope.launch { offsetX.snapTo(next) }
                                },
                                onDragEnd = {
                                    val shouldOpen = offsetX.value <= -revealThresholdPx
                                    coroutineScope.launch {
                                        offsetX.animateTo(
                                            targetValue = if (shouldOpen) -totalActionWidthPx else 0f,
                                            animationSpec = tween(durationMillis = 180)
                                        )
                                    }
                                    openSwipeIndex = if (shouldOpen) index else null
                                }
                            )
                        }
                        .clickable {
                            if (offsetX.value != 0f) {
                                openSwipeIndex = null
                                coroutineScope.launch {
                                    offsetX.animateTo(0f, animationSpec = tween(durationMillis = 160))
                                }
                                return@clickable
                            }
                            onSelectSample(index)
                            coroutineScope.launch {
                                listState.animateScrollToItem(visibleMeasurements.indexOfFirst { it.first == index })
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "#${displayIndex + 1} ${measurement.title}",
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RightPanel(
    measurement: Measurement?,
    summaryItems: List<Pair<String, String>>,
    referenceSpectra: List<ReferenceSpectrum>,
    referenceNormalizationMode: ReferenceNormalizationMode,
    hasAvailableUpdate: Boolean,
    hasEditedChanges: Boolean,
    currentVersion: String,
    latestVersion: String?,
    availableReferenceSpectra: List<ReferenceSpectrum>,
    selectedReferenceIds: Set<String>,
    onOpenFile: () -> Unit,
    onExport: () -> Unit,
    onSaveEdited: () -> Unit,
    showTopActions: Boolean,
    onToggleReferenceSpectrum: (String) -> Unit,
    onRenameSelectedSample: () -> Unit,
    onDeleteSelectedSample: () -> Unit,
    onCycleReferenceNormalization: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showReferenceDialog by remember { mutableStateOf(false) }
    var showReferencePicker by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp)
    ) {
        if (measurement == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (showTopActions) {
                    BoxWithConstraints {
                        val buttonFontSize = when {
                            maxWidth < 320.dp -> 9.sp
                            maxWidth < 420.dp -> 10.sp
                            else -> 12.sp
                        }
                        val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenFile,
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentPadding = buttonPadding,
                            ) {
                                ActionButtonText("Import", buttonFontSize)
                            }
                            Button(
                                onClick = onExport,
                                modifier = Modifier.weight(1f).height(40.dp),
                                enabled = false,
                                contentPadding = buttonPadding,
                            ) {
                                ActionButtonText("Export", buttonFontSize)
                            }
                            if (hasEditedChanges) {
                                Button(
                                    onClick = onSaveEdited,
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    contentPadding = buttonPadding,
                                ) {
                                    ActionButtonText("Save", buttonFontSize)
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Import a TBWK file to view spectra.", color = Color(0xFF4E5B75))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (showTopActions) {
                    BoxWithConstraints {
                        val buttonFontSize = when {
                            maxWidth < 320.dp -> 9.sp
                            maxWidth < 420.dp -> 10.sp
                            else -> 12.sp
                        }
                        val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenFile,
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentPadding = buttonPadding,
                            ) {
                                ActionButtonText("Import", buttonFontSize)
                            }
                            Button(
                                onClick = onExport,
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentPadding = buttonPadding,
                            ) {
                                ActionButtonText("Export", buttonFontSize)
                            }
                            if (hasEditedChanges) {
                                Button(
                                    onClick = onSaveEdited,
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    contentPadding = buttonPadding,
                                ) {
                                    ActionButtonText("Save", buttonFontSize)
                                }
                            }
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    summaryItems.forEach { (label, value) ->
                        SummaryChip(label = label, value = value)
                    }
                }

                SpectrumChart(
                    measurement = measurement,
                    referenceSpectra = referenceSpectra,
                    referenceNormalizationMode = referenceNormalizationMode,
                    hasAvailableUpdate = hasAvailableUpdate,
                    hasEditedChanges = hasEditedChanges,
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    onShowReference = { showReferenceDialog = true },
                    onShowReferencePicker = { showReferencePicker = true },
                    onSaveEdited = onSaveEdited,
                    onRenameSelectedSample = onRenameSelectedSample,
                    onDeleteSelectedSample = onDeleteSelectedSample,
                    onCycleReferenceNormalization = onCycleReferenceNormalization,
                    onCheckUpdates = onCheckUpdates,
                    onDownloadUpdate = onDownloadUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }

    if (showReferenceDialog) {
        NanoDropReferenceDialog(onDismiss = { showReferenceDialog = false })
    }

    if (showReferencePicker) {
        ReferenceSpectrumDialog(
            spectra = availableReferenceSpectra,
            selectedIds = selectedReferenceIds,
            onToggle = onToggleReferenceSpectrum,
            onDismiss = { showReferencePicker = false },
        )
    }
}

@Composable
private fun SummaryChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF667085))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun ReferenceSpectrumDialog(
    spectra: List<ReferenceSpectrum>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reference Spectra", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(spectra, key = { it.id }) { spectrum ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(spectrum.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = spectrum.id in selectedIds,
                                onCheckedChange = { onToggle(spectrum.id) }
                            )
                            Column {
                                Text(spectrum.shortTitle, fontWeight = FontWeight.SemiBold)
                                Text(
                                    spectrum.title,
                                    color = Color(0xFF667085),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ChartViewport(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
)

private data class SelectedChartPoint(
    val sourceIndex: Int,
    val xValue: Double,
    val yValue: Double,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SpectrumChart(
    measurement: Measurement,
    referenceSpectra: List<ReferenceSpectrum>,
    referenceNormalizationMode: ReferenceNormalizationMode,
    hasAvailableUpdate: Boolean,
    hasEditedChanges: Boolean,
    currentVersion: String,
    latestVersion: String?,
    onShowReference: () -> Unit,
    onShowReferencePicker: () -> Unit,
    onSaveEdited: () -> Unit,
    onRenameSelectedSample: () -> Unit,
    onDeleteSelectedSample: () -> Unit,
    onCycleReferenceNormalization: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseMinX = 220.0
    val baseMaxX = 350.0
    val baseYMin = 0.0
    val baseYMax = ((measurement.yValues.maxOrNull() ?: 0.0) + 1.0).coerceAtLeast(1.0)
    val fullViewport = ChartViewport(
        minX = baseMinX,
        maxX = baseMaxX,
        minY = baseYMin,
        maxY = baseYMax,
    )

    var viewport by remember(measurement) { mutableStateOf(fullViewport) }
    var selectedPoint by remember(measurement) { mutableStateOf<SelectedChartPoint?>(null) }
    val tapThresholdPx = with(LocalDensity.current) { 28.dp.toPx() }

    LaunchedEffect(measurement) {
        viewport = fullViewport
        selectedPoint = null
    }

    val normalizedReferences = remember(measurement, referenceSpectra, referenceNormalizationMode) {
        referenceSpectra.map { spectrum ->
            spectrum to normalizeReferenceSpectrum(
                spectrum = spectrum,
                measurement = measurement,
                mode = referenceNormalizationMode,
                minX = baseMinX,
                maxX = baseMaxX,
            )
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Absrob.(nm)",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF667085)
                )
                Button(onClick = onShowReference, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("Info", maxLines = 1)
                }
                Button(onClick = onShowReferencePicker, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("Reference", maxLines = 1)
                }
                Button(onClick = onCycleReferenceNormalization, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(referenceNormalizationMode.label, maxLines = 1)
                }
            }
            Box {
                var showOverflowMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(
                        onClick = { showOverflowMenu = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text("⋮", fontSize = 18.sp, maxLines = 1)
                    }
                    if (hasAvailableUpdate) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 4.dp)
                                .size(8.dp)
                                .background(Color(0xFFD92D20), RoundedCornerShape(999.dp))
                        )
                    }
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Current Version: $currentVersion") },
                        onClick = {},
                        enabled = false
                    )
                    DropdownMenuItem(
                        text = { Text("Latest Version: ${latestVersion ?: "-"}") },
                        onClick = {},
                        enabled = false
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Rename Selected Sample") },
                        onClick = {
                            showOverflowMenu = false
                            onRenameSelectedSample()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Selected Sample") },
                        onClick = {
                            showOverflowMenu = false
                            onDeleteSelectedSample()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Check for Updates") },
                        onClick = {
                            showOverflowMenu = false
                            onCheckUpdates()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Open Latest Release") },
                        onClick = {
                            showOverflowMenu = false
                            onDownloadUpdate("https://github.com/SavannaChow/Nanodrop2000-Viewer/releases/latest")
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (normalizedReferences.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReferenceLegendChip(label = measurement.title, color = Color(0xFF0F6CBD), dashed = false)
                normalizedReferences.forEach { (spectrum, _) ->
                    ReferenceLegendChip(
                        label = spectrum.title,
                        color = referenceColor(spectrum.id),
                        dashed = true
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8FAFC), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(20.dp))
                .pointerInput(measurement, viewport) {
                    detectTapGestures { tapOffset ->
                        val leftPadding = 72f
                        val bottomPadding = 48f
                        val topPadding = 44f
                        val rightPadding = 16f
                        val plotWidth = size.width - leftPadding - rightPadding
                        val plotHeight = size.height - topPadding - bottomPadding
                        if (plotWidth <= 0f || plotHeight <= 0f) {
                            selectedPoint = null
                            return@detectTapGestures
                        }

                        val plotLeft = leftPadding
                        val plotTop = topPadding
                        val plotBottom = topPadding + plotHeight
                        val plotRight = plotLeft + plotWidth

                        if (tapOffset.x !in plotLeft..plotRight || tapOffset.y !in plotTop..plotBottom) {
                            selectedPoint = null
                            return@detectTapGestures
                        }

                        var nearestPoint: SelectedChartPoint? = null
                        var nearestDistance = Float.MAX_VALUE

                        measurement.xValues.indices.forEach { pointIndex ->
                            val xValue = measurement.xValues[pointIndex]
                            if (xValue !in viewport.minX..viewport.maxX) return@forEach

                            val yValue = measurement.yValues[pointIndex].coerceIn(viewport.minY, viewport.maxY)
                            val xFraction = ((xValue - viewport.minX) / (viewport.maxX - viewport.minX).coerceAtLeast(0.00001)).toFloat()
                            val yFraction = ((yValue - viewport.minY) / (viewport.maxY - viewport.minY).coerceAtLeast(0.00001)).toFloat()
                            val pointX = plotLeft + plotWidth * xFraction
                            val pointY = plotBottom - plotHeight * yFraction
                            val dx = pointX - tapOffset.x
                            val dy = pointY - tapOffset.y
                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (distance < nearestDistance) {
                                nearestDistance = distance
                                nearestPoint = SelectedChartPoint(pointIndex, xValue, measurement.yValues[pointIndex])
                            }
                        }

                        selectedPoint = if (nearestDistance <= tapThresholdPx) nearestPoint else null
                    }
                }
                .pointerInput(measurement) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val xSpan = (viewport.maxX - viewport.minX).coerceAtLeast(0.00001)
                        val ySpan = (viewport.maxY - viewport.minY).coerceAtLeast(0.00001)
                        val zoomFactor = zoom.toDouble().coerceIn(0.8, 1.25)

                        val nextXSpan = (xSpan / zoomFactor).coerceIn(
                            (fullViewport.maxX - fullViewport.minX) * 0.05,
                            fullViewport.maxX - fullViewport.minX
                        )
                        val nextYSpan = (ySpan / zoomFactor).coerceIn(
                            (fullViewport.maxY - fullViewport.minY) * 0.05,
                            fullViewport.maxY - fullViewport.minY
                        )

                        val panXData = -pan.x / size.width * nextXSpan
                        val panYData = pan.y / size.height * nextYSpan

                        var nextMinX = viewport.minX + panXData
                        var nextMaxX = nextMinX + nextXSpan
                        var nextMinY = viewport.minY + panYData
                        var nextMaxY = nextMinY + nextYSpan

                        if (nextMinX < fullViewport.minX) {
                            nextMinX = fullViewport.minX
                            nextMaxX = nextMinX + nextXSpan
                        }
                        if (nextMaxX > fullViewport.maxX) {
                            nextMaxX = fullViewport.maxX
                            nextMinX = nextMaxX - nextXSpan
                        }
                        if (nextMinY < fullViewport.minY) {
                            nextMinY = fullViewport.minY
                            nextMaxY = nextMinY + nextYSpan
                        }
                        if (nextMaxY > fullViewport.maxY) {
                            nextMaxY = fullViewport.maxY
                            nextMinY = nextMaxY - nextYSpan
                        }

                        viewport = ChartViewport(nextMinX, nextMaxX, nextMinY, nextMaxY)
                    }
                }
                .padding(12.dp)
        ) {
            val leftPadding = 72f
            val bottomPadding = 48f
            val topPadding = 44f
            val rightPadding = 16f
            val plotWidth = size.width - leftPadding - rightPadding
            val plotHeight = size.height - topPadding - bottomPadding
            if (plotWidth <= 0f || plotHeight <= 0f || measurement.xValues.isEmpty()) return@Canvas

            val plotLeft = leftPadding
            val plotTop = topPadding
            val plotBottom = topPadding + plotHeight

            repeat(6) { tick ->
                val fraction = tick / 5f
                val x = plotLeft + plotWidth * fraction
                val y = plotTop + plotHeight * fraction

                drawLine(Color(0xFFE2E8F0), Offset(x, plotTop), Offset(x, plotBottom), 1f)
                drawLine(Color(0xFFE2E8F0), Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), 1f)
            }

            drawRect(
                color = Color(0xFF94A3B8),
                topLeft = Offset(plotLeft, plotTop),
                size = Size(plotWidth, plotHeight),
                style = Stroke(width = 2f)
            )

            val visiblePoints = measurement.xValues.indices.filter { index ->
                val x = measurement.xValues[index]
                x in viewport.minX..viewport.maxX
            }

            val path = Path()
            visiblePoints.forEachIndexed { pointIndex, sourceIndex ->
                val xValue = measurement.xValues[sourceIndex]
                val yValue = measurement.yValues[sourceIndex].coerceIn(viewport.minY, viewport.maxY)
                val xFraction = ((xValue - viewport.minX) / (viewport.maxX - viewport.minX).coerceAtLeast(0.00001)).toFloat()
                val yFraction = ((yValue - viewport.minY) / (viewport.maxY - viewport.minY).coerceAtLeast(0.00001)).toFloat()
                val x = plotLeft + plotWidth * xFraction
                val y = plotBottom - plotHeight * yFraction
                if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = Color(0xFF0F6CBD),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            normalizedReferences.forEach { (spectrum, normalizedPoints) ->
                val referencePath = Path()
                normalizedPoints.forEachIndexed { pointIndex, point ->
                    if (point.first !in viewport.minX..viewport.maxX) return@forEachIndexed
                    val yValue = point.second.coerceIn(viewport.minY, viewport.maxY)
                    val xFraction = ((point.first - viewport.minX) / (viewport.maxX - viewport.minX).coerceAtLeast(0.00001)).toFloat()
                    val yFraction = ((yValue - viewport.minY) / (viewport.maxY - viewport.minY).coerceAtLeast(0.00001)).toFloat()
                    val x = plotLeft + plotWidth * xFraction
                    val y = plotBottom - plotHeight * yFraction
                    if (pointIndex == 0) referencePath.moveTo(x, y) else referencePath.lineTo(x, y)
                }

                drawPath(
                    path = referencePath,
                    color = referenceColor(spectrum.id),
                    style = Stroke(
                        width = 3f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))
                    )
                )
            }

            val textPaint = Paint().apply {
                color = android.graphics.Color.DKGRAY
                textSize = 24f
                isAntiAlias = true
            }
            val markerValues = listOf(230.0, 260.0, 280.0)

            markerValues.forEach { marker ->
                if (marker in viewport.minX..viewport.maxX) {
                    val fraction = ((marker - viewport.minX) / (viewport.maxX - viewport.minX).coerceAtLeast(0.00001)).toFloat()
                    val x = plotLeft + plotWidth * fraction
                    drawLine(
                        color = Color(0x664B5563),
                        start = Offset(x, plotTop),
                        end = Offset(x, plotBottom),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                }
            }

            repeat(6) { tick ->
                val fraction = tick / 5f
                val xValue = viewport.minX + (viewport.maxX - viewport.minX) * fraction
                val yValue = viewport.maxY - (viewport.maxY - viewport.minY) * fraction
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        "%.1f".format(xValue),
                        plotLeft + plotWidth * fraction - 18f,
                        size.height - 8f,
                        textPaint
                    )
                    canvas.nativeCanvas.drawText(
                        "%.2f".format(yValue),
                        8f,
                        plotTop + plotHeight * fraction + 8f,
                        textPaint
                    )
                }
            }

            markerValues.forEach { marker ->
                if (marker in viewport.minX..viewport.maxX) {
                    val fraction = ((marker - viewport.minX) / (viewport.maxX - viewport.minX).coerceAtLeast(0.00001)).toFloat()
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            marker.toInt().toString(),
                            plotLeft + plotWidth * fraction - 12f,
                            size.height - 28f,
                            textPaint
                        )
                    }
                }
            }

            selectedPoint?.let { point ->
                if (point.xValue in viewport.minX..viewport.maxX) {
                    val clampedY = point.yValue.coerceIn(viewport.minY, viewport.maxY)
                    val xFraction = ((point.xValue - viewport.minX) / (viewport.maxX - viewport.minX).coerceAtLeast(0.00001)).toFloat()
                    val yFraction = ((clampedY - viewport.minY) / (viewport.maxY - viewport.minY).coerceAtLeast(0.00001)).toFloat()
                    val pointX = plotLeft + plotWidth * xFraction
                    val pointY = plotBottom - plotHeight * yFraction

                    drawCircle(
                        color = Color.White,
                        radius = 10f,
                        center = Offset(pointX, pointY)
                    )
                    drawCircle(
                        color = Color(0xFF0F6CBD),
                        radius = 6f,
                        center = Offset(pointX, pointY)
                    )

                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            "x=${"%.1f".format(point.xValue)} y=${"%.2f".format(point.yValue)}",
                            (pointX + 14f).coerceAtMost(size.width - 220f),
                            (pointY - 12f).coerceAtLeast(28f),
                            textPaint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceLegendChip(label: String, color: Color, dashed: Boolean) {
    Row(
        modifier = Modifier
            .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 10.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 4f,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null
            )
        }
        Text(label, fontSize = 12.sp)
    }
}

private fun normalizeReferenceSpectrum(
    spectrum: ReferenceSpectrum,
    measurement: Measurement,
    mode: ReferenceNormalizationMode,
    minX: Double,
    maxX: Double,
): List<Pair<Double, Double>> {
    val rawPoints = spectrum.xValues.zip(spectrum.yValues)
        .filter { (x, _) -> x in minX..maxX }
    if (rawPoints.isEmpty()) return emptyList()

    val samplePoints = measurement.xValues.zip(measurement.yValues)
        .filter { (x, _) -> x in minX..maxX }
    if (samplePoints.isEmpty()) return rawPoints

    val baselineCorrectedReference = baselineCorrectPoints(rawPoints)
    val baselineCorrectedSample = baselineCorrectPoints(samplePoints)

    val samplePeak = baselineCorrectedSample.maxOf { it.second }.coerceAtLeast(0.00001)
    val referencePeak = baselineCorrectedReference.maxOf { it.second }.coerceAtLeast(0.00001)
    val scaledFactor = when (mode) {
        ReferenceNormalizationMode.PEAK_NORMALIZE -> samplePeak / referencePeak
        ReferenceNormalizationMode.AREA_NORMALIZE -> {
            val sampleArea = trapezoidArea(baselineCorrectedSample).coerceAtLeast(0.00001)
            val referenceArea = trapezoidArea(baselineCorrectedReference).coerceAtLeast(0.00001)
            sampleArea / referenceArea
        }
        ReferenceNormalizationMode.FIT_TO_SAMPLE -> {
            var numerator = 0.0
            var denominator = 0.0
            baselineCorrectedReference.forEach { (x, refY) ->
                val sampleY = interpolateSampleY(baselineCorrectedSample, x)
                if (sampleY != null) {
                    numerator += sampleY * refY
                    denominator += refY * refY
                }
            }
            if (denominator <= 0.00001) samplePeak / referencePeak else numerator / denominator
        }
    }

    return baselineCorrectedReference.map { (x, y) -> x to (y * scaledFactor) }
}

private fun baselineCorrectPoints(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (points.isEmpty()) return points
    val minY = points.minOf { it.second }
    return points.map { (x, y) -> x to (y - minY).coerceAtLeast(0.0) }
}

private fun trapezoidArea(points: List<Pair<Double, Double>>): Double {
    if (points.size < 2) return 0.0
    return points.zipWithNext().sumOf { (left, right) ->
        val width = right.first - left.first
        width * (left.second + right.second) / 2.0
    }
}

private fun interpolateSampleY(points: List<Pair<Double, Double>>, x: Double): Double? {
    if (points.size < 2) return null
    points.zipWithNext().forEach { (left, right) ->
        if (x in left.first..right.first) {
            val span = (right.first - left.first).coerceAtLeast(0.00001)
            val fraction = (x - left.first) / span
            return left.second + (right.second - left.second) * fraction
        }
    }
    return null
}

private fun referenceColor(id: String): Color {
    return when (id) {
        "phenol" -> Color(0xFF92400E)
        "guanidine_hydrochloride_GuHCl" -> Color(0xFF047857)
        "guanidine_thiocyanate_GTC" -> Color(0xFF7C3AED)
        "protein_BSA" -> Color(0xFFB42318)
        "EDTA" -> Color(0xFF0E7490)
        "ethanol" -> Color(0xFFEA580C)
        "dsDNA" -> Color(0xFF1D4ED8)
        "RNA" -> Color(0xFFBE185D)
        else -> Color(0xFF475467)
    }
}

@Composable
private fun NanoDropReferenceDialog(onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("NanoDrop 說明", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    TextButton(onClick = onDismiss) {
                        Text("X")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("NanoDrop 數值", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("A260 (核酸波峰): 測定 DNA 或 RNA 的濃度。")
                    Text("A280 (蛋白質波峰): 檢測蛋白質污染。")
                    Text("A230 (有機雜質): 檢測酚類、胍鹽或碳水化合物等污染物。")
                    Text("DNA: 260 nm")
                    Text("protein: 280 nm")
                    Text("phenol: 270 nm")
                    Text("guanidine / salt: 230 nm")
                    Text("EDTA: 230 nm")
                    Text("carbohydrate: 230 nm")

                    Text("A260/280 Ratio:", fontWeight = FontWeight.Bold)
                    Text("DNA：~1.8")
                    Text("RNA：~2.0")
                    Text("低比值表示的汙染物：")
                    Text("· 蛋白質")
                    Text("· 殘餘酚或提取方法中使用的其他試劑")

                    Text("A260/230 Ratio:", fontWeight = FontWeight.Bold)
                    Text("DNA/RNA：~2.0-2.2")
                    Text("低比值表示的汙染物：")
                    Text("· 蛋白質")
                    Text("· 碳水化合物殘留（通常是植物的問題）")
                    Text("· 來自核酸提取的殘餘酚")
                    Text("· 殘餘胍（通常用於管柱式套件）")
                    Text("· 用於沉澱的糖原")

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("無汙染的純化 DNA (A，紅色)", fontWeight = FontWeight.SemiBold)
                            Text("被胍(B，綠色)和酚(C，褐色)汙染之光譜。")
                        }
                    }

                    Image(
                        painter = painterResource(R.drawable.contaminants_cause),
                        contentDescription = "NanoDrop contamination reference",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(16.dp))
                    )

                    Image(
                        painter = painterResource(R.drawable.uv_absorbance_spectra_common_contaminant_with_dna),
                        contentDescription = "UV absorbance spectra common contaminants with DNA",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(16.dp))
                    )

                    Image(
                        painter = painterResource(R.drawable.uv_absorbance_spectra_of_phenol_and_trizol_mixed_with_rna),
                        contentDescription = "UV absorbance spectra of phenol and TRIzol mixed with RNA",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(16.dp))
                    )

                    Image(
                        painter = painterResource(R.drawable.spectra_of_contaminated_dna),
                        contentDescription = "Spectra of contaminated DNA",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(16.dp))
                    )
                }
            }
        }
    }
}
