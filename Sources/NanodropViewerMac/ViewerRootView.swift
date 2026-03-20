import SwiftUI
import UniformTypeIdentifiers
import TBWKCore

private struct ChartSelection: Equatable {
    let index: Int
    let xValue: Double
    let yValue: Double
}

struct ViewerRootView: View {
    @ObservedObject var viewModel: ViewerViewModel
    @FocusState private var sampleListFocused: Bool
    @AppStorage("leftPanelFraction") private var leftPanelFraction = 0.24
    @State private var dragStartLeftWidth: CGFloat?

    var body: some View {
        GeometryReader { geometry in
            let totalWidth = max(geometry.size.width - 32, 1)
            let dividerWidth: CGFloat = 10
            let minLeftWidth: CGFloat = 180
            let minRightWidth: CGFloat = 420
            let clampedLeftWidth = min(
                max(CGFloat(leftPanelFraction) * totalWidth, minLeftWidth),
                max(totalWidth - minRightWidth - dividerWidth, minLeftWidth)
            )

            HStack(spacing: 0) {
                controlPanel
                    .frame(width: clampedLeftWidth)

                Rectangle()
                    .fill(Color.clear)
                    .frame(width: dividerWidth)
                    .overlay(
                        Capsule()
                            .fill(Color(nsColor: .separatorColor).opacity(0.85))
                            .frame(width: 4)
                    )
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture()
                            .onChanged { value in
                                let startingWidth = dragStartLeftWidth ?? clampedLeftWidth
                                if dragStartLeftWidth == nil {
                                    dragStartLeftWidth = clampedLeftWidth
                                }
                                let proposed = startingWidth + value.translation.width
                                let bounded = min(
                                    max(proposed, minLeftWidth),
                                    max(totalWidth - minRightWidth - dividerWidth, minLeftWidth)
                                )
                                leftPanelFraction = bounded / totalWidth
                            }
                            .onEnded { _ in
                                dragStartLeftWidth = nil
                            }
                    )

                rightPanel
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(16)
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear {
            sampleListFocused = true
            viewModel.checkForUpdatesIfNeeded()
        }
        .sheet(isPresented: $viewModel.isShowingInfo) {
            ViewerInfoSheet()
        }
        .alert("Update Available", isPresented: $viewModel.isShowingUpdatePrompt, presenting: viewModel.updateInfo) { update in
            Button("Download") {
                viewModel.downloadUpdate()
            }
            Button("Later", role: .cancel) {}
        } message: { update in
            Text("Current version: \(UpdateService.currentVersion())\nLatest version: \(update.version)")
        }
        .onDrop(of: [UTType.fileURL.identifier], isTargeted: nil, perform: handleDrop(providers:))
    }

    private var controlPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Button("Import") {
                    viewModel.importFile()
                    sampleListFocused = true
                }
                .frame(maxWidth: .infinity)

                Button("Export") {
                    viewModel.exportFiles()
                    sampleListFocused = true
                }
                .frame(maxWidth: .infinity)
                .disabled(viewModel.worksheet == nil || viewModel.isLoading)

                Button(viewModel.updateButtonTitle) {
                    Task {
                        await viewModel.checkForUpdates(showNoUpdateMessage: true)
                    }
                }
                .frame(maxWidth: .infinity)
            }

