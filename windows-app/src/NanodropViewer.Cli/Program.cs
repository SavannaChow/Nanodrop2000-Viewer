using System;
using System.Globalization;
using System.IO;
using System.Linq;
using NanodropViewer.Core;

if (args.Length == 0)
{
    Console.WriteLine("Usage: dotnet run --project windows-app/src/NanodropViewer.Cli -- <file.tbwk> [reference_dir] [--export]");
    return;
}

var export = args.Any(arg => string.Equals(arg, "--export", StringComparison.OrdinalIgnoreCase));
var filteredArgs = args.Where(arg => !string.Equals(arg, "--export", StringComparison.OrdinalIgnoreCase)).ToArray();
if (filteredArgs.Length == 0)
{
    Console.WriteLine("TBWK file path is required.");
    return;
}

var tbwkPath = Path.GetFullPath(filteredArgs[0]);
if (!File.Exists(tbwkPath))
{
    Console.Error.WriteLine($"File not found: {tbwkPath}");
    return;
}

var referenceDirectory = filteredArgs.Length > 1
    ? Path.GetFullPath(filteredArgs[1])
    : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", "spectrum_database"));

var worksheet = TbwkParser.Parse(tbwkPath);
Console.WriteLine($"Measurements: {worksheet.Measurements.Count}");

for (var index = 0; index < worksheet.Measurements.Count; index++)
{
    var measurement = worksheet.Measurements[index];
    Console.WriteLine($"{index + 1}. {measurement.Title} ({measurement.Time:yyyy-MM-dd HH:mm:ss zzz})");
}

var references = ReferenceSpectrumLibrary.LoadFromDirectory(referenceDirectory);
Console.WriteLine($"Reference spectra: {references.Count}");

foreach (var reference in references)
{
    var peakIndex = reference.YValues
        .Select((value, index) => (value, index))
        .OrderByDescending(entry => entry.value)
        .First().index;
    Console.WriteLine(
        $"{reference.ShortTitle}: peak at {reference.XValues[peakIndex].ToString("0.0", CultureInfo.InvariantCulture)} nm"
    );
}

if (export)
{
    var result = WorksheetExporter.Export(worksheet, tbwkPath);
    Console.WriteLine($"Exported summary CSV: {result.SummaryCsvPath}");
    Console.WriteLine($"Exported spectrum CSV: {result.SpectrumCsvPath}");
    Console.WriteLine($"Exported PDF: {result.PdfPath}");
}
