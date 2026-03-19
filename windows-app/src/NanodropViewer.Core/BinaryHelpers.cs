using System;
using System.Buffers.Binary;
using System.Text;

namespace NanodropViewer.Core;

internal static class BinaryHelpers
{
    internal readonly record struct TbwkString(int Length, string Value);

    public static bool StartsWithTbwkMagic(this ReadOnlySpan<byte> data)
    {
        return data.Length >= 4
            && data[0] == 0xfe
            && data[1] == 0xff
            && data[2] == 0xff
            && data[3] == 0xff;
    }

    public static uint ReadUInt32LittleEndian(this ReadOnlySpan<byte> data, int offset)
    {
        if (offset < 0 || offset + 4 > data.Length)
        {
            return 0;
        }

        return BinaryPrimitives.ReadUInt32LittleEndian(data.Slice(offset, 4));
    }

    public static ulong ReadUInt64LittleEndian(this ReadOnlySpan<byte> data, int offset)
    {
        if (offset < 0 || offset + 8 > data.Length)
        {
            return 0;
        }

        return BinaryPrimitives.ReadUInt64LittleEndian(data.Slice(offset, 8));
    }

    public static byte[] SliceSafe(this ReadOnlySpan<byte> data, int start, int end)
    {
        var lower = Math.Max(0, Math.Min(data.Length, start));
        var upper = Math.Max(lower, Math.Min(data.Length, end));
        return data.Slice(lower, upper - lower).ToArray();
    }

    public static TbwkString ReadTbwkString(this ReadOnlySpan<byte> data, int offset)
    {
        if (offset < 0 || offset >= data.Length)
        {
            throw new TbwkException("Unexpected end of TBWK string.");
        }

        var length = data[offset];
        var end = offset + 1 + length;
        if (end > data.Length)
        {
            throw new TbwkException("Unexpected end of TBWK string.");
        }

        var value = Encoding.UTF8.GetString(data.Slice(offset + 1, length));
        return new TbwkString(length, value);
    }

    public static DateTimeOffset WindowsFileTimeToDateTimeOffset(this ReadOnlySpan<byte> data)
    {
        var raw = unchecked((long)data.ReadUInt64LittleEndian(0));
        var unixTimeIn100Ns = raw - 116_444_736_000_000_000L;
        var seconds = unixTimeIn100Ns * 0.0000001;
        return DateTimeOffset.FromUnixTimeMilliseconds((long)Math.Round(seconds * 1000.0));
    }
}
