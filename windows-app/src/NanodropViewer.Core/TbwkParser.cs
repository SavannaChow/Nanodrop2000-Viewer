using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Xml.Linq;

namespace NanodropViewer.Core;

public static class TbwkParser
{
    public static Worksheet Parse(string filePath)
    {
        return Parse(File.ReadAllBytes(filePath));
    }

    public static Worksheet Parse(Stream stream)
    {
        using var memory = new MemoryStream();
        stream.CopyTo(memory);
        return Parse(memory.ToArray());
    }

    public static Worksheet Parse(byte[] data)
    {
        var blocks = Unpack(data);
        var measurements = blocks
            .Where(block => block.Type == 151L)
            .Select(ParseMeasurement)
            .ToArray();
        return new Worksheet(measurements);
    }

    private sealed record ParsedBlock(
        long Type,
        int Offset,
        XElement? XmlElement,
        IReadOnlyList<ParsedBlock> Children,
        UvData? UvData,
        SpectrumMetadata? SpectrumMetadata,
        MeasurementInfo? MeasurementInfo,
        string? Text
    );

    private sealed record UvData(string LongLabel, string ShortLabel, IReadOnlyList<double> Values);
    private sealed record SpectrumMetadata(string SampleName, DateTimeOffset SampleTime);
    private sealed record MeasurementInfo(string SampleName);

    private static IReadOnlyList<ParsedBlock> Unpack(byte[] data)
    {
        ReadOnlySpan<byte> span = data;
        if (!span.StartsWithTbwkMagic())
        {
            throw new TbwkException("Input is not a TBWK file.");
        }

        var headerSize = checked((int)span.ReadUInt32LittleEndian(32));
        var offset = 40 + headerSize;
        var blocks = new List<ParsedBlock>();

        while (offset < span.Length)
        {
            var blockType = (long)span.ReadUInt32LittleEndian(offset);
            var blockSize = checked((int)span.ReadUInt32LittleEndian(offset + 4));
            var content = span.SliceSafe(offset + 12, offset + 12 + blockSize);
            blocks.Add(ParseBlock(blockType, content, offset));
            offset += 12 + blockSize;
        }

        return blocks;
    }

    private static ParsedBlock ParseBlock(long type, byte[] content, int offset)
    {
        ReadOnlySpan<byte> span = content;
        return type switch
        {
            62 or 63 or 990 => new ParsedBlock(
                type,
                offset,
                ParseXml(span.SliceSafe(12, span.Length)),
                Array.Empty<ParsedBlock>(),
                null,
                null,
                null,
                null
            ),
            150 or 922 => new ParsedBlock(type, offset, null, Array.Empty<ParsedBlock>(), null, null, null, null),
            151 or 920 or 930 => new ParsedBlock(
                type,
                offset,
                null,
                Unpack(span.SliceSafe(12, span.Length)),
                null,
                null,
                null,
                null
            ),
            152 => new ParsedBlock(type, offset, null, Array.Empty<ParsedBlock>(), null, null, ParseMeasurementInfo(span), null),
            921 => new ParsedBlock(type, offset, null, Array.Empty<ParsedBlock>(), null, null, null, span.ReadTbwkString(12).Value),
            931 => new ParsedBlock(type, offset, null, Array.Empty<ParsedBlock>(), null, ParseSpectrumMetadata(span), null, null),
            932 => new ParsedBlock(type, offset, null, Array.Empty<ParsedBlock>(), ParseUvData(span), null, null, null),
            991 => Parse991Block(type, offset, span),
            _ when type < 10000 => throw new TbwkException($"Unsupported TBWK block type {type}."),
            _ => new ParsedBlock(type, offset, null, Array.Empty<ParsedBlock>(), null, null, null, null),
        };
    }

    private static ParsedBlock Parse991Block(long type, int offset, ReadOnlySpan<byte> span)
    {
        var info = span.ReadTbwkString(12);
        return new ParsedBlock(
            type,
            offset,
            ParseXml(span.SliceSafe(13 + info.Length, span.Length)),
            Array.Empty<ParsedBlock>(),
            null,
            null,
            null,
            info.Value
        );
    }

