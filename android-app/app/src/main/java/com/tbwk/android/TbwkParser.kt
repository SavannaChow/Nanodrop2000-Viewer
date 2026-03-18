package com.tbwk.android

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min

data class Worksheet(val measurements: List<Measurement>)

data class Measurement(
    val title: String,
    val xValues: List<Double>,
    val xLabel: String,
    val yValues: List<Double>,
    val yLabel: String,
    val time: Instant,
    val properties: PropertyBag,
)

data class PropertyBag(
    val methodTitle: String = "",
    val methodDescription: String = "",
    val methodFilename: String = "",
    val properties: Map<String, Property> = emptyMap(),
)

data class Property(
    val id: String,
    val type: String,
    val value: TbwkValue,
    val rawValue: TbwkValue? = null,
)

data class TbwkValue(
    val title: String,
    val digits: Int,
    val value: Double,
    val unit: String? = null,
    val factor: Double? = null,
)

class TbwkParseException(message: String) : Exception(message)

object TbwkParser {
    fun parse(inputStream: InputStream): Worksheet = parse(inputStream.readBytes())

    fun parse(bytes: ByteArray): Worksheet {
        val blocks = unpack(bytes)
        val measurements = blocks
            .filter { it.type == 151L }
            .map { parseMeasurement(it) }
        return Worksheet(measurements)
    }

    private data class ParsedBlock(
        val type: Long,
        val offset: Int,
        val xmlElement: Element? = null,
        val children: List<ParsedBlock> = emptyList(),
        val uvData: UVData? = null,
        val spectrumMetadata: SpectrumMetadata? = null,
        val measurementInfo: MeasurementInfo? = null,
        val text: String? = null,
    )

    private data class UVData(
        val longLabel: String,
        val shortLabel: String,
        val values: List<Double>,
    )

    private data class SpectrumMetadata(
        val sampleName: String,
        val sampleTime: Instant,
    )

    private data class MeasurementInfo(
        val sampleName: String,
    )

    private fun unpack(bytes: ByteArray): List<ParsedBlock> {
        if (!bytes.startsWithMagic()) {
            throw TbwkParseException("Input is not a TBWK file.")
        }

        val headerSize = bytes.littleEndianUInt32(32).toInt()
        var offset = 40 + headerSize
        val blocks = mutableListOf<ParsedBlock>()

        while (offset < bytes.size) {
            val type = bytes.littleEndianUInt32(offset)
            val size = bytes.littleEndianUInt32(offset + 4).toInt()
            val content = bytes.sliceSafely(offset + 12, offset + 12 + size)
            blocks += parseBlock(type = type, content = content, offset = offset)
            offset += 12 + size
        }

        return blocks
    }

    private fun parseBlock(type: Long, content: ByteArray, offset: Int): ParsedBlock {
        return when (type) {
            62L, 63L, 990L -> ParsedBlock(type, offset, xmlElement = parseXml(content.sliceSafely(12, content.size)))
            150L, 922L -> ParsedBlock(type, offset)
            151L, 920L, 930L -> ParsedBlock(type, offset, children = unpack(content.sliceSafely(12, content.size)))
            152L -> ParsedBlock(type, offset, measurementInfo = parseMeasurementInfo(content))
            921L -> ParsedBlock(type, offset, text = content.tbwkString(12).value)
            931L -> ParsedBlock(type, offset, spectrumMetadata = parseSpectrumMetadata(content))
            932L -> ParsedBlock(type, offset, uvData = parseUvData(content))
            991L -> {
                val stringInfo = content.tbwkString(12)
                ParsedBlock(
                    type = type,
                    offset = offset,
                    xmlElement = parseXml(content.sliceSafely(13 + stringInfo.length, content.size)),
                    text = stringInfo.value,
                )
            }
            else -> {
                if (type in 0L..9999L) {
                    throw TbwkParseException("Unsupported TBWK block type $type.")
                }
                ParsedBlock(type, offset)
            }
        }
    }

