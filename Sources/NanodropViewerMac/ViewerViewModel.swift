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
    @Published var latestVersion: String?
    @Published var hasEditedChanges = false
    private var editableDocument: TBWKEditableDocument?
    private var hasCheckedForUpdates = false
    private var transientMessageTask: Task<Void, Never>?

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
    var currentVersion: String { UpdateService.currentVersion() }

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
            let editableDocument = try TBWKEditableDocument(fileURL: fileURL)
            let worksheet = try editableDocument.loadWorksheet()
            self.editableDocument = editableDocument
            self.worksheet = worksheet
            self.fileURL = fileURL
            self.hasEditedChanges = false
            let firstIndex = orderedMeasurements.first?.0 ?? 0
            self.primarySelection = firstIndex
            self.selectedIndexes = worksheet.measurements.isEmpty ? [] : [firstIndex]
        } catch {
            self.errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func exportFiles() {
        guard let fileURL, let worksheet else { return }

        isLoading = true
        errorMessage = nil
        exportMessage = nil

        do {
            let baseFolder = try chooseExportDirectory(for: fileURL)
            let result = try TBWKExporter.export(worksheet: worksheet, outputDirectory: baseFolder, baseName: fileURL.deletingPathExtension().lastPathComponent)
            exportMessage = "Exported to \(result.pdfURL.deletingLastPathComponent().path)"
            scheduleTransientMessageClear()
        } catch {
            if (error as NSError).domain == NSCocoaErrorDomain && (error as NSError).code == NSUserCancelledError {
                exportMessage = nil
            } else {
                errorMessage = error.localizedDescription
            }
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

    func clearSelectedReferenceSpectra() {
        selectedReferenceIDs.removeAll()
    }

    func renameSelectedMeasurement() {
        guard let selectedMeasurement else { return }

        let alert = NSAlert()
        alert.messageText = "Rename sample"
        alert.informativeText = "Update the selected sample name and save later as a new TBWK file."
        alert.alertStyle = .informational
        let textField = NSTextField(string: selectedMeasurement.title)
        textField.frame = NSRect(x: 0, y: 0, width: 320, height: 24)
        alert.accessoryView = textField
        alert.addButton(withTitle: "Rename")
        alert.addButton(withTitle: "Cancel")

        guard alert.runModal() == .alertFirstButtonReturn else { return }
        applyRename(textField.stringValue)
    }

    func deleteSelectedMeasurement() {
        guard let selectedMeasurement else { return }

        let alert = NSAlert()
        alert.messageText = "Delete sample?"
        alert.informativeText = "Remove \"\(selectedMeasurement.title)\" from the edited copy. The original TBWK file will not be overwritten."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Delete")
        alert.addButton(withTitle: "Cancel")

        guard alert.runModal() == .alertFirstButtonReturn else { return }
        applyDelete()
    }

    func saveEditedCopy() {
        guard hasEditedChanges, let fileURL, let editableDocument else { return }

        let savePanel = NSSavePanel()
        savePanel.canCreateDirectories = true
        savePanel.directoryURL = fileURL.deletingLastPathComponent()
        savePanel.nameFieldStringValue = editedFileName(for: fileURL)
        savePanel.allowedContentTypes = []
        savePanel.title = "Save edited TBWK file"

        guard savePanel.runModal() == .OK, let destinationURL = savePanel.url else { return }

        do {
            try editableDocument.save(to: destinationURL)
            self.fileURL = destinationURL
            hasEditedChanges = false
            exportMessage = "Saved edited file to \(destinationURL.path)"
            scheduleTransientMessageClear()
        } catch {
            errorMessage = error.localizedDescription
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
            let result = try await UpdateService.checkForUpdate()
            latestVersion = result.latestVersion
            if let update = result.update {
                updateInfo = update
                updateStatusMessage = "Update \(update.version) is available."
            } else if showNoUpdateMessage {
                updateInfo = nil
                updateStatusMessage = "You are up to date."
                scheduleTransientMessageClear()
            }
        } catch {
            if showNoUpdateMessage {
                updateStatusMessage = "Unable to check for updates."
                scheduleTransientMessageClear()
            }
        }
    }

    func downloadUpdate() {
        guard let updateInfo else { return }
        UpdateService.openDownloadURL(updateInfo.downloadURL)
    }

    private func chooseExportDirectory(for fileURL: URL) throws -> URL {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = true
        panel.prompt = "Export"
        panel.title = "Choose export folder"
        panel.directoryURL = fileURL.deletingLastPathComponent()

        guard panel.runModal() == .OK, let selectedDirectory = panel.url else {
            throw NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError)
        }

        let folderName = sanitizeForFileName(fileURL.deletingPathExtension().lastPathComponent)
        let exportDirectory = selectedDirectory
            .appendingPathComponent(folderName, isDirectory: true)

        try FileManager.default.createDirectory(at: exportDirectory, withIntermediateDirectories: true)
        return exportDirectory
    }

    private func applyRename(_ newName: String) {
        guard var editableDocument else { return }
        do {
            try editableDocument.renameMeasurement(at: primarySelection, to: newName)
            try refreshWorksheet(using: editableDocument)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func applyDelete() {
        guard var editableDocument else { return }
        let deletedIndex = primarySelection

        do {
            try editableDocument.deleteMeasurement(at: deletedIndex)
            try refreshWorksheet(using: editableDocument, deletedIndex: deletedIndex)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func refreshWorksheet(using editableDocument: TBWKEditableDocument, deletedIndex: Int? = nil) throws {
        let worksheet = try editableDocument.loadWorksheet()
        self.editableDocument = editableDocument
        self.worksheet = worksheet
        self.hasEditedChanges = true

        let adjustedSelection: Int
        if worksheet.measurements.isEmpty {
            adjustedSelection = 0
            selectedIndexes = []
            primarySelection = 0
        } else if let deletedIndex {
            adjustedSelection = min(deletedIndex, worksheet.measurements.count - 1)
            selectedIndexes = [adjustedSelection]
            primarySelection = adjustedSelection
        } else {
            adjustedSelection = min(primarySelection, worksheet.measurements.count - 1)
            selectedIndexes = [adjustedSelection]
            primarySelection = adjustedSelection
        }
    }

    private func editedFileName(for fileURL: URL) -> String {
        let baseName = fileURL.deletingPathExtension().lastPathComponent
        let ext = fileURL.pathExtension
        let editedBase = baseName.hasSuffix("_edited") ? baseName : "\(baseName)_edited"
        return ext.isEmpty ? editedBase : "\(editedBase).\(ext)"
    }

    private func scheduleTransientMessageClear() {
        transientMessageTask?.cancel()
        let currentExportMessage = exportMessage
        let currentUpdateStatusMessage = updateStatusMessage
        transientMessageTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3.5))
            if exportMessage == currentExportMessage {
                exportMessage = nil
            }
            if updateStatusMessage == currentUpdateStatusMessage && updateInfo == nil {
                updateStatusMessage = nil
            }
        }
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
