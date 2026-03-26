using System;
using System.Collections.Generic;
using System.Buffers.Binary;
using System.IO;
using System.Linq;
using System.Text;
using System.Xml.Linq;

namespace NanodropViewer.Core;

public sealed class TbwkEditableDocument
{
    private RawTbwkFile _file;

    private TbwkEditableDocument(RawTbwkFile file)
    {
        _file = file;
    }

    public static TbwkEditableDocument Parse(byte[] bytes)
    {
        return new TbwkEditableDocument(new RawTbwkFile(bytes));
    }

    public Worksheet Worksheet()
    {
        return TbwkParser.Parse(SerializedBytes());
    }

    public void RenameMeasurement(int index, string newName)
    {
        var sanitized = (newName ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(sanitized))
        {
            throw new TbwkException("Sample name cannot be empty.");
        }

        var positions = MeasurementBlockPositions();
        if (index < 0 || index >= positions.Count)
        {
            throw new TbwkException($"Measurement index {index} is out of range.");
        }

        _file.Blocks[positions[index]].RenameMeasurement(sanitized);
    }

    public void DeleteMeasurement(int index)
    {
        var positions = MeasurementBlockPositions();
        if (index < 0 || index >= positions.Count)
        {
            throw new TbwkException($"Measurement index {index} is out of range.");
        }

        _file.Blocks.RemoveAt(positions[index]);
    }

    public byte[] SerializedBytes()
    {
        return _file.SerializedBytes();
    }

    public void Save(string path)
    {
        File.WriteAllBytes(path, SerializedBytes());
    }

    private List<int> MeasurementBlockPositions()
    {
        return _file.Blocks
            .Select((block, position) => new { block.Type, Position = position })
            .Where(item => item.Type == 151L)
            .Select(item => item.Position)
            .ToList();
    }

    private sealed class RawTbwkFile
    {
        public RawTbwkFile(byte[] bytes)
        {
            ReadOnlySpan<byte> span = bytes;
            if (!span.StartsWithTbwkMagic())
            {
                throw new TbwkException("Input is not a TBWK file.");
            }

            var headerSize = checked((int)span.ReadUInt32LittleEndian(32));
            var headerLength = 40 + headerSize;
            Header = bytes.AsSpan(0, headerLength).ToArray();

            Blocks = new List<RawTbwkBlock>();
            var offset = headerLength;
            while (offset < bytes.Length)
            {
                var block = new RawTbwkBlock(bytes, offset);
                Blocks.Add(block);
                offset += block.SerializedLength();
            }
        }

        public byte[] Header { get; }
        public List<RawTbwkBlock> Blocks { get; }

        public byte[] SerializedBytes()
        {
            var chunks = new List<byte[]> { Header };
            var totalSize = Header.Length;

            foreach (var block in Blocks)
            {
                var bytes = block.SerializedBytes();
                chunks.Add(bytes);
                totalSize += bytes.Length;
            }

            var result = new byte[totalSize];
            var offset = 0;
            foreach (var chunk in chunks)
            {
                Buffer.BlockCopy(chunk, 0, result, offset, chunk.Length);
                offset += chunk.Length;
            }

            return result;
        }
    }

    private sealed class RawTbwkBlock
    {
        private readonly byte[] _blockHeader;
        private byte[]? _leafContent;
        private byte[]? _nestedPrefix;
        private RawTbwkFile? _nestedFile;

        public RawTbwkBlock(byte[] bytes, int offset)
        {
            ReadOnlySpan<byte> span = bytes;
            Type = span.ReadUInt32LittleEndian(offset);
            _blockHeader = bytes.AsSpan(offset, 12).ToArray();

            var size = checked((int)span.ReadUInt32LittleEndian(offset + 4));
            var content = bytes.AsSpan(offset + 12, size).ToArray();
            if ((Type == 151L || Type == 920L || Type == 930L) && content.Length >= 12)
            {
                var nestedBytes = content.AsSpan(12).ToArray();
                if (((ReadOnlySpan<byte>)nestedBytes).StartsWithTbwkMagic())
                {
                    _nestedPrefix = content.AsSpan(0, 12).ToArray();
                    _nestedFile = new RawTbwkFile(nestedBytes);
                }
                else
                {
                    _leafContent = content;
                }
            }
            else
            {
                _leafContent = content;
            }
        }