    private fun parseMeasurement(block: ParsedBlock): Measurement {
        val infoBlock = requireChild(152L, block)
        val wrapperBlock = requireChild(920L, block)
        val xmlBlock = requireChild(62L, block)
        val spectrumBlock = requireChild(930L, wrapperBlock)
        val metadataBlock = requireChild(931L, spectrumBlock)
        val uvBlocks = spectrumBlock.children.filter { it.type == 932L }

        val yBlock = uvBlocks.firstOrNull()?.uvData
            ?: throw TbwkParseException("Measurement is missing spectral y-values.")
        val xBlock = uvBlocks.getOrNull(1)?.uvData
            ?: throw TbwkParseException("Measurement is missing spectral x-values.")
        val info = infoBlock.measurementInfo
            ?: throw TbwkParseException("Measurement info is missing.")
        val metadata = metadataBlock.spectrumMetadata
            ?: throw TbwkParseException("Measurement metadata is missing.")
        val xmlRoot = xmlBlock.xmlElement
            ?: throw TbwkParseException("Measurement XML is missing.")

        return Measurement(
            title = info.sampleName,
            xValues = xBlock.values,
            xLabel = xBlock.longLabel,
            yValues = yBlock.values,
            yLabel = yBlock.longLabel,
            time = metadata.sampleTime,
            properties = parsePropertyBag(xmlRoot),
        )
    }

    private fun requireChild(type: Long, block: ParsedBlock): ParsedBlock {
        return block.children.firstOrNull { it.type == type }
            ?: throw TbwkParseException("Required TBWK block $type is missing.")
    }

    private fun parseXml(bytes: ByteArray): Element {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(bytes))
        return document.documentElement ?: throw TbwkParseException("TBWK XML block has no root element.")
    }

    private fun parseMeasurementInfo(bytes: ByteArray): MeasurementInfo {
        val content = bytes.sliceSafely(12, bytes.size)
        val first = content.tbwkString(0)
        val second = content.tbwkString(first.length + 1 + 8)
        return MeasurementInfo(sampleName = second.value)
    }

    private fun parseSpectrumMetadata(bytes: ByteArray): SpectrumMetadata {
        var offset = 12
        val blockType = bytes.tbwkString(offset)
        offset += 1 + blockType.length

        val fileFormat = bytes.tbwkString(offset)
        offset += 1 + fileFormat.length

        val sampleName = bytes.tbwkString(offset)
        offset += 1 + sampleName.length

        val timestamp = windowsFileTimeToInstant(bytes.sliceSafely(offset, offset + 8))
        return SpectrumMetadata(sampleName.value, timestamp)
    }

    private fun parseUvData(bytes: ByteArray): UVData {
        val content = bytes.sliceSafely(59, bytes.size)
        val first = content.tbwkString(0)
        val second = content.tbwkString(first.length + 1)
        var offset = 1 + first.length + 1 + second.length
        offset += 8
        offset += 21

        val count = content.littleEndianUInt32(offset).toInt()
        val valuesStart = offset + 4
        val values = buildList(count) {
            repeat(count) { index ->
                add(Double.fromBits(content.littleEndianUInt64(valuesStart + (index * 8))))
            }
        }

        return UVData(
            longLabel = first.value,
            shortLabel = second.value,
            values = values,
        )
    }

    private fun parsePropertyBag(root: Element): PropertyBag {
        var methodTitle = ""
        var methodDescription = ""
        var methodFilename = ""
        val properties = linkedMapOf<String, Property>()

        val firstParam = root.childElements().firstOrNull()
        val spectrumResults = firstParam?.childElements()?.firstOrNull()

        for (variable in spectrumResults.childElementsNamed("VAR")) {
            val name = variable.getAttribute("NAME")
            val text = variable.textContent.orEmpty().trim()

            when (name) {
                "m_MethodFilename" -> methodFilename = text
                "m_MethodTitle" -> methodTitle = text
                "m_MethodDescription" -> methodDescription = text
                "m_QuantGroups" -> {
                    for (propertyNode in variable.childElementsNamed("PARAM")) {
                        parseProperty(propertyNode)?.let { properties[it.id] = it }
                    }
                }
            }
        }

        return PropertyBag(methodTitle, methodDescription, methodFilename, properties)
    }

    private fun parseProperty(propertyNode: Element): Property? {
        var propertyTitle = ""
        var propertyType = ""
        var value: TbwkValue? = null
        var rawValue: TbwkValue? = null

        for (variable in propertyNode.childElementsNamed("VAR")) {
            when (variable.getAttribute("NAME")) {
                "m_Title" -> propertyTitle = variable.textContent.orEmpty().trim()
                "m_ResultType" -> propertyType = variable.textContent.orEmpty().trim()
                "m_QuantElements" -> {
                    val parsed = parseValues(variable, propertyType)
                    value = parsed.first
                    rawValue = parsed.second
                }
            }
        }

        val resolvedValue = value ?: return null
        return Property(
            id = propertyTitle,
            type = propertyType,
            value = resolvedValue,
            rawValue = rawValue,
        )
    }

    private fun parseValues(quantElements: Element, type: String): Pair<TbwkValue?, TbwkValue?> {
        val params = linkedMapOf<String, Map<String, String>>()

        for (param in quantElements.childElementsNamed("PARAM")) {
            val element = linkedMapOf<String, String>()
            for (variable in param.childElementsNamed("VAR")) {
                element[variable.getAttribute("NAME")] = variable.textContent.orEmpty().trim()
            }
            val resultType = element["m_ResultType"]
            if (resultType != null) {
                params[resultType] = element
            }
        }

        val main = params[type]
        val value = if (main != null) {
            val mainValue = main["m_Value"]?.toDoubleOrNull()
            val digits = main["m_NumDigits"]?.toIntOrNull()
            val title = main["m_Title"]
            if (mainValue != null && digits != null && title != null) {
                TbwkValue(
                    title = title,
                    digits = digits,
                    value = mainValue,
                    unit = params["${type}Unit"]?.get("m_Value"),
                    factor = params["${type}Factor"]?.get("m_Value")?.toDoubleOrNull(),
                )
            } else {
                null
            }
        } else {
            null
        }

        val raw = params["${type}Raw"]
        val rawValue = if (raw != null) {
            val rawNumber = raw["m_Value"]?.toDoubleOrNull()
            val rawDigits = raw["m_NumDigits"]?.toIntOrNull()
            val rawTitle = raw["m_Title"]
            if (rawNumber != null && rawDigits != null && rawTitle != null) {
                TbwkValue(
                    title = "${rawTitle}Raw",
                    digits = rawDigits,
                    value = rawNumber,
                )
            } else {
                null
            }
        } else {
            null
        }

        return value to rawValue
    }

    private fun windowsFileTimeToInstant(bytes: ByteArray): Instant {
        val fileTime = bytes.littleEndianUInt64(0).toLong()
        val unixTimeIn100Ns = fileTime - 116_444_736_000_000_000L
        val millis = unixTimeIn100Ns / 10_000L
        return Instant.ofEpochMilli(millis)
    }
}

