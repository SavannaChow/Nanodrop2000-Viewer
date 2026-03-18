import AppKit
import Foundation

public struct Worksheet {
    public var measurements: [Measurement]

    public init(measurements: [Measurement]) {
        self.measurements = measurements
    }
}

public struct Measurement {
    public var title: String
    public var xValues: [Double]
    public var xLabel: String
    public var yValues: [Double]
    public var yLabel: String
    public var time: Date
    public var properties: PropertyBag
}

public struct PropertyBag {
    public var methodTitle: String
    public var methodDescription: String
    public var methodFilename: String
    public var properties: [String: Property]

    public init(
        methodTitle: String = "",
        methodDescription: String = "",
        methodFilename: String = "",
        properties: [String: Property] = [:]
    ) {
        self.methodTitle = methodTitle
        self.methodDescription = methodDescription
        self.methodFilename = methodFilename
        self.properties = properties
    }
}

public struct Property {
    public var id: String
    public var type: String
    public var value: Value
    public var rawValue: Value?
}

public struct Value {
    public var title: String
    public var digits: Int
    public var value: Double
    public var unit: String?
    public var factor: Double?
}

public struct ExportResult {
    public var summaryURL: URL
    public var spectrumURL: URL
    public var pdfURL: URL
}

public enum TBWKError: Error, LocalizedError {
    case invalidFormat(String)
    case missingBlock(String)
    case unsupportedBlock(Int)

    public var errorDescription: String? {
        switch self {
        case .invalidFormat(let message):
            return message
        case .missingBlock(let message):
            return message
        case .unsupportedBlock(let type):
            return "Unsupported TBWK block type \(type)."
        }
    }
}

private struct UVData {
    let longLabel: String
    let shortLabel: String
    let values: [Double]
}

private struct SpectrumMetadata {
    let sampleName: String
    let sampleTime: Date
}

private struct MeasurementInfo {
    let sampleName: String
}

private struct ParsedBlock {
    let type: Int
    let offset: Int
    let xmlElement: XMLElement?
    let children: [ParsedBlock]
    let uvData: UVData?
    let spectrumMetadata: SpectrumMetadata?
    let measurementInfo: MeasurementInfo?
    let text: String?
}

private extension Data {
    subscript(safe range: Range<Int>) -> Data {
        let lower = Swift.max(0, Swift.min(count, range.lowerBound))
        let upper = Swift.max(lower, Swift.min(count, range.upperBound))
        return subdata(in: lower..<upper)
    }

    func littleEndianUInt32(at offset: Int) -> UInt32 {
        let bytes = self[safe: offset..<(offset + 4)]
        return bytes.enumerated().reduce(0) { partial, pair in
            partial | (UInt32(pair.element) << (pair.offset * 8))
        }
    }

    func littleEndianUInt64(at offset: Int) -> UInt64 {
        let bytes = self[safe: offset..<(offset + 8)]
        return bytes.enumerated().reduce(0) { partial, pair in
            partial | (UInt64(pair.element) << (pair.offset * 8))
        }
    }

    func tbwkString(at offset: Int) throws -> (length: Int, value: String) {
        guard offset < count else {
            throw TBWKError.invalidFormat("Unexpected end of TBWK string.")
        }

        let length = Int(self[offset])
        let data = self[safe: (offset + 1)..<(offset + 1 + length)]
        guard let value = String(data: data, encoding: .utf8) else {
            throw TBWKError.invalidFormat("TBWK string is not valid UTF-8.")
        }
        return (length, value)
    }
}

private enum XMLHelpers {
    static func childElements(of node: XMLNode?) -> [XMLElement] {
        (node?.children ?? []).compactMap { $0 as? XMLElement }
    }

    static func firstChild(named name: String, in node: XMLNode?) -> XMLElement? {
        childElements(of: node).first { $0.name == name }
    }

    static func variables(in node: XMLNode?) -> [XMLElement] {
        childElements(of: node).filter { $0.name == "VAR" }
    }
}

private enum TBWKParser {
    static func parse(fileURL: URL) throws -> Worksheet {
        try parse(data: Data(contentsOf: fileURL))
    }

