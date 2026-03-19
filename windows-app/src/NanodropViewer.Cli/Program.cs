using System;
using System.Globalization;
using System.IO;
using System.Linq;
using NanodropViewer.Core;

if (args.Length == 0)
{
    Console.WriteLine("Usage: dotnet run --project windows-app/src/NanodropViewer.Cli -- <file.tbwk> [reference_dir]");
    return;
}

var tbwkPath = Path.GetFullPath(args[0]);
if (!File.Exists(tbwkPath))
{
    Console.Error.WriteLine($"File not found: {tbwkPath}");
    return;
}

var referenceDirectory = args.Length > 1
    ? Path.GetFullPath(args[1])
    : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "assets", "reference_spectra"));

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