            Menu("Reference Spectra") {
                ForEach(viewModel.availableReferenceSpectra) { reference in
                    Button {
                        viewModel.toggleReferenceSpectrum(id: reference.id)
                    } label: {
                        HStack {
                            Text(reference.shortTitle)
                            Spacer()
                            if viewModel.selectedReferenceIDs.contains(reference.id) {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Picker("Normalization", selection: $viewModel.referenceNormalizationMode) {
                ForEach(ReferenceNormalizationMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(.menu)
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(viewModel.displayedFileName)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            if let errorMessage = viewModel.errorMessage {
                Text(errorMessage)
                    .foregroundStyle(.red)
            }

            if let exportMessage = viewModel.exportMessage {
                Text(exportMessage)
                    .foregroundStyle(.green)
            }

            if let updateStatusMessage = viewModel.updateStatusMessage {
                Text(updateStatusMessage)
                    .foregroundStyle(.blue)
            }

            if viewModel.isLoading {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Working...")
                }
            }

            ScrollViewReader { proxy in
                List(selection: $viewModel.selectedIndexes) {
                    ForEach(Array(viewModel.orderedMeasurements.enumerated()), id: \.element.0) { displayIndex, entry in
                        let originalIndex = entry.0
                        let measurement = entry.1
                        Text("#\(displayIndex + 1) \(measurement.title)")
                            .tag(originalIndex)
                    }
                }
                .listStyle(.sidebar)
                .onReceive(viewModel.$primarySelection) { newValue in
                    withAnimation(.easeInOut(duration: 0.18)) {
                        proxy.scrollTo(newValue, anchor: .center)
                    }
                }
            }
            .focusable()
            .focused($sampleListFocused)
            .onTapGesture {
                sampleListFocused = true
            }
            .onMoveCommand { direction in
                switch direction {
                case .up:
                    viewModel.moveSelection(by: -1)
                case .down:
                    viewModel.moveSelection(by: 1)
                default:
                    break
                }
            }

            HStack(spacing: 8) {
                Button("Previous") {
                    viewModel.moveSelection(by: -1)
                    sampleListFocused = true
                }
                .frame(maxWidth: .infinity)

                Button("Next") {
                    viewModel.moveSelection(by: 1)
                    sampleListFocused = true
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(16)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }

    private var rightPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            if !viewModel.selectedMeasurements.isEmpty {
                if viewModel.isMultiSelection {
                    HStack {
                        Text("\(viewModel.selectedMeasurements.count) samples selected")
                            .font(.title3.weight(.semibold))
                        Spacer()
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(viewModel.selectedMeasurements, id: \.0) { index, measurement in
                                    HStack(spacing: 6) {
                                        Circle()
                                            .fill(Color.seriesColor(for: index))
                                            .frame(width: 10, height: 10)
                                        Text(measurement.title)
                                            .font(.subheadline.weight(.medium))
                                            .lineLimit(1)
                                    }
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(
                                        RoundedRectangle(cornerRadius: 10)
                                            .stroke(Color(nsColor: .separatorColor))
                                    )
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    .padding(.horizontal, 4)
                } else if let measurement = viewModel.selectedMeasurement {
                    let items = viewModel.summaryItems(for: measurement)
                    let columns = Array(
                        repeating: GridItem(.flexible(minimum: 48, maximum: .infinity), spacing: 12),
                        count: max(7, items.count)
                    )

                    LazyVGrid(columns: columns, alignment: .center, spacing: 12) {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                            SummaryChip(label: item.0, value: item.1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .top)
                    .fixedSize(horizontal: false, vertical: true)
                }

                SpectrumChartView(
                    selections: viewModel.selectedMeasurements,
                    referenceSpectra: viewModel.selectedReferenceSpectra,
                    referenceNormalizationMode: viewModel.referenceNormalizationMode,
                    onShowInfo: { viewModel.isShowingInfo = true }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "waveform.path.ecg")
                        .font(.system(size: 40))
                        .foregroundStyle(.secondary)
                    Text("Import a TBWK file to view spectra.")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(16)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private func handleDrop(providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first else { return false }
        provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
            guard
                let data = item as? Data,
                let url = URL(dataRepresentation: data, relativeTo: nil)
            else { return }

            DispatchQueue.main.async {
                viewModel.load(fileURL: url)
            }
        }
        return true
    }
}

private struct SummaryChip: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .center, spacing: 6) {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.45)
                .frame(maxWidth: .infinity)
            Text(value)
                .font(.system(size: 20, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.35)
                .frame(maxWidth: .infinity)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .stroke(Color(nsColor: .separatorColor))
        )
    }
}

private struct SpectrumChartView: View {
    let selections: [(Int, TBWKCore.Measurement)]
    let referenceSpectra: [ReferenceSpectrum]
    let referenceNormalizationMode: ReferenceNormalizationMode
    let onShowInfo: () -> Void

    @State private var selectedPoint: ChartSelection?

    private let minX = 220.0
    private let maxX = 350.0

    private var maxY: Double {
        max((selections.flatMap(\.1.yValues).max() ?? 0) + 1.0, 1.0)
    }

    private var singleSelection: (Int, TBWKCore.Measurement)? {
        selections.count == 1 ? selections.first : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Absrobance(nm)")
                    .font(.headline)
                Spacer()
                if !referenceSpectra.isEmpty {
                    Text("Reference: \(referenceNormalizationMode.rawValue)")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                }
                Button("Info", action: onShowInfo)
                Button("Reset Selection") { selectedPoint = nil }
            }

            let showSampleLegendInChart = selections.count <= 1
            if showSampleLegendInChart || !referenceSpectra.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        if showSampleLegendInChart {
                            ForEach(selections, id: \.0) { index, measurement in
                                HStack(spacing: 6) {
                                    Circle()
                                        .fill(seriesColor(for: index))
                                        .frame(width: 10, height: 10)
                                    Text(measurement.title)
                                        .font(.subheadline.weight(.medium))
                                        .lineLimit(1)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(Color(nsColor: .separatorColor))
                                )
                            }
                        }

                        ForEach(referenceSpectra) { reference in
                            HStack(spacing: 6) {
                                Capsule()
                                    .fill(referenceColor(for: reference.id))
                                    .frame(width: 18, height: 4)
                                Text("\(reference.title) (\(referenceNormalizationMode.rawValue))")
                                    .font(.subheadline.weight(.medium))
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(Color(nsColor: .separatorColor))
                            )
                        }
                    }
                }
            }

            GeometryReader { geometry in
                let plotInsets = NSEdgeInsets(top: 24, left: 74, bottom: 52, right: 20)
                let plotRect = CGRect(
                    x: plotInsets.left,
                    y: plotInsets.bottom,
                    width: max(geometry.size.width - plotInsets.left - plotInsets.right, 1),
                    height: max(geometry.size.height - plotInsets.top - plotInsets.bottom, 1)
                )

                ZStack(alignment: .topLeading) {
                    RoundedRectangle(cornerRadius: 20)
                        .fill(Color(nsColor: .controlBackgroundColor))
                    RoundedRectangle(cornerRadius: 20)
                        .stroke(Color(nsColor: .separatorColor))

                    Canvas { context, size in
                        drawChart(in: &context, size: size, plotRect: plotRect)
                    }

                    if let selectedPoint {
                        let point = chartPoint(for: selectedPoint, in: plotRect)
                        Circle()
                            .fill(Color.white)
                            .frame(width: 16, height: 16)
                            .overlay(Circle().fill(Color.accentColor).frame(width: 10, height: 10))
                            .position(point)

                        Text(selectionLabel(for: selectedPoint))
                            .font(.system(size: 16, weight: .bold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.ultraThinMaterial)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .position(
                                x: min(max(point.x + 116, 116), geometry.size.width - 116),
                                y: max(point.y - 22, 22)
                            )
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onEnded { value in
                            selectedPoint = nearestPoint(to: value.location, in: plotRect)
                        }
                )
            }
        }
    }

    private func drawChart(in context: inout GraphicsContext, size: CGSize, plotRect: CGRect) {
        let markerStyle = StrokeStyle(lineWidth: 1.5, dash: [6, 6])

        for tick in 0...5 {
            let fraction = CGFloat(tick) / 5.0
            let x = plotRect.minX + fraction * plotRect.width
            let y = plotRect.minY + fraction * plotRect.height
            context.stroke(Path { path in
                path.move(to: CGPoint(x: x, y: plotRect.minY))
                path.addLine(to: CGPoint(x: x, y: plotRect.maxY))
                path.move(to: CGPoint(x: plotRect.minX, y: y))
                path.addLine(to: CGPoint(x: plotRect.maxX, y: y))
            }, with: .color(Color.gray.opacity(0.18)))
        }

        for marker in [230.0, 260.0, 280.0] {
            let x = plotRect.minX + CGFloat((marker - minX) / (maxX - minX)) * plotRect.width
            context.stroke(
                Path(CGRect(x: x, y: plotRect.minY, width: 0, height: plotRect.height)),
                with: .color(Color.gray.opacity(0.45)),
                style: markerStyle
            )
        }

        context.stroke(Path(plotRect), with: .color(Color.gray.opacity(0.6)), lineWidth: 1.5)

        for (seriesIndex, measurement) in selections {
            let visible = measurement.xValues.enumerated().filter { $0.element >= minX && $0.element <= maxX }
            var path = Path()
            for (offset, pair) in visible.enumerated() {
                let point = chartPoint(x: pair.element, y: measurement.yValues[pair.offset], in: plotRect)
                if offset == 0 {
                    path.move(to: point)
                } else {
                    path.addLine(to: point)
                }
            }
            context.stroke(path, with: .color(seriesColor(for: seriesIndex)), lineWidth: 2.5)
        }

        for reference in referenceSpectra {
            let normalized = normalizedReferenceSpectrum(reference)
            guard !normalized.isEmpty else { continue }
            var path = Path()
            for (offset, point) in normalized.enumerated() {
                let chartPoint = chartPoint(x: point.x, y: point.y, in: plotRect)
                if offset == 0 {
                    path.move(to: chartPoint)
                } else {
                    path.addLine(to: chartPoint)
                }
            }
            context.stroke(
                path,
                with: .color(referenceColor(for: reference.id)),
                style: StrokeStyle(lineWidth: 2.0, dash: [8, 5])
            )
        }

        for tick in 0...5 {
            let fraction = Double(tick) / 5.0
            let xValue = minX + (maxX - minX) * fraction
            let yValue = maxY - (maxY * fraction)
            let x = plotRect.minX + CGFloat(fraction) * plotRect.width
            let y = plotRect.minY + CGFloat(fraction) * plotRect.height

            context.draw(Text("\(xValue, specifier: "%.1f")").font(.caption2), at: CGPoint(x: x, y: plotRect.maxY + 14))
            context.draw(Text("\(yValue, specifier: "%.2f")").font(.caption2), at: CGPoint(x: plotRect.minX - 30, y: y))
        }

        for marker in [230, 260, 280] {
            let x = plotRect.minX + CGFloat((Double(marker) - minX) / (maxX - minX)) * plotRect.width
            context.draw(Text("\(marker)").font(.caption2.weight(.semibold)), at: CGPoint(x: x, y: plotRect.maxY + 30))
        }
    }

    private func chartPoint(for point: ChartSelection, in plotRect: CGRect) -> CGPoint {
        chartPoint(x: point.xValue, y: point.yValue, in: plotRect)
    }

    private func chartPoint(x: Double, y: Double, in plotRect: CGRect) -> CGPoint {
        let normalizedX = (x - minX) / (maxX - minX)
        let normalizedY = max(0, min(y / maxY, 1))
        return CGPoint(
            x: plotRect.minX + CGFloat(normalizedX) * plotRect.width,
            y: plotRect.maxY - CGFloat(normalizedY) * plotRect.height
        )
    }

    private func nearestPoint(to location: CGPoint, in plotRect: CGRect) -> ChartSelection? {
        guard plotRect.contains(location) else { return nil }

        var best: ChartSelection?
        var bestDistance = CGFloat.greatestFiniteMagnitude

        for (seriesIndex, measurement) in selections {
            for (index, xValue) in measurement.xValues.enumerated() where xValue >= minX && xValue <= maxX {
                let point = chartPoint(x: xValue, y: measurement.yValues[index], in: plotRect)
                let distance = hypot(point.x - location.x, point.y - location.y)
                if distance < bestDistance {
                    bestDistance = distance
                    best = ChartSelection(index: seriesIndex, xValue: xValue, yValue: measurement.yValues[index])
                }
            }
        }

        return bestDistance <= 24 ? best : nil
    }

    private func selectionLabel(for point: ChartSelection) -> String {
        if let measurement = selections.first(where: { $0.0 == point.index })?.1, selections.count > 1 {
            return "\(measurement.title)  x=\(String(format: "%.1f", point.xValue)) y=\(String(format: "%.2f", point.yValue))"
        }
        return "x=\(String(format: "%.1f", point.xValue)) y=\(String(format: "%.2f", point.yValue))"
    }

    private func seriesColor(for index: Int) -> Color {
        Color.seriesColor(for: index)
    }

    private func referenceColor(for id: String) -> Color {
        let palette: [Color] = [
            Color(red: 0.10, green: 0.10, blue: 0.10),
            Color(red: 0.58, green: 0.12, blue: 0.12),
            Color(red: 0.08, green: 0.32, blue: 0.62),
            Color(red: 0.43, green: 0.27, blue: 0.63),
            Color(red: 0.42, green: 0.36, blue: 0.08),
            Color(red: 0.00, green: 0.45, blue: 0.45),
            Color(red: 0.55, green: 0.24, blue: 0.00),
            Color(red: 0.36, green: 0.00, blue: 0.36),
        ]
        let orderedIDs = referenceSpectra.map(\.id).sorted()
        let index = orderedIDs.firstIndex(of: id) ?? 0
        return palette[index]
    }

    private func normalizedReferenceSpectrum(_ reference: ReferenceSpectrum) -> [(x: Double, y: Double)] {
        let points = zip(reference.xValues, reference.yValues)
            .filter { $0.0 >= minX && $0.0 <= maxX }
            .map { (x: $0.0, y: $0.1) }

        guard !points.isEmpty else { return [] }

        let shifted = baselineCorrect(points: points)
        let shiftedMax = shifted.map(\.y).max() ?? 0
        let targetMeasurement = selections.first(where: { $0.0 == selectedSeriesIndex })?.1 ?? selections.first?.1
        let targetMeasurementPoints = targetMeasurement.map { baselineCorrect(points:
            zip($0.xValues, $0.yValues)
                .filter { $0.0 >= minX && $0.0 <= maxX }
                .map { (x: $0.0, y: $0.1) }
        ) } ?? []
        let targetPeak = max(targetMeasurementPoints.map(\.y).max() ?? 0, 0.00001)

        switch referenceNormalizationMode {
        case .peakNormalize:
            let scale = targetPeak / max(shiftedMax, 0.00001)
            return shifted.map { (x: $0.x, y: $0.y * scale) }
        case .areaNormalize:
            let referenceArea = trapezoidArea(points: shifted)
            let sampleArea = targetMeasurement.map(normalizationTargetArea(for:)) ?? targetPeak
            let scale = sampleArea / max(referenceArea, 0.00001)
            return shifted.map { (x: $0.x, y: $0.y * scale) }
        case .fitToSample:
            guard let targetMeasurement else {
                let scale = targetPeak / max(shiftedMax, 0.00001)
                return shifted.map { (x: $0.x, y: $0.y * scale) }
            }

            let overlapping = shifted.compactMap { point -> (Double, Double)? in
                guard let sampleY = interpolatedSampleY(at: point.x, measurement: targetMeasurement) else {
                    return nil
                }
                return (point.y, max(sampleY, 0))
            }

            let numerator = overlapping.reduce(0.0) { $0 + ($1.0 * $1.1) }
            let denominator = overlapping.reduce(0.0) { $0 + ($1.0 * $1.0) }
            let scale = numerator / max(denominator, 0.00001)
            return shifted.map { (x: $0.x, y: $0.y * scale) }
        }
    }

    private var selectedSeriesIndex: Int {
        selections.first?.0 ?? 0
    }

    private func normalizationTargetArea(for measurement: TBWKCore.Measurement) -> Double {
        let filtered = baselineCorrect(points:
            zip(measurement.xValues, measurement.yValues)
            .filter { $0.0 >= minX && $0.0 <= maxX }
            .map { (x: $0.0, y: max($0.1, 0)) }
        )
        return trapezoidArea(points: filtered)
    }

    private func baselineCorrect(points: [(x: Double, y: Double)]) -> [(x: Double, y: Double)] {
        guard let minY = points.map(\.y).min() else { return [] }
        return points.map { (x: $0.x, y: max($0.y - minY, 0)) }
    }

    private func trapezoidArea(points: [(x: Double, y: Double)]) -> Double {
        guard points.count > 1 else { return 0 }
        return zip(points, points.dropFirst()).reduce(0.0) { partial, pair in
            let dx = pair.1.x - pair.0.x
            let avgY = (pair.0.y + pair.1.y) / 2.0
            return partial + dx * avgY
        }
    }

    private func interpolatedSampleY(at x: Double, measurement: TBWKCore.Measurement) -> Double? {
        let xs = measurement.xValues
        let ys = measurement.yValues
        guard xs.count == ys.count, xs.count > 1 else { return nil }

        for index in 0..<(xs.count - 1) {
            let x0 = xs[index]
            let x1 = xs[index + 1]
            guard x >= x0, x <= x1, x1 != x0 else { continue }
            let fraction = (x - x0) / (x1 - x0)
            return ys[index] + (ys[index + 1] - ys[index]) * fraction
        }
        return nil
    }
}

private extension Color {
    static func seriesColor(for index: Int) -> Color {
        let palette: [Color] = [
            .accentColor, .red, .green, .orange, .purple, .pink, .teal, .brown, .indigo, .mint
        ]
        return palette[index % palette.count]
    }
}

private struct ViewerInfoSheet: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("NanoDrop 說明")
                        .font(.title.bold())
                    Spacer()
                }

                Group {
                    Text("NanoDrop 數值").font(.title3.bold())
                    Text("A260 (核酸波峰): 測定 DNA 或 RNA 的濃度。")
                    Text("A280 (蛋白質波峰): 檢測蛋白質污染。")
                    Text("A230 (有機雜質): 檢測酚類、胍鹽或碳水化合物等污染物。")
                    Text("DNA: 260 nm")
                    Text("protein: 280 nm")
                    Text("phenol: 270 nm")
                    Text("guanidine / salt: 230 nm")
                    Text("EDTA: 230 nm")
                    Text("carbohydrate: 230 nm")
                }

                Group {
                    Text("A260/280 Ratio:").font(.headline)
                    Text("DNA：~1.8")
                    Text("RNA：~2.0")
                    Text("低比值表示的汙染物：")
                    Text("· 蛋白質")
                    Text("· 殘餘酚或提取方法中使用的其他試劑")
                }

                Group {
                    Text("A260/230 Ratio:").font(.headline)
                    Text("DNA/RNA：~2.0-2.2")
                    Text("低比值表示的汙染物：")
                    Text("· 蛋白質")
                    Text("· 碳水化合物殘留（通常是植物的問題）")
                    Text("· 來自核酸提取的殘餘酚")
                    Text("· 殘餘胍（通常用於管柱式套件）")
                    Text("· 用於沉澱的糖原")
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("無汙染的純化 DNA (A，紅色)").font(.headline)
                    Text("被胍(B，綠色)和酚(C，褐色)汙染之光譜。")
                }
                .padding(12)
                .background(.regularMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                bundledImage("contaminants_cause", ext: "jpg")
                bundledImage("uv_absorbance_spectra_common_contaminant_with_dna", ext: "jpg")
                bundledImage("uv_absorbance_spectra_of_phenol_and_trizol_mixed_with_rna", ext: "jpg")
                bundledImage("spectra_of_contaminated_dna", ext: "webp")
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minWidth: 1100, minHeight: 760)
    }

    @ViewBuilder
    private func bundledImage(_ name: String, ext: String) -> some View {
        if let url = Bundle.module.url(forResource: name, withExtension: ext),
           let image = NSImage(contentsOf: url) {
            Image(nsImage: image)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(nsColor: .separatorColor)))
        }
    }
}