private fun ByteArray.startsWithMagic(): Boolean {
    if (size < 4) return false
    return this[0] == 0xFE.toByte() &&
        this[1] == 0xFF.toByte() &&
        this[2] == 0xFF.toByte() &&
        this[3] == 0xFF.toByte()
}

private fun ByteArray.sliceSafely(start: Int, endExclusive: Int): ByteArray {
    val lower = max(0, min(size, start))
    val upper = max(lower, min(size, endExclusive))
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

private fun ByteArray.littleEndianUInt64(offset: Int): Long {
    var result = 0L
    repeat(8) { index ->
        val actual = offset + index
        if (actual < size) {
            result = result or ((this[actual].toLong() and 0xFF) shl (index * 8))
        }
    }
    return result
}

private fun ByteArray.tbwkString(offset: Int): TbwkString {
    if (offset >= size) throw TbwkParseException("Unexpected end of TBWK string.")
    val length = this[offset].toInt() and 0xFF
    val slice = sliceSafely(offset + 1, offset + 1 + length)
    val value = slice.toString(StandardCharsets.UTF_8)
    return TbwkString(length, value)
}

private data class TbwkString(val length: Int, val value: String)

private fun Node?.childElements(): List<Element> {
    if (this == null || !hasChildNodes()) return emptyList()
    val result = mutableListOf<Element>()
    val list = childNodes
    for (index in 0 until list.length) {
        val node = list.item(index)
        if (node is Element) result += node
    }
    return result
}

private fun Node?.childElementsNamed(name: String): List<Element> =
    childElements().filter { it.tagName == name }
