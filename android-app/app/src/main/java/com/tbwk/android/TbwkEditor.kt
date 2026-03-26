package com.tbwk.android

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class TbwkEditableDocument private constructor(
    private var file: RawTbwkFile,
) {
    companion object {
        fun parse(bytes: ByteArray): TbwkEditableDocument {
            return TbwkEditableDocument(RawTbwkFile(bytes))
        }
    }

    fun worksheet(): Worksheet = TbwkParser.parse(serializedBytes())

    fun renameMeasurement(index: Int, newName: String) {
        val sanitizedName = newName.trim()
        require(sanitizedName.isNotEmpty()) { "Sample name cannot be empty." }

        val measurementPositions = measurementBlockPositions()
        require(index in measurementPositions.indices) { "Measurement index $index is out of range." }
        file.blocks[measurementPositions[index]].renameMeasurement(sanitizedName)
    }

    fun deleteMeasurement(index: Int) {
        val measurementPositions = measurementBlockPositions()
        require(index in measurementPositions.indices) { "Measurement index $index is out of range." }
        file.blocks.removeAt(measurementPositions[index])
    }

    fun serializedBytes(): ByteArray = file.serializedBytes()

    private fun measurementBlockPositions(): List<Int> {
        return file.blocks.mapIndexedNotNull { position, block ->
            if (block.type == 151L) position else null
        }
    }
}

private class RawTbwkFile(bytes: ByteArray) {
    val header: ByteArray
    val blocks: MutableList<RawTbwkBlock>

    init {
        if (!bytes.startsWithMagicEditor()) {
            throw TbwkParseException("Input is not a TBWK file.")
        }

        val headerSize = bytes.littleEndianUInt32(32).toInt()
        val headerLength = 40 + headerSize
        header = bytes.sliceSafely(0, headerLength)

        val parsedBlocks = mutableListOf<RawTbwkBlock>()
        var offset = headerLength
        while (offset < bytes.size) {
            val block = RawTbwkBlock(bytes, offset)
            parsedBlocks += block
            offset += block.serializedLength()
        }
        blocks = parsedBlocks
    }

    fun serializedBytes(): ByteArray {
        val output = ByteArrayBuilder()
        output.append(header)
        blocks.forEach { output.append(it.serializedBytes()) }
        return output.toByteArray()
    }
}

private class RawTbwkBlock(bytes: ByteArray, offset: Int) {
    var type: Long = bytes.littleEndianUInt32(offset)
    private var blockHeader: ByteArray = bytes.sliceSafely(offset, offset + 12)
    private var leafContent: ByteArray? = null
    private var nestedPrefix: ByteArray? = null
    private var nestedFile: RawTbwkFile? = null

    init {
        val size = bytes.littleEndianUInt32(offset + 4).toInt()
        val content = bytes.sliceSafely(offset + 12, offset + 12 + size)
        if (type in setOf(151L, 920L, 930L) && content.size >= 12) {
            val nestedBytes = content.sliceSafely(12, content.size)
            if (nestedBytes.startsWithMagicEditor()) {
                nestedPrefix = content.sliceSafely(0, 12)
                nestedFile = RawTbwkFile(nestedBytes)
            } else {
                leafContent = content
            }
        } else {
            leafContent = content
        }
    }

    fun renameMeasurement(newName: String) {
        visitBlocks { block ->
            when (block.type) {
                152L -> block.leafContent = renameMeasurementInfo(block.requireLeafContent(), newName)
                931L -> block.leafContent = renameSpectrumMetadata(block.requireLeafContent(), newName)
                62L -> block.leafContent = renameMeasurementXml(block.requireLeafContent(), newName)
            }
        }
    }

    fun serializedLength(): Int = 12 + serializedContent().size

    fun serializedBytes(): ByteArray {
        val content = serializedContent()
        val header = blockHeader.copyOf()
        header.writeUInt32LE(0, type)
        header.writeUInt32LE(4, content.size.toLong())
        return header + content
    }

    private fun serializedContent(): ByteArray {
        val nested = nestedFile
        return if (nested != null) {
            (nestedPrefix ?: byteArrayOf()) + nested.serializedBytes()
        } else {
            leafContent ?: byteArrayOf()
        }
    }