    static func parse(data: Data) throws -> Worksheet {
        let blocks = try unpack(data)
        let measurements = try blocks
            .filter { $0.type == 151 }
            .map(parseMeasurement(from:))

        return Worksheet(measurements: measurements)
    }

    private static func unpack(_ data: Data) throws -> [ParsedBlock] {
        let magic = data[safe: 0..<4]
        guard magic == Data([0xfe, 0xff, 0xff, 0xff]) else {
            throw TBWKError.invalidFormat("Input is not a TBWK file.")
        }

        let headerSize = Int(data.littleEndianUInt32(at: 32))
        var offset = 40 + headerSize
        var blocks: [ParsedBlock] = []

        while offset < data.count {
            let blockType = Int(data.littleEndianUInt32(at: offset))
            let blockSize = Int(data.littleEndianUInt32(at: offset + 4))
            let blockContent = data[safe: (offset + 12)..<(offset + 12 + blockSize)]
            blocks.append(try parseBlock(type: blockType, content: blockContent, offset: offset))
            offset += 12 + blockSize
        }

        return blocks
    }

    private static func parseBlock(type: Int, content: Data, offset: Int) throws -> ParsedBlock {
        switch type {
        case 62, 63, 990:
            return ParsedBlock(
                type: type,
                offset: offset,
                xmlElement: try parseXML(content[safe: 12..<content.count]),
                children: [],
                uvData: nil,
                spectrumMetadata: nil,
                measurementInfo: nil,
                text: nil
            )
        case 150:
            return ParsedBlock(type: type, offset: offset, xmlElement: nil, children: [], uvData: nil, spectrumMetadata: nil, measurementInfo: nil, text: nil)
        case 151, 920, 930:
            return ParsedBlock(
                type: type,
                offset: offset,
                xmlElement: nil,
                children: try unpack(content[safe: 12..<content.count]),
                uvData: nil,
                spectrumMetadata: nil,
                measurementInfo: nil,
                text: nil
            )
        case 152:
            return ParsedBlock(
                type: type,
                offset: offset,
                xmlElement: nil,
                children: [],
                uvData: nil,
                spectrumMetadata: nil,
                measurementInfo: try parseMeasurementInfo(content),
                text: nil
            )
        case 921:
            return ParsedBlock(
                type: type,
                offset: offset,
                xmlElement: nil,
                children: [],
                uvData: nil,
                spectrumMetadata: nil,
                measurementInfo: nil,
                text: try content.tbwkString(at: 12).value
            )
        case 922:
            return ParsedBlock(type: type, offset: offset, xmlElement: nil, children: [], uvData: nil, spectrumMetadata: nil, measurementInfo: nil, text: nil)
        case 931:
            return ParsedBlock(
                type: type,
                offset: offset,
                xmlElement: nil,
                children: [],
                uvData: nil,
                spectrumMetadata: try parseSpectrumMetadata(content),
                measurementInfo: nil,
                text: nil
            )
        case 932:
            return ParsedBlock(
                type: type,
                offset: offset,
                xmlElement: nil,
                children: [],
                uvData: try parseUVData(content),
                spectrumMetadata: nil,
                measurementInfo: nil,
                text: nil
            )
        case 991:
            let stringInfo = try content.tbwkString(at: 12)
            return ParsedBlock(
                type: type,
                offset: offset,
                xmlElement: try parseXML(content[safe: (13 + stringInfo.length)..<content.count]),
                children: [],
                uvData: nil,
                spectrumMetadata: nil,
                measurementInfo: nil,
                text: stringInfo.value
            )
        default:
            if type < 10000 {
                throw TBWKError.unsupportedBlock(type)
            }
            return ParsedBlock(type: type, offset: offset, xmlElement: nil, children: [], uvData: nil, spectrumMetadata: nil, measurementInfo: nil, text: nil)
        }
    }

    private static func parseXML(_ data: Data) throws -> XMLElement {
        let document = try XMLDocument(data: data, options: [])
        guard let root = document.rootElement() else {
            throw TBWKError.invalidFormat("TBWK XML block has no root element.")
        }
        return root
    }

