using PdfSharp.Drawing;
using PdfSharp.Fonts;
using PdfSharp.Pdf;
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
    static WorksheetExporter()
    {
        GlobalFontSettings.UseWindowsFontsUnderWindows = true;
    }

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
        using var document = new PdfDocument();
        document.Info.Title = "Nanodrop spectra";

        foreach (var pair in worksheet.Measurements.Select((measurement, index) => (measurement, index)))
        {
            var page = document.AddPage();
            page.Width = XUnit.FromPoint(1000);
            page.Height = XUnit.FromPoint(625);

            using var graphics = XGraphics.FromPdfPage(page);
            DrawMeasurementPdf(graphics, page.Width.Point, page.Height.Point, pair.measurement, pair.index);
        }

        document.Save(path);
    }

    private static void DrawMeasurementPdf(XGraphics graphics, double pageWidth, double pageHeight, Measurement measurement, int index)
    {
        const double plotX = 92;
        const double plotY = 92;
        const double plotWidth = 850;
        const double plotHeight = 420;

        var titleFont = new XFont("Arial", 24, XFontStyleEx.Bold);
        var textFont = new XFont("Arial", 13, XFontStyleEx.Regular);
        var tickFont = new XFont("Arial", 10, XFontStyleEx.Regular);
        var blackBrush = XBrushes.Black;
        var grayBrush = new XSolidBrush(XColor.FromArgb(64, 64, 64));

        graphics.DrawString($"{index}: {measurement.Title}", titleFont, blackBrush, new XPoint(56, 50));
        graphics.DrawString(
            $"Method: {measurement.Properties.MethodTitle}    Time: {measurement.Time:yyyy-MM-dd HH:mm:ss}",
            textFont,
            grayBrush,
            new XPoint(56, 78));

        var plotRect = new XRect(plotX, plotY, plotWidth, plotHeight);
        graphics.DrawRectangle(XPens.Black, plotRect);
        graphics.DrawString(measurement.XLabel, textFont, blackBrush, new XRect(plotX, 540, plotWidth, 20), XStringFormats.Center);
        graphics.DrawString(measurement.YLabel, textFont, blackBrush, new XPoint(20, 110));

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
        if (Math.Abs(maxX - minX) < 0.00001)
        {
            maxX = minX + 1.0;
        }

        var minY = visiblePoints.Min(point => point.Y);
        var maxY = visiblePoints.Max(point => point.Y);
        var yRange = BuildAxisRange(minY, maxY);

        var gridPen = new XPen(XColor.FromArgb(224, 224, 224), 0.5);
        for (var tick = 1; tick < 5; tick++)
        {
            var fx = tick / 5.0;
            graphics.DrawLine(gridPen, plotX + (fx * plotWidth), plotY, plotX + (fx * plotWidth), plotY + plotHeight);
            graphics.DrawLine(gridPen, plotX, plotY + (fx * plotHeight), plotX + plotWidth, plotY + (fx * plotHeight));
        }

        for (var tick = 0; tick <= 5; tick++)
        {
            var fraction = tick / 5.0;
            var xValue = minX + ((maxX - minX) * fraction);
            var yValue = yRange.Min + (yRange.Span * fraction);
            var x = plotX + (fraction * plotWidth);
            var y = plotY + (fraction * plotHeight);
            graphics.DrawString(xValue.ToString("0.00", CultureInfo.InvariantCulture), tickFont, grayBrush, new XPoint(x - 14, plotY + plotHeight + 16));
            graphics.DrawString(yValue.ToString("0.00", CultureInfo.InvariantCulture), tickFont, grayBrush, new XPoint(plotX - 54, y + 3));
        }

        var markerPen = new XPen(XColor.FromArgb(115, 115, 115), 1) { DashStyle = XDashStyle.Dash };
        foreach (var marker in new[] { 230.0, 260.0, 280.0 })
        {
            if (marker < minX || marker > maxX)
            {
                continue;
            }

            var x = plotX + (((marker - minX) / (maxX - minX)) * plotWidth);
            graphics.DrawLine(markerPen, x, plotY, x, plotY + plotHeight);
            graphics.DrawString(marker.ToString("0", CultureInfo.InvariantCulture), tickFont, grayBrush, new XPoint(x - 8, plotY + plotHeight + 30));
        }

        var samplePen = new XPen(XColor.FromArgb(15, 108, 189), 1.5);
        var path = new XGraphicsPath();
        var chartPoints = visiblePoints
            .Select(point => ToPdfPoint(point.X, point.Y, minX, maxX, yRange, plotRect, pageHeight))
            .ToArray();
        if (chartPoints.Length > 1)
        {
            path.AddLines(chartPoints);
            var state = graphics.Save();
            graphics.IntersectClip(plotRect);
            graphics.DrawPath(samplePen, path);
            graphics.Restore(state);
        }
    }

    private static XPoint ToPdfPoint(double x, double y, double minX, double maxX, AxisRange yRange, XRect plotRect, double pageHeight)
    {
        var normalizedX = (x - minX) / Math.Max(maxX - minX, 0.00001);
        var normalizedY = (y - yRange.Min) / yRange.Span;
        var canvasX = plotRect.Left + (normalizedX * plotRect.Width);
        var canvasY = plotRect.Bottom - (normalizedY * plotRect.Height);
        return new XPoint(canvasX, canvasY);
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
