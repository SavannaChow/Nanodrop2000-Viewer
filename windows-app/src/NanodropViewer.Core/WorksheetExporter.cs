using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace NanodropViewer.Core;

public sealed record ExportResult(string SummaryCsvPath, string SpectrumCsvPath, string PdfPath, string OutputDirectory);

public static class WorksheetExporter
{
    public static ExportResult Export(Worksheet worksheet, string sourceFilePath, string? outputDirectory = null, string? baseName = null)
    {
        var exportDirectory = outputDirectory ?? BuildDefaultExportDirectory(sourceFilePath);
        Directory.CreateDirectory(exportDirectory);

        var normalizedBaseName = baseName ?? Path.GetFileNameWithoutExtension(sourceFilePath);
        var summaryPath = Path.Combine(exportDirectory, $"{normalizedBaseName}_summary.csv");
        var spectrumPath = Path.Combine(exportDirectory, $"{normalizedBaseName}_spectrum.csv");
        var pdfPath = Path.Combine(exportDirectory, $"{normalizedBaseName}_spectra.pdf");

        WriteCsv(BuildSummaryRows(worksheet), summaryPath);
        WriteCsv(BuildSpectrumRows(worksheet), spectrumPath);
        WriteSpectraPdf(worksheet, pdfPath);

        return new ExportResult(summaryPath, spectrumPath, pdfPath, exportDirectory);
    }

    public static ExportResult ExportCsv(Worksheet worksheet, string sourceFilePath, string? outputDirectory = null, string? baseName = null)
    {
        return Export(worksheet, sourceFilePath, outputDirectory, baseName);
    }

    private static string BuildDefaultExportDirectory(string sourceFilePath)
    {
        var documentsPath = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        var fileName = SanitizeFileName(Path.GetFileNameWithoutExtension(sourceFilePath));
        return Path.Combine(documentsPath, "Nanodrop2000_viewer", fileName);
    }

    private static string SanitizeFileName(string input)
    {
        var invalidChars = Path.GetInvalidFileNameChars();
        return string.Concat(input.Select(ch => invalidChars.Contains(ch) ? '_' : ch));
    }

    private static IEnumerable<IDictionary<string, string>> BuildSummaryRows(Worksheet worksheet)
    {
        foreach (var pair in worksheet.Measurements.Select((measurement, index) => (measurement, index)))
        {
            var measurement = pair.measurement;
            var row = new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["measurement_index"] = pair.index.ToString(CultureInfo.InvariantCulture),
                ["sample_name"] = measurement.Title,
                ["measurement_time"] = measurement.Time.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture),
                ["method_title"] = measurement.Properties.MethodTitle,
                ["method_description"] = measurement.Properties.MethodDescription,
                ["x_label"] = measurement.XLabel,
                ["y_label"] = measurement.YLabel,
                ["point_count"] = measurement.XValues.Count.ToString(CultureInfo.InvariantCulture)
            };

            foreach (var property in measurement.Properties.Properties.OrderBy(entry => entry.Key, StringComparer.Ordinal))
            {
                row[property.Key] = property.Value.Value.Value.ToString("0.########", CultureInfo.InvariantCulture);
                if (!string.IsNullOrWhiteSpace(property.Value.Value.Unit))
                {
                    row[$"{property.Key}_unit"] = property.Value.Value.Unit!;
                }
                if (property.Value.Value.Factor is not null)
                {
                    row[$"{property.Key}_factor"] = property.Value.Value.Factor.Value.ToString("0.########", CultureInfo.InvariantCulture);
                }
                if (property.Value.RawValue is not null)
                {
                    row[$"{property.Key}_raw"] = property.Value.RawValue.Value.ToString("0.########", CultureInfo.InvariantCulture);
                }
            }