    private static func parseMeasurementInfo(_ data: Data) throws -> MeasurementInfo {
        let content = data[safe: 12..<data.count]
        let first = try content.tbwkString(at: 0)
        let secondOffset = first.length + 1 + 8
        let second = try content.tbwkString(at: secondOffset)
        return MeasurementInfo(sampleName: second.value)
    }

    private static func parseSpectrumMetadata(_ data: Data) throws -> SpectrumMetadata {
        var offset = 12
        let blockType = try data.tbwkString(at: offset)
        offset += 1 + blockType.length

        let fileFormat = try data.tbwkString(at: offset)
        offset += 1 + fileFormat.length

        let sampleName = try data.tbwkString(at: offset)
        offset += 1 + sampleName.length

        let time = windowsFileTimeToDate(data[safe: offset..<(offset + 8)])
        return SpectrumMetadata(sampleName: sampleName.value, sampleTime: time)
    }

    private static func parseUVData(_ data: Data) throws -> UVData {
        let content = data[safe: 59..<data.count]
        let first = try content.tbwkString(at: 0)
        let second = try content.tbwkString(at: first.length + 1)
        var offset = 1 + first.length + 1 + second.length
        offset += 8
        offset += 21

        let numberOfValues = Int(content.littleEndianUInt32(at: offset))
        let valuesStart = offset + 4
        var values: [Double] = []
        values.reserveCapacity(numberOfValues)

        for index in 0..<numberOfValues {
            let valueBits = content.littleEndianUInt64(at: valuesStart + (index * 8))
            values.append(Double(bitPattern: valueBits))
        }

        return UVData(longLabel: first.value, shortLabel: second.value, values: values)
    }

    private static func parseMeasurement(from block: ParsedBlock) throws -> Measurement {
        let infoBlock = try requiredChild(type: 152, in: block)
        let wrapperBlock = try requiredChild(type: 920, in: block)
        let xmlBlock = try requiredChild(type: 62, in: block)
        let spectrumBlock = try requiredChild(type: 930, in: wrapperBlock)
        let metadataBlock = try requiredChild(type: 931, in: spectrumBlock)
        let uvBlocks = spectrumBlock.children.filter { $0.type == 932 }

        guard uvBlocks.count >= 2 else {
            throw TBWKError.missingBlock("Measurement is missing spectral data.")
        }
        guard let yBlock = uvBlocks.first?.uvData, let xBlock = uvBlocks.dropFirst().first?.uvData else {
            throw TBWKError.invalidFormat("Measurement spectrum data could not be parsed.")
        }
        guard let info = infoBlock.measurementInfo, let metadata = metadataBlock.spectrumMetadata, let xmlRoot = xmlBlock.xmlElement else {
            throw TBWKError.invalidFormat("Measurement metadata could not be parsed.")
        }

        return Measurement(
            title: info.sampleName,
            xValues: xBlock.values,
            xLabel: xBlock.longLabel,
            yValues: yBlock.values,
            yLabel: yBlock.longLabel,
            time: metadata.sampleTime,
            properties: parsePropertyBag(xmlRoot)
        )
    }

    private static func requiredChild(type: Int, in block: ParsedBlock) throws -> ParsedBlock {
        guard let child = block.children.first(where: { $0.type == type }) else {
            throw TBWKError.missingBlock("Required TBWK block \(type) is missing.")
        }
        return child
    }

    private static func parsePropertyBag(_ root: XMLElement) -> PropertyBag {
        var bag = PropertyBag()
        let firstParam = XMLHelpers.childElements(of: root).first
        let spectrumResults = XMLHelpers.childElements(of: firstParam).first

        for variable in XMLHelpers.variables(in: spectrumResults) {
            let name = variable.attribute(forName: "NAME")?.stringValue ?? ""
            let text = variable.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            switch name {
            case "m_MethodFilename":
                bag.methodFilename = text
            case "m_MethodTitle":
                bag.methodTitle = text
            case "m_MethodDescription":
                bag.methodDescription = text
            case "m_QuantGroups":
                for propertyNode in XMLHelpers.childElements(of: variable) where propertyNode.name == "PARAM" {
                    if let property = parseProperty(propertyNode) {
                        bag.properties[property.id] = property
                    }
                }
            default:
                continue
            }
        }

        return bag
    }

