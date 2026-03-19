using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace NanodropViewer.Core;

public static class ReferenceSpectrumLibrary
{
    public static IReadOnlyList<ReferenceSpectrum> LoadFromDirectory(string directoryPath)
    {
        if (!Directory.Exists(directoryPath))
        {
            return Array.Empty<ReferenceSpectrum>();
        }

        return Directory.EnumerateFiles(directoryPath, "*.jdx", SearchOption.TopDirectoryOnly)
            .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
            .Select(path =>
            {
                try
                {
                    return JcampDxParser.Parse(path);
                }
                catch
                {
                    return null;
                }
            })
            .Where(spectrum => spectrum is not null)
            .Cast<ReferenceSpectrum>()
            .ToArray();
    }
}
