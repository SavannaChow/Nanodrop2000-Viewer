using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;

namespace NanodropViewer.Core;

public sealed record ExportResult(string SummaryCsvPath, string SpectrumCsvPath, string OutputDirectory);

public static class WorksheetExporter
{
    public static ExportResult ExportCsv(Worksheet worksheet, string sourceFilePath, string? outputDirectory = null, string? baseName = null)
    {
        var exportDirectory = outputDirectory ?? BuildDefaultExportDirectory(sourceFilePath);
        Directory.CreateDirectory(exportDirectory);

        var normalizedBaseName = baseName ?? Path.GetFileNameWithoutExtension(sourceFilePath);
        var summaryPath = Path.Combine(exportDirectory, $"{normalizedBaseName}_summary.csv");
        var spectrumPath = Path.Combine(exportDirectory, $"{normalizedBaseName}_spectrum.csv");

        WriteCsv(BuildSummaryRows(worksheet), summaryPath);
        WriteCsv(BuildSpectrumRows(worksheet), spectrumPath);

        return new ExportResult(summaryPath, spectrumPath, exportDirectory);
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
        using var writer = new StreamWriter(path, false, System.Text.Encoding.UTF8);
        writer.WriteLine(string.Join(",", headers));

        foreach (var row in materialized)
        {
            writer.WriteLine(string.Join(",", headers.Select(header => EscapeCsv(row.TryGetValue(header, out var value) ? value : string.Empty))));
        }
    }

    private static string EscapeCsv(string value)
    {
        if (value.Contains('"'))
        {
            value = value.Replace("\"", "\"\"");
        }

        return value.IndexOfAny(new[] { ',', '"', '\n', '\r' }) >= 0 ? $"\"{value}\"" : value;
    }
}