            yield return row;
        }
    }

    private static IEnumerable<IDictionary<string, string>> BuildSpectrumRows(Worksheet worksheet)
    {
        foreach (var pair in worksheet.Measurements.Select((measurement, index) => (measurement, index)))
        {
            for (var pointIndex = 0; pointIndex < Math.Min(pair.measurement.XValues.Count, pair.measurement.YValues.Count); pointIndex++)
            {
                yield return new Dictionary<string, string>(StringComparer.Ordinal)
                {
                    ["measurement_index"] = pair.index.ToString(CultureInfo.InvariantCulture),
                    ["measurement_time"] = pair.measurement.Time.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture),
                    ["sample_name"] = pair.measurement.Title,
                    ["x_label"] = pair.measurement.XLabel,
                    ["x_value"] = pair.measurement.XValues[pointIndex].ToString("0.########", CultureInfo.InvariantCulture),
                    ["y_label"] = pair.measurement.YLabel,
                    ["y_value"] = pair.measurement.YValues[pointIndex].ToString("0.########", CultureInfo.InvariantCulture)
                };
            }
        }
    }

    private static void WriteCsv(IEnumerable<IDictionary<string, string>> rows, string path)
    {
        var materialized = rows.ToArray();
        var headers = materialized.SelectMany(row => row.Keys).Distinct(StringComparer.Ordinal).OrderBy(key => key, StringComparer.Ordinal).ToArray();
        using var writer = new StreamWriter(path, false, Encoding.UTF8);
        writer.WriteLine(string.Join(",", headers));

        foreach (var row in materialized)
        {
            writer.WriteLine(string.Join(",", headers.Select(header => EscapeCsv(row.TryGetValue(header, out var value) ? value : string.Empty))));
        }
    }

    private static void WriteSpectraPdf(Worksheet worksheet, string path)
    {
        var document = new SimplePdfDocument(1000, 625);

        foreach (var pair in worksheet.Measurements.Select((measurement, index) => (measurement, index)))
        {
            var page = document.AddPage();
            DrawMeasurementPdf(page, pair.measurement, pair.index);
        }

        File.WriteAllBytes(path, document.Build());
    }

    private static void DrawMeasurementPdf(SimplePdfPage page, Measurement measurement, int index)
    {
        const double plotX = 92;
        const double plotY = 92;
        const double plotWidth = 850;
        const double plotHeight = 420;

        page.SetStrokeRgb(0, 0, 0);
        page.SetFillRgb(0, 0, 0);
        page.DrawText($"{index}: {measurement.Title}", 56, 575, 24, bold: true);
        page.SetFillRgb(0.25, 0.25, 0.25);
        page.DrawText(
            $"Method: {measurement.Properties.MethodTitle}    Time: {measurement.Time:yyyy-MM-dd HH:mm:ss}",
            56,
            548,
            13);

        page.SetStrokeRgb(0, 0, 0);
        page.DrawRectangle(plotX, plotY, plotWidth, plotHeight, fill: false);

        page.SetFillRgb(0, 0, 0);
        page.DrawText(measurement.XLabel, plotX + (plotWidth / 2) - 55, 46, 13);
        page.DrawText(measurement.YLabel, 32, plotY + plotHeight + 10, 13);

        if (measurement.XValues.Count == 0 || measurement.YValues.Count == 0)
        {
            return;
        }

        var visiblePoints = measurement.XValues
            .Zip(measurement.YValues, (x, y) => new SpectrumPoint(x, y))
            .Where(point => point.X >= 220 && point.X <= 350)
            .ToArray();

        if (visiblePoints.Length == 0)
        {
            visiblePoints = measurement.XValues
                .Zip(measurement.YValues, (x, y) => new SpectrumPoint(x, y))
                .ToArray();
        }

        if (visiblePoints.Length == 0)
        {
            return;
        }

        var minX = visiblePoints.Min(point => point.X);
        var maxX = visiblePoints.Max(point => point.X);
        var minY = visiblePoints.Min(point => point.Y);
        var maxY = visiblePoints.Max(point => point.Y);

        if (Math.Abs(maxX - minX) < 0.00001)
        {
            maxX = minX + 1.0;
        }

        var yRange = BuildAxisRange(minY, maxY);

        page.SetStrokeRgb(0.88, 0.88, 0.88);
        page.SetLineWidth(0.5);
        for (var tick = 1; tick < 5; tick++)
        {
            var fx = tick / 5.0;
            page.DrawLine(plotX + (fx * plotWidth), plotY, plotX + (fx * plotWidth), plotY + plotHeight);
            page.DrawLine(plotX, plotY + (fx * plotHeight), plotX + plotWidth, plotY + (fx * plotHeight));
        }

        page.SetFillRgb(0.25, 0.25, 0.25);
        for (var tick = 0; tick <= 5; tick++)
        {
            var fraction = tick / 5.0;
            var xValue = minX + ((maxX - minX) * fraction);
            var yValue = yRange.Min + (yRange.Span * fraction);
            var x = plotX + (fraction * plotWidth);
            var y = plotY + (fraction * plotHeight);
            page.DrawText(xValue.ToString("0.00", CultureInfo.InvariantCulture), x - 14, plotY - 22, 10);
            page.DrawText(yValue.ToString("0.00", CultureInfo.InvariantCulture), plotX - 54, y - 6, 10);
        }

        if (yRange.Min < 0 && yRange.Max > 0)
        {
            var zeroY = plotY + (((0 - yRange.Min) / yRange.Span) * plotHeight);
            page.SetStrokeRgb(0.45, 0.45, 0.45);
            page.SetLineWidth(0.75);
            page.DrawLine(plotX, zeroY, plotX + plotWidth, zeroY);
        }

        foreach (var marker in new[] { 230.0, 260.0, 280.0 })
        {
            if (marker < minX || marker > maxX)
            {
                continue;
            }

            var x = plotX + (((marker - minX) / (maxX - minX)) * plotWidth);
            page.SetStrokeRgb(0.45, 0.45, 0.45);
            page.SetLineWidth(1);
            page.DrawDashedLine(x, plotY, x, plotY + plotHeight, 6, 6);
            page.SetFillRgb(0.35, 0.35, 0.35);
            page.DrawText(marker.ToString("0", CultureInfo.InvariantCulture), x - 10, plotY + 8, 10);
        }

        page.SetStrokeRgb(0.06, 0.42, 0.74);
        page.SetLineWidth(1.3);
        page.BeginClip(plotX, plotY, plotWidth, plotHeight);
        page.BeginPath();
        for (var i = 0; i < visiblePoints.Length; i++)
        {
            var point = visiblePoints[i];
            var x = plotX + (((point.X - minX) / (maxX - minX)) * plotWidth);
            var y = plotY + (((point.Y - yRange.Min) / yRange.Span) * plotHeight);
            if (i == 0)
            {
                page.MoveTo(x, y);
            }
            else
            {
                page.LineTo(x, y);
            }
        }
        page.StrokePath();
        page.EndClip();
    }

    private static AxisRange BuildAxisRange(double min, double max)
    {
        var padding = Math.Max(0.05, (max - min) * 0.08);
        var paddedMin = min - padding;
        var paddedMax = max + padding;

        if (Math.Abs(paddedMax - paddedMin) < 0.00001)
        {
            paddedMin -= 0.5;
            paddedMax += 0.5;
        }

        return new AxisRange(paddedMin, paddedMax);
    }

    private static string EscapeCsv(string value)
    {
        if (value.Contains('"'))
        {
            value = value.Replace("\"", "\"\"");
        }

        return value.IndexOfAny(new[] { ',', '"', '\n', '\r' }) >= 0 ? $"\"{value}\"" : value;
    }

    private sealed record SpectrumPoint(double X, double Y);

    private sealed record AxisRange(double Min, double Max)
    {
        public double Span => Math.Max(Max - Min, 0.00001);
    }
}

