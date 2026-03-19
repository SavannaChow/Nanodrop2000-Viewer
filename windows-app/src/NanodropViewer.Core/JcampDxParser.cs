using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;

namespace NanodropViewer.Core;

public static class JcampDxParser
{
    public static ReferenceSpectrum Parse(string filePath)
    {
        return Parse(File.ReadAllText(filePath), Path.GetFileNameWithoutExtension(filePath));
    }

    public static ReferenceSpectrum Parse(string raw, string fallbackId)
    {
        var title = fallbackId.Replace('_', ' ');
        var xUnits = "Wavelength (nm)";
        var yUnits = "Normalized reference";
        var xValues = new List<double>();
        var yValues = new List<double>();
        var inXySection = false;

        foreach (var line in raw.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None))
        {
            var trimmed = line.Trim();
            if (string.IsNullOrWhiteSpace(trimmed))
            {
                continue;
            }

            if (trimmed.StartsWith("##TITLE=", StringComparison.Ordinal))
            {
                title = trimmed["##TITLE=".Length..];
                continue;
            }

            if (trimmed.StartsWith("##XUNITS=", StringComparison.Ordinal))
            {
                xUnits = trimmed["##XUNITS=".Length..];
                continue;
            }

            if (trimmed.StartsWith("##YUNITS=", StringComparison.Ordinal))
            {
                yUnits = trimmed["##YUNITS=".Length..];
                continue;
            }

            if (trimmed.StartsWith("##XYPOINTS=", StringComparison.Ordinal))
            {
                inXySection = true;
                continue;
            }

            if (trimmed.StartsWith("##END=", StringComparison.Ordinal))
            {
                break;
            }

            if (!inXySection)
            {
                continue;
            }

            var parts = trimmed.Split(',').Select(part => part.Trim()).ToArray();
            if (parts.Length < 2)
            {
                continue;
            }

            if (!double.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out var x)
                || !double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var y))
            {
                continue;
            }

            xValues.Add(x);
            yValues.Add(y);
        }

        if (xValues.Count == 0 || xValues.Count != yValues.Count)
        {
            throw new TbwkException($"Could not parse {fallbackId}.jdx.");
        }

        return new ReferenceSpectrum(
            fallbackId,
            ShortTitleFor(fallbackId),
            title,
            xValues,
            yValues,
            xUnits,
            yUnits
        );
    }

    private static string ShortTitleFor(string id)
    {
        return id switch
        {
            "dsDNA" => "DNA",
            "RNA" => "RNA",
            "guanidine_hydrochloride_GuHCl" => "GuHCl",
            "guanidine_thiocyanate_GTC" => "GTC",
            "protein_BSA" => "BSA",
            "phenol" => "Phenol",
            "ethanol" => "Ethanol",
            "EDTA" => "EDTA",
            _ => id
        };
    }
}
