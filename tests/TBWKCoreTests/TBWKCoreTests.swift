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
}
