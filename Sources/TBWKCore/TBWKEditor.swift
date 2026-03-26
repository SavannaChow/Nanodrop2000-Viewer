import Foundation

public struct TBWKEditableDocument {
    private var file: RawTBWKFile

    public init(fileURL: URL) throws {
        try self.init(data: Data(contentsOf: fileURL))
    }

    public init(data: Data) throws {
        file = try RawTBWKFile(data: data)
    }

    public func loadWorksheet() throws -> Worksheet {
        try TBWKParser.parse(data: serializedData())
    }

    public var hasMeasurements: Bool {
        measurementBlockPositions().isEmpty == false
    }

    public mutating func renameMeasurement(at measurementIndex: Int, to newName: String) throws {
        let sanitizedName = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard sanitizedName.isEmpty == false else {
            throw TBWKError.invalidFormat("Sample name cannot be empty.")
        }

        let positions = measurementBlockPositions()
        guard positions.indices.contains(measurementIndex) else {
            throw TBWKError.invalidFormat("Measurement index \(measurementIndex) is out of range.")
        }

        try file.blocks[positions[measurementIndex]].renameMeasurement(to: sanitizedName)
    }

    public mutating func deleteMeasurement(at measurementIndex: Int) throws {
        let positions = measurementBlockPositions()
        guard positions.indices.contains(measurementIndex) else {
            throw TBWKError.invalidFormat("Measurement index \(measurementIndex) is out of range.")
        }

        file.blocks.remove(at: positions[measurementIndex])
    }

    public func serializedData() throws -> Data {
        try file.serializedData()
    }

    public func save(to destinationURL: URL) throws {
        try serializedData().write(to: destinationURL, options: .atomic)
    }

    private func measurementBlockPositions() -> [Int] {
        file.blocks.enumerated().compactMap { index, block in
            block.type == 151 ? index : nil
        }
    }
}

private struct RawTBWKFile {
    var header: Data
    var blocks: [RawTBWKBlock]

    init(data: Data) throws {
        guard data.starts(with: Data([0xfe, 0xff, 0xff, 0xff])) else {
            throw TBWKError.invalidFormat("Input is not a TBWK file.")
        }

        let headerSize = Int(data.littleEndianUInt32Editor(at: 32))
        let headerLength = 40 + headerSize
        header = data.editorSlice(0..<headerLength)

        var offset = headerLength
        var parsedBlocks: [RawTBWKBlock] = []

        while offset < data.count {
            let block = try RawTBWKBlock(data: data, offset: offset)
            parsedBlocks.append(block)
            offset += block.serializedLength
        }

        blocks = parsedBlocks
    }

    func serializedData() throws -> Data {
        var data = header
        for block in blocks {
            data.append(try block.serializedData())
        }
        return data
    }
}

private struct RawTBWKBlock {
    var type: UInt32
    var blockHeader: Data
    var leafContent: Data?
    var nestedPrefix: Data?
    var nestedFile: RawTBWKFile?

    private static let containerTypes: Set<UInt32> = [151, 920, 930]

    init(data: Data, offset: Int) throws {
        type = data.littleEndianUInt32Editor(at: offset)
        let blockSize = Int(data.littleEndianUInt32Editor(at: offset + 4))
        blockHeader = data.editorSlice(offset..<(offset + 12))
        let content = data.editorSlice((offset + 12)..<(offset + 12 + blockSize))

        if Self.containerTypes.contains(type), content.count >= 12 {
            let nestedBytes = content.editorSlice(12..<content.count)
            if nestedBytes.starts(with: Data([0xfe, 0xff, 0xff, 0xff])) {
                nestedPrefix = content.editorSlice(0..<12)
                nestedFile = try RawTBWKFile(data: nestedBytes)
                leafContent = nil
                return
            }
        }

        leafContent = content
        nestedPrefix = nil
        nestedFile = nil
    }

    var serializedLength: Int {
        12 + ((try? contentData().count) ?? 0)
    }

    mutating func renameMeasurement(to newName: String) throws {
        try visitBlocks { block in
            switch block.type {
            case 152:
                block.leafContent = try renameMeasurementInfo(in: block.requireLeafContent(), to: newName)
            case 931:
                block.leafContent = try renameSpectrumMetadata(in: block.requireLeafContent(), to: newName)
            case 62:
                block.leafContent = try renameMeasurementXML(in: block.requireLeafContent(), to: newName)
            default:
                break
            }
        }
    }

