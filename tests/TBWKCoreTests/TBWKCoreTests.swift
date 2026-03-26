import Foundation
import Testing
@testable import TBWKCore

struct TBWKCoreTests {
    @Test
    func parsesExampleWorksheet() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sampleURL = root.appendingPathComponent("examples/nanodrop-dna-measurements-01.twbk")

        let worksheet = try TBWKExporter.loadWorksheet(from: sampleURL)

        #expect(worksheet.measurements.count == 13)
        #expect(worksheet.measurements.first?.title == "wash")
        #expect(worksheet.measurements.first?.xLabel == "Wavelength (nm)")
        #expect(worksheet.measurements.first?.yLabel == "10mm Absorbance")
        #expect(
            worksheet.measurements.map(\.title) == [
                "wash",
                "blank",
                "BSD01",
                "BSD01",
                "BSD01 cntl A1",
                "wash",
                "BSD01 cntl A2",
                "wash",
                "BSD01 cntl A3",
                "BSD01 cntl A3",
                "BSD01 cntl A4",
                "wash",
                "wash",
            ]
        )
    }

    @Test
    func exportsCsvAndPdf() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sampleURL = root.appendingPathComponent("examples/nanodrop-dna-measurements-01.twbk")
        let tempDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)

        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
        let result = try TBWKExporter.export(fileURL: sampleURL, outputDirectory: tempDirectory, baseName: "swift-test")

        #expect(FileManager.default.fileExists(atPath: result.summaryURL.path))
        #expect(FileManager.default.fileExists(atPath: result.spectrumURL.path))
        #expect(FileManager.default.fileExists(atPath: result.pdfURL.path))

        let summary = try String(contentsOf: result.summaryURL, encoding: .utf8)
        let spectrum = try String(contentsOf: result.spectrumURL, encoding: .utf8)
        #expect(summary.contains("sample_name"))
        #expect(summary.contains("A260"))
        #expect(spectrum.contains("x_value"))
        #expect(spectrum.contains("y_value"))
    }

    @Test
    func editsMeasurementNamesAndDeletesSamplesViaSaveAs() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sampleURL = root.appendingPathComponent("examples/nanodrop-dna-measurements-01.twbk")
        var document = try TBWKEditableDocument(fileURL: sampleURL)

        try document.renameMeasurement(at: 0, to: "wash-renamed")
        try document.deleteMeasurement(at: 1)

        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("tbwk")
        try FileManager.default.createDirectory(at: tempURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try document.save(to: tempURL)

        let worksheet = try TBWKExporter.loadWorksheet(from: tempURL)
        #expect(worksheet.measurements.count == 12)
        #expect(worksheet.measurements.first?.title == "wash-renamed")
        #expect(worksheet.measurements.dropFirst().contains(where: { $0.title == "blank" }) == false)
    }
}