        public long Type { get; private set; }

        public void RenameMeasurement(string newName)
        {
            VisitBlocks(block =>
            {
                switch (block.Type)
                {
                    case 152L:
                        block._leafContent = RenameMeasurementInfo(block.RequireLeafContent(), newName);
                        break;
                    case 931L:
                        block._leafContent = RenameSpectrumMetadata(block.RequireLeafContent(), newName);
                        break;
                    case 62L:
                        block._leafContent = RenameMeasurementXml(block.RequireLeafContent(), newName);
                        break;
                }
            });
        }

        public int SerializedLength()
        {
            return 12 + SerializedContent().Length;
        }

        public byte[] SerializedBytes()
        {
            var content = SerializedContent();
            var header = _blockHeader.ToArray();
            BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(0, 4), (uint)Type);
            BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(4, 4), (uint)content.Length);
            return header.Concat(content).ToArray();
        }

        private byte[] SerializedContent()
        {
            if (_nestedFile is not null)
            {
                return (_nestedPrefix ?? Array.Empty<byte>()).Concat(_nestedFile.SerializedBytes()).ToArray();
            }

            return _leafContent ?? Array.Empty<byte>();
        }

        private byte[] RequireLeafContent()
        {
            return _leafContent ?? throw new TbwkException($"TBWK block {Type} is not a leaf block.");
        }

        private void VisitBlocks(Action<RawTbwkBlock> visitor)
        {
            visitor(this);
            if (_nestedFile is null)
            {
                return;
            }

            foreach (var block in _nestedFile.Blocks)
            {
                block.VisitBlocks(visitor);
            }
        }
    }

    private static byte[] RenameMeasurementInfo(byte[] content, string newName)
    {
        var inner = content.AsSpan(12).ToArray();
        var first = inner.AsSpan().ReadTbwkString(0);
        var secondOffset = first.Length + 1 + 8;
        var second = inner.AsSpan().ReadTbwkString(secondOffset);

        return content.AsSpan(0, 12).ToArray()
            .Concat(inner.AsSpan(0, secondOffset).ToArray())
            .Concat(TbwkString(newName))
            .Concat(inner.AsSpan(secondOffset + 1 + second.Length).ToArray())
            .ToArray();
    }

    private static byte[] RenameSpectrumMetadata(byte[] content, string newName)
    {
        var offset = 12;
        var first = content.AsSpan().ReadTbwkString(offset);
        offset += 1 + first.Length;
        var second = content.AsSpan().ReadTbwkString(offset);
        offset += 1 + second.Length;
        var thirdOffset = offset;
        var third = content.AsSpan().ReadTbwkString(thirdOffset);

        return content.AsSpan(0, thirdOffset).ToArray()
            .Concat(TbwkString(newName))
            .Concat(content.AsSpan(thirdOffset + 1 + third.Length).ToArray())
            .ToArray();
    }

    private static byte[] RenameMeasurementXml(byte[] content, string newName)
    {
        var xmlBytes = content.AsSpan(12).ToArray();
        var root = XDocument.Parse(Encoding.UTF8.GetString(xmlBytes)).Root;
        if (root is null)
        {
            return content;
        }

        var updated = false;
        foreach (var element in root.Descendants("VAR"))
        {
            if (string.Equals(element.Attribute("NAME")?.Value, "m_SampleID", StringComparison.Ordinal))
            {
                element.Value = newName;
                updated = true;
            }
        }

        if (!updated)
        {
            return content;
        }

        var rebuiltXml = Encoding.UTF8.GetBytes(root.Document?.ToString(SaveOptions.DisableFormatting) ?? root.ToString(SaveOptions.DisableFormatting));
        return content.AsSpan(0, 12).ToArray().Concat(rebuiltXml).ToArray();
    }

    private static byte[] TbwkString(string value)
    {
        var encoded = Encoding.UTF8.GetBytes(value);
        if (encoded.Length >= 256)
        {
            throw new TbwkException("TBWK strings must fit in a single-byte length prefix.");
        }

        return new[] { (byte)encoded.Length }.Concat(encoded).ToArray();
    }
}