    private static func parseProperty(_ propertyNode: XMLElement) -> Property? {
        var propertyTitle = ""
        var propertyType = ""
        var propertyValue: Value?
        var rawValue: Value?

        for variable in XMLHelpers.variables(in: propertyNode) {
            let name = variable.attribute(forName: "NAME")?.stringValue ?? ""
            let text = variable.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            switch name {
            case "m_Title":
                propertyTitle = text
            case "m_ResultType":
                propertyType = text
            case "m_QuantElements":
                let parsed = parseValues(variable, type: propertyType)
                propertyValue = parsed.value
                rawValue = parsed.rawValue
            default:
                continue
            }
        }

        guard let propertyValue else {
            return nil
        }

        return Property(id: propertyTitle, type: propertyType, value: propertyValue, rawValue: rawValue)
    }

    private static func parseValues(_ quantElements: XMLElement, type: String) -> (value: Value?, rawValue: Value?) {
        var params: [String: [String: String]] = [:]

        for param in XMLHelpers.childElements(of: quantElements) where param.name == "PARAM" {
            var element: [String: String] = [:]
            for variable in XMLHelpers.variables(in: param) {
                let key = variable.attribute(forName: "NAME")?.stringValue ?? ""
                let value = variable.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                element[key] = value
            }
            if let resultType = element["m_ResultType"] {
                params[resultType] = element
            }
        }

        var value: Value?
        if let main = params[type],
           let mainValue = main["m_Value"].flatMap(Double.init),
           let digits = main["m_NumDigits"].flatMap(Int.init),
           let title = main["m_Title"] {
            value = Value(
                title: title,
                digits: digits,
                value: mainValue,
                unit: params["\(type)Unit"]?["m_Value"],
                factor: params["\(type)Factor"]?["m_Value"].flatMap(Double.init)
            )
        }

        var rawValue: Value?
        if let raw = params["\(type)Raw"],
           let rawNumber = raw["m_Value"].flatMap(Double.init),
           let rawDigits = raw["m_NumDigits"].flatMap(Int.init),
           let rawTitle = raw["m_Title"] {
            rawValue = Value(title: "\(rawTitle)Raw", digits: rawDigits, value: rawNumber, unit: nil, factor: nil)
        }

        return (value, rawValue)
    }

    private static func windowsFileTimeToDate(_ data: Data) -> Date {
        let fileTime = Int64(bitPattern: data.littleEndianUInt64(at: 0))
        let unixTimeIn100ns = fileTime - 116_444_736_000_000_000
        let seconds = Double(unixTimeIn100ns) * 0.000_000_1
        return Date(timeIntervalSince1970: seconds)
    }
}

public enum TBWKExporter {
    public static func loadWorksheet(from fileURL: URL) throws -> Worksheet {
        try TBWKParser.parse(fileURL: fileURL)
    }

    public static func export(fileURL: URL, outputDirectory: URL? = nil, baseName: String? = nil) throws -> ExportResult {
        let worksheet = try loadWorksheet(from: fileURL)
        let outputDirectory = outputDirectory ?? fileURL.deletingLastPathComponent()
        let baseName = baseName ?? fileURL.deletingPathExtension().lastPathComponent

        try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

        let summaryRows = makeSummaryRows(worksheet)
        let spectrumRows = makeSpectrumRows(worksheet)

        let summaryURL = outputDirectory.appendingPathComponent("\(baseName)_summary.csv")
        let spectrumURL = outputDirectory.appendingPathComponent("\(baseName)_spectrum.csv")
        let pdfURL = outputDirectory.appendingPathComponent("\(baseName)_spectra.pdf")

        try writeCSV(rows: summaryRows, to: summaryURL)
        try writeCSV(rows: spectrumRows, to: spectrumURL)
        try writeSpectraPDF(for: worksheet, to: pdfURL)

        return ExportResult(summaryURL: summaryURL, spectrumURL: spectrumURL, pdfURL: pdfURL)
    }