    private static XElement ParseXml(byte[] bytes)
    {
        try
        {
            return XDocument.Parse(System.Text.Encoding.UTF8.GetString(bytes)).Root
                ?? throw new TbwkException("TBWK XML block has no root element.");
        }
        catch (Exception ex) when (ex is not TbwkException)
        {
            throw new TbwkException($"TBWK XML block could not be parsed: {ex.Message}");
        }
    }

    private static MeasurementInfo ParseMeasurementInfo(ReadOnlySpan<byte> span)
    {
        ReadOnlySpan<byte> content = span.SliceSafe(12, span.Length);
        var first = content.ReadTbwkString(0);
        var second = content.ReadTbwkString(first.Length + 1 + 8);
        return new MeasurementInfo(second.Value);
    }

    private static SpectrumMetadata ParseSpectrumMetadata(ReadOnlySpan<byte> span)
    {
        var offset = 12;
        var blockType = span.ReadTbwkString(offset);
        offset += 1 + blockType.Length;

        var fileFormat = span.ReadTbwkString(offset);
        offset += 1 + fileFormat.Length;

        var sampleName = span.ReadTbwkString(offset);
        offset += 1 + sampleName.Length;

        ReadOnlySpan<byte> timeData = span.SliceSafe(offset, offset + 8);
        var time = timeData.WindowsFileTimeToDateTimeOffset();
        return new SpectrumMetadata(sampleName.Value, time);
    }

    private static UvData ParseUvData(ReadOnlySpan<byte> span)
    {
        ReadOnlySpan<byte> content = span.SliceSafe(59, span.Length);
        var first = content.ReadTbwkString(0);
        var second = content.ReadTbwkString(first.Length + 1);
        var offset = 1 + first.Length + 1 + second.Length;
        offset += 8;
        offset += 21;

        var count = checked((int)content.ReadUInt32LittleEndian(offset));
        var valuesStart = offset + 4;
        var values = new double[count];
        for (var index = 0; index < count; index++)
        {
            values[index] = BitConverter.Int64BitsToDouble(unchecked((long)content.ReadUInt64LittleEndian(valuesStart + (index * 8))));
        }

        return new UvData(first.Value, second.Value, values);
    }

    private static Measurement ParseMeasurement(ParsedBlock block)
    {
        var infoBlock = RequireChild(152L, block);
        var wrapperBlock = RequireChild(920L, block);
        var xmlBlock = RequireChild(62L, block);
        var spectrumBlock = RequireChild(930L, wrapperBlock);
        var metadataBlock = RequireChild(931L, spectrumBlock);
        var uvBlocks = spectrumBlock.Children.Where(child => child.Type == 932L).ToArray();

        var yBlock = uvBlocks.FirstOrDefault()?.UvData
            ?? throw new TbwkException("Measurement is missing spectral y-values.");
        var xBlock = uvBlocks.Skip(1).FirstOrDefault()?.UvData
            ?? throw new TbwkException("Measurement is missing spectral x-values.");
        var info = infoBlock.MeasurementInfo
            ?? throw new TbwkException("Measurement info is missing.");
        var metadata = metadataBlock.SpectrumMetadata
            ?? throw new TbwkException("Measurement metadata is missing.");
        var xmlRoot = xmlBlock.XmlElement
            ?? throw new TbwkException("Measurement XML is missing.");

        return new Measurement(
            info.SampleName,
            xBlock.Values,
            xBlock.LongLabel,
            yBlock.Values,
            yBlock.LongLabel,
            metadata.SampleTime,
            ParsePropertyBag(xmlRoot)
        );
    }

    private static ParsedBlock RequireChild(long type, ParsedBlock block)
    {
        return block.Children.FirstOrDefault(child => child.Type == type)
            ?? throw new TbwkException($"Required TBWK block {type} is missing.");
    }

