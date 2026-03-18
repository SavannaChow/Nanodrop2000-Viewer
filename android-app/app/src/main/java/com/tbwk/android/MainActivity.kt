package com.tbwk.android

import android.content.res.Configuration
import android.graphics.Paint
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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                val picker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.loadUri(this, it) }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F7FB)
                ) {
                    ViewerScreen(
                        viewModel = viewModel,
                        onOpenFile = { picker.launch(arrayOf("*/*")) },
                        onExport = { viewModel.exportCurrentWorksheet(this) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerScreen(
    viewModel: MainViewModel,
    onOpenFile: () -> Unit,
    onExport: () -> Unit,
) {
    val state = viewModel.uiState
    val measurement = viewModel.selectedMeasurement()
    val visibleMeasurements = viewModel.visibleMeasurements()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
            )

            RightPanel(
                measurement = measurement,
                summaryItems = viewModel.summaryItems(),
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RightPanel(
                measurement = measurement,
                summaryItems = viewModel.summaryItems(),
                modifier = Modifier.weight(1f)
            )

            ControlPanel(
                state = state,
                visibleMeasurements = visibleMeasurements,
                isLandscape = false,
                onOpenFile = onOpenFile,
                onExport = onExport,
                onMoveSelection = viewModel::moveSelection,
                onSelectSample = viewModel::selectSample,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.48f)
            )
        }
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
                PanelHeader(state, onOpenFile, onExport)
                PanelMessages(state)
                SampleList(
                    visibleMeasurements = visibleMeasurements,
                    selectedIndex = state.selectedIndex,
                    onSelectSample = onSelectSample,
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
                PanelHeader(state, onOpenFile, onExport)
                PanelMessages(state)
                SampleList(
                    visibleMeasurements = visibleMeasurements,
                    selectedIndex = state.selectedIndex,
                    onSelectSample = onSelectSample,
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
    onOpenFile: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenFile, modifier = Modifier.weight(1f)) {
                Text("Import")
            }
            Button(
                onClick = onExport,
                modifier = Modifier.weight(1f),
                enabled = state.worksheet != null && !state.isLoading
            ) {
                Text("Export")
            }
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
private fun PanelMessages(state: ViewerUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.errorMessage?.let {
            Text(it, color = Color(0xFFB42318))
        }

        state.exportMessage?.let {
            Text(it, color = Color(0xFF027A48))
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
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
        items(visibleMeasurements, key = { it.first }) { (index, measurement) ->
            val selected = index == selectedIndex
            val background = if (selected) Color(0xFF0F6CBD) else Color(0xFFF4F6FA)
            val textColor = if (selected) Color.White else Color(0xFF172033)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background, RoundedCornerShape(16.dp))
                    .clickable {
                        onSelectSample(index)
                        coroutineScope.launch {
                            listState.animateScrollToItem(visibleMeasurements.indexOfFirst { it.first == index })
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "#${index + 1} ${measurement.title}",
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RightPanel(
    measurement: Measurement?,
    summaryItems: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    var showReferenceDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp)
    ) {
        if (measurement == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Import a TBWK file to view spectra.", color = Color(0xFF4E5B75))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                    onShowReference = { showReferenceDialog = true },
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
private fun SpectrumChart(
    measurement: Measurement,
    onShowReference: () -> Unit,
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

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Absrobance(nm)",
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowReference) {
                    Text("Info")
                }
                Button(onClick = { viewport = fullViewport }) {
                    Text("Reset Zoom")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8FAFC), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(20.dp))
                .pointerInput(measurement, viewport) {
                    detectTapGestures { tapOffset ->
                        val leftPadding = 72f
                        val bottomPadding = 48f
                        val topPadding = 20f
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
            val topPadding = 20f
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