    private static func makeSummaryRows(_ worksheet: Worksheet) -> [[String: String]] {
        let formatter = isoFormatter()

        return worksheet.measurements.enumerated().map { index, measurement in
            var row: [String: String] = [
                "measurement_index": String(index),
                "sample_name": measurement.title,
                "measurement_time": formatter.string(from: measurement.time),
                "method_title": measurement.properties.methodTitle,
                "method_description": measurement.properties.methodDescription,
                "x_label": measurement.xLabel,
                "y_label": measurement.yLabel,
                "point_count": String(measurement.xValues.count),
            ]

            for key in measurement.properties.properties.keys.sorted() {
                guard let property = measurement.properties.properties[key] else { continue }
                row[key] = formatDouble(property.value.value)
                if let unit = property.value.unit {
                    row["\(key)_unit"] = unit
                }
                if let factor = property.value.factor {
                    row["\(key)_factor"] = formatDouble(factor)
                }
                if let rawValue = property.rawValue {
                    row["\(key)_raw"] = formatDouble(rawValue.value)
                }
            }

            return row
        }
    }

    private static func makeSpectrumRows(_ worksheet: Worksheet) -> [[String: String]] {
        let formatter = isoFormatter()
        var rows: [[String: String]] = []

        for (index, measurement) in worksheet.measurements.enumerated() {
            for (xValue, yValue) in zip(measurement.xValues, measurement.yValues) {
                rows.append([
                    "measurement_index": String(index),
                    "measurement_time": formatter.string(from: measurement.time),
                    "sample_name": measurement.title,
                    "x_label": measurement.xLabel,
                    "x_value": formatDouble(xValue),
                    "y_label": measurement.yLabel,
                    "y_value": formatDouble(yValue),
                ])
            }
        }

        return rows
    }

    private static func writeCSV(rows: [[String: String]], to url: URL) throws {
        let headers = Array(Set(rows.flatMap(\.keys))).sorted()
        var lines = [headers.joined(separator: ",")]

        for row in rows {
            let line = headers.map { csvEscape(row[$0] ?? "") }.joined(separator: ",")
            lines.append(line)
        }

        let csv = lines.joined(separator: "\n") + "\n"
        try csv.write(to: url, atomically: true, encoding: .utf8)
    }

    private static func writeSpectraPDF(for worksheet: Worksheet, to url: URL) throws {
        var mediaBox = CGRect(x: 0, y: 0, width: 595, height: 842)
        guard let context = CGContext(url as CFURL, mediaBox: &mediaBox, nil) else {
            throw TBWKError.invalidFormat("Could not create PDF context.")
        }

        for (index, measurement) in worksheet.measurements.enumerated() {
            context.beginPDFPage(nil as CFDictionary?)
            let graphicsContext = NSGraphicsContext(cgContext: context, flipped: false)
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = graphicsContext

            drawMeasurement(measurement, index: index, in: mediaBox)

            NSGraphicsContext.restoreGraphicsState()
            context.endPDFPage()
        }

        context.closePDF()
    }