    private fun requireLeafContent(): ByteArray {
        return leafContent ?: throw TbwkParseException("TBWK block $type is not a leaf block.")
    }

    private fun visitBlocks(visitor: (RawTbwkBlock) -> Unit) {
        visitor(this)
        nestedFile?.blocks?.forEach { it.visitBlocks(visitor) }
    }
}

private fun renameMeasurementInfo(content: ByteArray, newName: String): ByteArray {
    val inner = content.sliceSafely(12, content.size)
    val first = inner.tbwkString(0)
    val secondOffset = first.length + 1 + 8
    val second = inner.tbwkString(secondOffset)
    val builder = ByteArrayBuilder()
    builder.append(content.sliceSafely(0, 12))
    builder.append(inner.sliceSafely(0, secondOffset))
    builder.append(tbwkString(newName))
    builder.append(inner.sliceSafely(secondOffset + 1 + second.length, inner.size))
    return builder.toByteArray()
}

private fun renameSpectrumMetadata(content: ByteArray, newName: String): ByteArray {
    var offset = 12
    val first = content.tbwkString(offset)
    offset += 1 + first.length
    val second = content.tbwkString(offset)
    offset += 1 + second.length
    val thirdOffset = offset
    val third = content.tbwkString(thirdOffset)

    val builder = ByteArrayBuilder()
    builder.append(content.sliceSafely(0, thirdOffset))
    builder.append(tbwkString(newName))
    builder.append(content.sliceSafely(thirdOffset + 1 + third.length, content.size))
    return builder.toByteArray()
}

private fun renameMeasurementXml(content: ByteArray, newName: String): ByteArray {
    val xmlBytes = content.sliceSafely(12, content.size)
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val document = builder.parse(ByteArrayInputStream(xmlBytes))
    val sampleNodes = document.getElementsByTagName("VAR")

    var updated = false
    for (index in 0 until sampleNodes.length) {
        val node = sampleNodes.item(index)
        val element = node as? Element ?: continue
        if (element.getAttribute("NAME") == "m_SampleID") {
            element.textContent = newName
            updated = true
        }
    }

    if (!updated) {
        return content
    }

    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        setOutputProperty(OutputKeys.ENCODING, "utf-8")
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(document), StreamResult(writer))
    val rebuiltXml = writer.toString().toByteArray(Charsets.UTF_8)
    return content.sliceSafely(0, 12) + rebuiltXml
}

private fun tbwkString(value: String): ByteArray {
    val encoded = value.toByteArray(Charsets.UTF_8)
    require(encoded.size < 256) { "TBWK strings must fit in a single-byte length prefix." }
    return byteArrayOf(encoded.size.toByte()) + encoded
}

private class ByteArrayBuilder {
    private val chunks = ArrayList<ByteArray>()
    private var size = 0

    fun append(bytes: ByteArray) {
        chunks += bytes
        size += bytes.size
    }

    fun toByteArray(): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    }
}

private fun ByteArray.writeUInt32LE(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun ByteArray.startsWithMagicEditor(): Boolean {
    if (size < 4) return false
    return this[0] == 0xFE.toByte() &&
        this[1] == 0xFF.toByte() &&
        this[2] == 0xFF.toByte() &&
        this[3] == 0xFF.toByte()
}

private fun ByteArray.sliceSafely(start: Int, endExclusive: Int): ByteArray {
    val lower = kotlin.math.max(0, kotlin.math.min(size, start))
    val upper = kotlin.math.max(lower, kotlin.math.min(size, endExclusive))
    return copyOfRange(lower, upper)
}

private fun ByteArray.littleEndianUInt32(offset: Int): Long {
    var result = 0L
    repeat(4) { index ->
        val actual = offset + index
        if (actual < size) {
            result = result or ((this[actual].toLong() and 0xFF) shl (index * 8))
        }
    }
    return result
}

private data class EditorTbwkString(val length: Int, val value: String)

private fun ByteArray.tbwkString(offset: Int): EditorTbwkString {
    if (offset >= size) throw TbwkParseException("Unexpected end of TBWK string.")
    val length = this[offset].toInt() and 0xFF
    val slice = sliceSafely(offset + 1, offset + 1 + length)
    val value = slice.toString(Charsets.UTF_8)
    return EditorTbwkString(length, value)
}