    func serializedData() throws -> Data {
        let content = try contentData()
        var header = blockHeader
        header.replaceUInt32LE(at: 0, with: type)
        header.replaceUInt32LE(at: 4, with: UInt32(content.count))
        return header + content
    }

    private func contentData() throws -> Data {
        if let nestedFile {
            return (nestedPrefix ?? Data()) + (try nestedFile.serializedData())
        }
        return leafContent ?? Data()
    }

    private mutating func visitBlocks(_ body: (inout RawTBWKBlock) throws -> Void) throws {
        try body(&self)
        if var nestedFile {
            for index in nestedFile.blocks.indices {
                try nestedFile.blocks[index].visitBlocks(body)
            }
            self.nestedFile = nestedFile
        }
    }

    private func requireLeafContent() throws -> Data {
        guard let leafContent else {
            throw TBWKError.invalidFormat("TBWK block \(type) is not a leaf block.")
        }
        return leafContent
    }
}

private func renameMeasurementInfo(in content: Data, to newName: String) throws -> Data {
    let inner = content.editorSlice(12..<content.count)
    let first = try inner.tbwkStringEditor(at: 0)
    let secondOffset = first.totalLength + 8
    let second = try inner.tbwkStringEditor(at: secondOffset)

    var rebuilt = Data()
    rebuilt.append(content.editorSlice(0..<12))
    rebuilt.append(inner.editorSlice(0..<secondOffset))
    rebuilt.append(tbwkStringData(newName))
    rebuilt.append(inner.editorSlice((secondOffset + second.totalLength)..<inner.count))
    return rebuilt
}

private func renameSpectrumMetadata(in content: Data, to newName: String) throws -> Data {
    var offset = 12
    let first = try content.tbwkStringEditor(at: offset)
    offset += first.totalLength
    let second = try content.tbwkStringEditor(at: offset)
    offset += second.totalLength
    let thirdOffset = offset
    let third = try content.tbwkStringEditor(at: thirdOffset)

    var rebuilt = Data()
    rebuilt.append(content.editorSlice(0..<thirdOffset))
    rebuilt.append(tbwkStringData(newName))
    rebuilt.append(content.editorSlice((thirdOffset + third.totalLength)..<content.count))
    return rebuilt
}

private func renameMeasurementXML(in content: Data, to newName: String) throws -> Data {
    let xmlData = content.editorSlice(12..<content.count)
    let document = try XMLDocument(data: xmlData, options: [])
    guard let root = document.rootElement() else {
        throw TBWKError.invalidFormat("TBWK XML block has no root element.")
    }

    var updated = false
    for node in try root.nodes(forXPath: ".//VAR[@NAME='m_SampleID']") {
        node.stringValue = newName
        updated = true
    }

    guard updated else {
        return content
    }

    var rebuilt = Data()
    rebuilt.append(content.editorSlice(0..<12))
    rebuilt.append(document.xmlData)
    return rebuilt
}

private func tbwkStringData(_ value: String) -> Data {
    let encoded = value.data(using: .utf8, allowLossyConversion: false) ?? Data()
    precondition(encoded.count < 256, "TBWK strings must fit in a single-byte length prefix.")
    return Data([UInt8(encoded.count)]) + encoded
}

private extension Data {
    func editorSlice(_ range: Range<Int>) -> Data {
        let lower = Swift.max(0, Swift.min(count, range.lowerBound))
        let upper = Swift.max(lower, Swift.min(count, range.upperBound))
        return subdata(in: lower..<upper)
    }

    func littleEndianUInt32Editor(at offset: Int) -> UInt32 {
        editorSlice(offset..<(offset + 4)).enumerated().reduce(0) { partial, pair in
            partial | (UInt32(pair.element) << (pair.offset * 8))
        }
    }

    func tbwkStringEditor(at offset: Int) throws -> (totalLength: Int, value: String) {
        guard offset < count else {
            throw TBWKError.invalidFormat("Unexpected end of TBWK string.")
        }
        let length = Int(self[offset])
        let bytes = editorSlice((offset + 1)..<(offset + 1 + length))
        guard let value = String(data: bytes, encoding: .utf8) else {
            throw TBWKError.invalidFormat("TBWK string is not valid UTF-8.")
        }
        return (length + 1, value)
    }

    mutating func replaceUInt32LE(at offset: Int, with value: UInt32) {
        let bytes = Swift.withUnsafeBytes(of: value.littleEndian) { Data($0) }
        replaceSubrange(offset..<(offset + 4), with: bytes)
    }
}