    private static func drawMeasurement(_ measurement: Measurement, index: Int, in pageRect: CGRect) {
        NSColor.white.setFill()
        pageRect.fill()

        let title = "\(index): \(measurement.title)"
        let subtitle = "Method: \(measurement.properties.methodTitle)    Time: \(isoDisplayFormatter().string(from: measurement.time))"

        let titleAttributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.boldSystemFont(ofSize: 18),
            .foregroundColor: NSColor.black,
        ]
        let subtitleAttributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 10),
            .foregroundColor: NSColor.darkGray,
        ]
        NSString(string: title).draw(at: CGPoint(x: 48, y: 790), withAttributes: titleAttributes)
        NSString(string: subtitle).draw(at: CGPoint(x: 48, y: 770), withAttributes: subtitleAttributes)

        let plotRect = CGRect(x: 70, y: 130, width: 470, height: 600)
        NSColor.black.setStroke()
        let border = NSBezierPath(rect: plotRect)
        border.lineWidth = 1
        border.stroke()

        guard
            let minX = measurement.xValues.min(),
            let maxX = measurement.xValues.max(),
            let minY = measurement.yValues.min(),
            let maxY = measurement.yValues.max(),
            maxX > minX
        else {
            return
        }

        let yPadding = max(0.05, (maxY - minY) * 0.08)
        let scaledMinY = minY - yPadding
        let scaledMaxY = maxY + yPadding
        let yRange = max(scaledMaxY - scaledMinY, 0.1)

        drawAxisLabels(measurement: measurement, plotRect: plotRect)
        drawGrid(plotRect: plotRect)

        let path = NSBezierPath()
        path.lineWidth = 1.3
        NSColor.systemBlue.setStroke()

        for (offset, xValue) in measurement.xValues.enumerated() {
            let yValue = measurement.yValues[offset]
            let x = plotRect.minX + CGFloat((xValue - minX) / (maxX - minX)) * plotRect.width
            let y = plotRect.minY + CGFloat((yValue - scaledMinY) / yRange) * plotRect.height
            let point = CGPoint(x: x, y: y)

            if offset == 0 {
                path.move(to: point)
            } else {
                path.line(to: point)
            }
        }

        path.stroke()
        drawTickLabels(minX: minX, maxX: maxX, minY: scaledMinY, maxY: scaledMaxY, plotRect: plotRect)
    }

    private static func drawAxisLabels(measurement: Measurement, plotRect: CGRect) {
        let labelAttributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 11),
            .foregroundColor: NSColor.black,
        ]

        NSString(string: measurement.xLabel).draw(
            in: CGRect(x: plotRect.midX - 80, y: 90, width: 160, height: 20),
            withAttributes: labelAttributes
        )

        NSGraphicsContext.current?.cgContext.saveGState()
        NSGraphicsContext.current?.cgContext.translateBy(x: 30, y: plotRect.midY + 80)
        NSGraphicsContext.current?.cgContext.rotate(by: -.pi / 2)
        NSString(string: measurement.yLabel).draw(
            in: CGRect(x: 0, y: 0, width: 160, height: 20),
            withAttributes: labelAttributes
        )
        NSGraphicsContext.current?.cgContext.restoreGState()
    }

    private static func drawGrid(plotRect: CGRect) {
        NSColor(calibratedWhite: 0.88, alpha: 1).setStroke()
        let grid = NSBezierPath()
        grid.lineWidth = 0.5

        for tick in 1..<5 {
            let x = plotRect.minX + (CGFloat(tick) / 5.0) * plotRect.width
            grid.move(to: CGPoint(x: x, y: plotRect.minY))
            grid.line(to: CGPoint(x: x, y: plotRect.maxY))

            let y = plotRect.minY + (CGFloat(tick) / 5.0) * plotRect.height
            grid.move(to: CGPoint(x: plotRect.minX, y: y))
            grid.line(to: CGPoint(x: plotRect.maxX, y: y))
        }

        grid.stroke()
    }

    private static func drawTickLabels(minX: Double, maxX: Double, minY: Double, maxY: Double, plotRect: CGRect) {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 9),
            .foregroundColor: NSColor.darkGray,
        ]

        for tick in 0...5 {
            let fraction = Double(tick) / 5.0
            let xValue = minX + (maxX - minX) * fraction
            let x = plotRect.minX + CGFloat(fraction) * plotRect.width
            NSString(string: formatTick(xValue)).draw(
                at: CGPoint(x: x - 12, y: plotRect.minY - 18),
                withAttributes: attributes
            )

            let yValue = minY + (maxY - minY) * fraction
            let y = plotRect.minY + CGFloat(fraction) * plotRect.height
            NSString(string: formatTick(yValue)).draw(
                at: CGPoint(x: plotRect.minX - 48, y: y - 5),
                withAttributes: attributes
            )
        }
    }

    private static func csvEscape(_ value: String) -> String {
        if value.contains(",") || value.contains("\"") || value.contains("\n") {
            return "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\""
        }
        return value
    }

    private static func formatDouble(_ value: Double) -> String {
        if value.rounded() == value {
            return String(format: "%.1f", value)
        }
        return String(format: "%.15g", value)
    }

    private static func formatTick(_ value: Double) -> String {
        String(format: "%.2f", value)
    }

    private static func isoFormatter() -> ISO8601DateFormatter {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }

    private static func isoDisplayFormatter() -> DateFormatter {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }
}