internal sealed class SimplePdfDocument
{
    private readonly double _pageWidth;
    private readonly double _pageHeight;
    private readonly List<SimplePdfPage> _pages = new();

    public SimplePdfDocument(double pageWidth, double pageHeight)
    {
        _pageWidth = pageWidth;
        _pageHeight = pageHeight;
    }

    public SimplePdfPage AddPage()
    {
        var page = new SimplePdfPage(_pageWidth, _pageHeight);
        _pages.Add(page);
        return page;
    }

    public byte[] Build()
    {
        var objects = new List<string>();
        var pageObjectNumbers = new List<int>();
        var contentObjectNumbers = new List<int>();

        var fontRegularObjectNumber = AddObject(objects, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        var fontBoldObjectNumber = AddObject(objects, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");

        foreach (var page in _pages)
        {
            var contentBytes = Encoding.ASCII.GetBytes(page.BuildContent());
            var contentStream =
                $"<< /Length {contentBytes.Length} >>\nstream\n{Encoding.ASCII.GetString(contentBytes)}\nendstream";
            contentObjectNumbers.Add(AddObject(objects, contentStream));
        }

        var pagesObjectNumberPlaceholder = objects.Count + 2;
        for (var i = 0; i < _pages.Count; i++)
        {
            var pageObject =
                $"<< /Type /Page /Parent {pagesObjectNumberPlaceholder} 0 R /MediaBox [0 0 {FormatNumber(_pageWidth)} {FormatNumber(_pageHeight)}] /Resources << /Font << /F1 {fontRegularObjectNumber} 0 R /F2 {fontBoldObjectNumber} 0 R >> >> /Contents {contentObjectNumbers[i]} 0 R >>";
            pageObjectNumbers.Add(AddObject(objects, pageObject));
        }

        var kids = string.Join(" ", pageObjectNumbers.Select(number => $"{number} 0 R"));
        var pagesObjectNumber = AddObject(objects, $"<< /Type /Pages /Kids [{kids}] /Count {pageObjectNumbers.Count} >>");
        var catalogObjectNumber = AddObject(objects, $"<< /Type /Catalog /Pages {pagesObjectNumber} 0 R >>");

        using var stream = new MemoryStream();
        using var writer = new StreamWriter(stream, Encoding.ASCII, leaveOpen: true);
        writer.NewLine = "\n";
        writer.Write("%PDF-1.4\n");
        writer.Flush();

        var offsets = new List<long> { 0 };
        for (var i = 0; i < objects.Count; i++)
        {
            offsets.Add(stream.Position);
            writer.Write($"{i + 1} 0 obj\n{objects[i]}\nendobj\n");
            writer.Flush();
        }

        var xrefPosition = stream.Position;
        writer.Write($"xref\n0 {objects.Count + 1}\n");
        writer.Write("0000000000 65535 f \n");
        foreach (var offset in offsets.Skip(1))
        {
            writer.Write($"{offset:D10} 00000 n \n");
        }

        writer.Write($"trailer\n<< /Size {objects.Count + 1} /Root {catalogObjectNumber} 0 R >>\n");
        writer.Write($"startxref\n{xrefPosition}\n%%EOF");
        writer.Flush();

        return stream.ToArray();
    }

    private static int AddObject(ICollection<string> objects, string body)
    {
        objects.Add(body);
        return objects.Count;
    }

    private static string FormatNumber(double value)
    {
        return value.ToString("0.###", CultureInfo.InvariantCulture);
    }
}

internal sealed class SimplePdfPage
{
    private readonly double _pageHeight;
    private readonly StringBuilder _content = new();

    public SimplePdfPage(double pageWidth, double pageHeight)
    {
        _pageHeight = pageHeight;
    }

    public void SetStrokeRgb(double r, double g, double b)
    {
        _content.AppendLine($"{FormatNumber(r)} {FormatNumber(g)} {FormatNumber(b)} RG");
    }

    public void SetFillRgb(double r, double g, double b)
    {
        _content.AppendLine($"{FormatNumber(r)} {FormatNumber(g)} {FormatNumber(b)} rg");
    }

    public void SetLineWidth(double width)
    {
        _content.AppendLine($"{FormatNumber(width)} w");
    }

    public void DrawLine(double x1, double y1, double x2, double y2)
    {
        _content.AppendLine($"{FormatX(x1)} {FormatY(y1)} m {FormatX(x2)} {FormatY(y2)} l S");
    }

    public void DrawDashedLine(double x1, double y1, double x2, double y2, double dash, double gap)
    {
        _content.AppendLine($"[{FormatNumber(dash)} {FormatNumber(gap)}] 0 d");
        DrawLine(x1, y1, x2, y2);
        _content.AppendLine("[] 0 d");
    }

    public void DrawRectangle(double x, double y, double width, double height, bool fill)
    {
        _content.AppendLine($"{FormatX(x)} {FormatY(y + height)} {FormatNumber(width)} {FormatNumber(height)} re {(fill ? "f" : "S")}");
    }

    public void DrawText(string text, double x, double y, double fontSize, bool bold = false)
    {
        _content.AppendLine("BT");
        _content.AppendLine($"/{(bold ? "F2" : "F1")} {FormatNumber(fontSize)} Tf");
        _content.AppendLine($"{FormatX(x)} {FormatY(y)} Td");
        _content.AppendLine($"({EscapeText(text)}) Tj");
        _content.AppendLine("ET");
    }

    public void BeginClip(double x, double y, double width, double height)
    {
        _content.AppendLine("q");
        _content.AppendLine($"{FormatX(x)} {FormatY(y + height)} {FormatNumber(width)} {FormatNumber(height)} re W n");
    }

    public void EndClip()
    {
        _content.AppendLine("Q");
    }

    public void BeginPath()
    {
    }

    public void MoveTo(double x, double y)
    {
        _content.AppendLine($"{FormatX(x)} {FormatY(y)} m");
    }

    public void LineTo(double x, double y)
    {
        _content.AppendLine($"{FormatX(x)} {FormatY(y)} l");
    }

    public void StrokePath()
    {
        _content.AppendLine("S");
    }

    public string BuildContent()
    {
        return _content.ToString();
    }

    private string FormatX(double value)
    {
        return FormatNumber(value);
    }

    private string FormatY(double value)
    {
        return FormatNumber(_pageHeight - value);
    }

    private static string FormatNumber(double value)
    {
        return value.ToString("0.###", CultureInfo.InvariantCulture);
    }

    private static string EscapeText(string value)
    {
        return value
            .Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("(", "\\(", StringComparison.Ordinal)
            .Replace(")", "\\)", StringComparison.Ordinal);
    }
}