    private static PropertyBag ParsePropertyBag(XElement root)
    {
        var methodTitle = string.Empty;
        var methodDescription = string.Empty;
        var methodFilename = string.Empty;
        var properties = new Dictionary<string, MeasurementProperty>(StringComparer.Ordinal);

        var firstParam = root.Elements().FirstOrDefault();
        var spectrumResults = firstParam?.Elements().FirstOrDefault();
        if (spectrumResults is null)
        {
            return PropertyBag.Empty;
        }

        foreach (var variable in ChildElementsNamed(spectrumResults, "VAR"))
        {
            var name = variable.Attribute("NAME")?.Value ?? string.Empty;
            var text = (variable.Value ?? string.Empty).Trim();
            switch (name)
            {
                case "m_MethodFilename":
                    methodFilename = text;
                    break;
                case "m_MethodTitle":
                    methodTitle = text;
                    break;
                case "m_MethodDescription":
                    methodDescription = text;
                    break;
                case "m_QuantGroups":
                    foreach (var propertyNode in ChildElementsNamed(variable, "PARAM"))
                    {
                        var property = ParseProperty(propertyNode);
                        if (property is not null)
                        {
                            properties[property.Id] = property;
                        }
                    }
                    break;
            }
        }

        return new PropertyBag(methodTitle, methodDescription, methodFilename, properties);
    }

    private static MeasurementProperty? ParseProperty(XElement propertyNode)
    {
        var propertyTitle = string.Empty;
        var propertyType = string.Empty;
        QuantValue? value = null;
        QuantValue? rawValue = null;

        foreach (var variable in ChildElementsNamed(propertyNode, "VAR"))
        {
            var name = variable.Attribute("NAME")?.Value ?? string.Empty;
            var text = (variable.Value ?? string.Empty).Trim();
            switch (name)
            {
                case "m_Title":
                    propertyTitle = text;
                    break;
                case "m_ResultType":
                    propertyType = text;
                    break;
                case "m_QuantElements":
                    var parsed = ParseValues(variable, propertyType);
                    value = parsed.value;
                    rawValue = parsed.rawValue;
                    break;
            }
        }

        return value is null
            ? null
            : new MeasurementProperty(propertyTitle, propertyType, value, rawValue);
    }

    private static (QuantValue? value, QuantValue? rawValue) ParseValues(XElement quantElements, string type)
    {
        var parameters = new Dictionary<string, Dictionary<string, string>>(StringComparer.Ordinal);

        foreach (var param in ChildElementsNamed(quantElements, "PARAM"))
        {
            var entry = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var variable in ChildElementsNamed(param, "VAR"))
            {
                entry[variable.Attribute("NAME")?.Value ?? string.Empty] = (variable.Value ?? string.Empty).Trim();
            }

            if (entry.TryGetValue("m_ResultType", out var resultType))
            {
                parameters[resultType] = entry;
            }
        }

        QuantValue? value = null;
        if (parameters.TryGetValue(type, out var main)
            && main.TryGetValue("m_Title", out var title)
            && main.TryGetValue("m_Value", out var stringValue)
            && main.TryGetValue("m_NumDigits", out var stringDigits)
            && double.TryParse(stringValue, NumberStyles.Float, CultureInfo.InvariantCulture, out var numericValue)
            && int.TryParse(stringDigits, NumberStyles.Integer, CultureInfo.InvariantCulture, out var digits))
        {
            value = new QuantValue(
                title,
                digits,
                numericValue,
                parameters.TryGetValue($"{type}Unit", out var unitEntry) && unitEntry.TryGetValue("m_Value", out var unit) ? unit : null,
                parameters.TryGetValue($"{type}Factor", out var factorEntry)
                    && factorEntry.TryGetValue("m_Value", out var factorText)
                    && double.TryParse(factorText, NumberStyles.Float, CultureInfo.InvariantCulture, out var factor)
                        ? factor
                        : null
            );
        }

        QuantValue? rawValue = null;
        if (parameters.TryGetValue($"{type}Raw", out var raw)
            && raw.TryGetValue("m_Title", out var rawTitle)
            && raw.TryGetValue("m_Value", out var rawStringValue)
            && raw.TryGetValue("m_NumDigits", out var rawDigitsText)
            && double.TryParse(rawStringValue, NumberStyles.Float, CultureInfo.InvariantCulture, out var rawNumericValue)
            && int.TryParse(rawDigitsText, NumberStyles.Integer, CultureInfo.InvariantCulture, out var rawDigits))
        {
            rawValue = new QuantValue($"{rawTitle}Raw", rawDigits, rawNumericValue, null, null);
        }

        return (value, rawValue);
    }

    private static IEnumerable<XElement> ChildElementsNamed(XElement element, string name)
    {
        return element.Elements().Where(child => string.Equals(child.Name.LocalName, name, StringComparison.Ordinal));
    }
}
