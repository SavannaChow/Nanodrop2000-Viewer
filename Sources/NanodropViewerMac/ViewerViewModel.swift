import AppKit
import Foundation
import TBWKCore

@MainActor
final class ViewerViewModel: ObservableObject {
    @Published var worksheet: Worksheet?
    @Published var fileURL: URL?
    @Published var selectedIndexes: Set<Int> = [0] {
        didSet {
            normalizeSelection()
        }
    }
    @Published var primarySelection = 0
    @Published var errorMessage: String?
    @Published var exportMessage: String?
    @Published var isLoading = false
    @Published var isShowingInfo = false
    @Published var selectedReferenceIDs: Set<String> = []
    @Published var referenceNormalizationMode: ReferenceNormalizationMode = .peakNormalize
    @Published var updateInfo: AppUpdateInfo?
    @Published var updateStatusMessage: String?
    private var hasCheckedForUpdates = false

    let availableReferenceSpectra: [ReferenceSpectrum] = ReferenceSpectrumLibrary.loadBundledSpectra()

    var measurements: [TBWKCore.Measurement] {
        worksheet?.measurements ?? []
    }

    var orderedMeasurements: [(Int, TBWKCore.Measurement)] {
        measurements
            .enumerated()
            .sorted {
                if $0.element.time == $1.element.time {
                    return $0.offset < $1.offset
                }
                return $0.element.time < $1.element.time
            }
            .map { ($0.offset, $0.element) }
    }

    var selectedMeasurement: TBWKCore.Measurement? {
        measurements.indices.contains(primarySelection) ? measurements[primarySelection] : nil
    }

    var selectedMeasurements: [(Int, TBWKCore.Measurement)] {
        orderedMeasurements.filter { selectedIndexes.contains($0.0) }
    }

    var isMultiSelection: Bool {
        selectedMeasurements.count > 1
    }

    var displayedFileName: String {
        fileURL?.lastPathComponent ?? "No file selected"
    }

    var hasAvailableUpdate: Bool { updateInfo != nil }

    var selectedReferenceSpectra: [ReferenceSpectrum] {
        availableReferenceSpectra.filter { selectedReferenceIDs.contains($0.id) }
    }

    func importFile() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowedContentTypes = []

        guard panel.runModal() == .OK, let url = panel.url else { return }
        load(fileURL: url)
    }

    func load(fileURL: URL) {
        isLoading = true
        errorMessage = nil
        exportMessage = nil

        do {
            let worksheet = try TBWKExporter.loadWorksheet(from: fileURL)
            self.worksheet = worksheet
            self.fileURL = fileURL
            let firstIndex = orderedMeasurements.first?.0 ?? 0
            self.primarySelection = firstIndex
            self.selectedIndexes = worksheet.measurements.isEmpty ? [] : [firstIndex]
        } catch {
            self.errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func exportFiles() {
        guard let fileURL, worksheet != nil else { return }

        isLoading = true
        errorMessage = nil
        exportMessage = nil

        do {
            let baseFolder = try exportDirectory(for: fileURL)
            let result = try TBWKExporter.export(
                fileURL: fileURL,
                outputDirectory: baseFolder,
                baseName: fileURL.deletingPathExtension().lastPathComponent
            )
            exportMessage = "Exported to \(result.pdfURL.deletingLastPathComponent().path)"
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func moveSelection(by delta: Int) {
        guard !orderedMeasurements.isEmpty else { return }
        let currentPosition = orderedMeasurements.firstIndex { $0.0 == primarySelection } ?? 0
        let nextPosition = max(0, min(orderedMeasurements.count - 1, currentPosition + delta))
        let nextIndex = orderedMeasurements[nextPosition].0
        primarySelection = nextIndex
        selectedIndexes = [nextIndex]
    }

    func selectMeasurement(at index: Int) {
        guard measurements.indices.contains(index) else { return }
        primarySelection = index
        selectedIndexes = [index]
    }

    func summaryItems(for measurement: TBWKCore.Measurement) -> [(String, String)] {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")

        var keyedItems: [String: String] = [
            "Sample": measurement.title,
            "Time": formatter.string(from: measurement.time),
        ]

        for key in measurement.properties.properties.keys {
            guard let property = measurement.properties.properties[key] else { continue }
            let unit = property.value.unit.map { " \($0)" } ?? ""
            keyedItems[key] = "\(formatDouble(property.value.value))\(unit)"
        }

        let preferredOrder = [
            "Sample",
            "Nucleic Acid",
            "260/280",
            "260/230",
            "A260",
            "A280",
            "Time",
        ]

        var orderedItems: [(String, String)] = []
        for key in preferredOrder {
            if let value = keyedItems.removeValue(forKey: key) {
                orderedItems.append((key, value))
            }
        }

        for key in keyedItems.keys.sorted() {
            if let value = keyedItems[key] {
                orderedItems.append((key, value))
            }
        }

        return orderedItems
    }

    func toggleReferenceSpectrum(id: String) {
        if selectedReferenceIDs.contains(id) {
            selectedReferenceIDs.remove(id)
        } else {
            selectedReferenceIDs.insert(id)
        }
    }

    func checkForUpdatesIfNeeded() {
        guard !hasCheckedForUpdates else { return }
        hasCheckedForUpdates = true
        Task {
            await checkForUpdates(showNoUpdateMessage: false)
        }
    }

    func checkForUpdates(showNoUpdateMessage: Bool) async {
        do {
            if let update = try await UpdateService.checkForUpdate() {
                updateInfo = update
                updateStatusMessage = "Update \(update.version) is available."
            } else if showNoUpdateMessage {
                updateInfo = nil
                updateStatusMessage = "You are up to date."
            }
        } catch {
            if showNoUpdateMessage {
                updateStatusMessage = "Unable to check for updates."
            }
        }
    }

    func downloadUpdate() {
        guard let updateInfo else { return }
        UpdateService.openDownloadURL(updateInfo.downloadURL)
    }

    private func exportDirectory(for fileURL: URL) throws -> URL {
        let documentsURL = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )

        let folderName = sanitizeForFileName(fileURL.deletingPathExtension().lastPathComponent)
        let exportDirectory = documentsURL
            .appendingPathComponent("Nanodrop2000_viewer", isDirectory: true)
            .appendingPathComponent(folderName, isDirectory: true)

        try FileManager.default.createDirectory(at: exportDirectory, withIntermediateDirectories: true)
        return exportDirectory
    }

    private func sanitizeForFileName(_ name: String) -> String {
        let pattern = #"[\\/:*?"<>|]"#
        return name.replacingOccurrences(of: pattern, with: "_", options: .regularExpression)
    }

    private func formatDouble(_ value: Double) -> String {
        String(format: "%.2f", value)
    }

    private func normalizeSelection() {
        let valid = Set(selectedIndexes.filter { measurements.indices.contains($0) })
        if valid != selectedIndexes {
            selectedIndexes = valid
            return
        }

        if valid.isEmpty {
            if measurements.indices.contains(primarySelection) {
                selectedIndexes = [primarySelection]
            } else if !measurements.isEmpty {
                primarySelection = 0
                selectedIndexes = [0]
            }
            return
        }

        if !valid.contains(primarySelection) {
            primarySelection = orderedMeasurements.first(where: { valid.contains($0.0) })?.0 ?? 0
        }
    }
}
