using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;

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

    public static IReadOnlyList<ReferenceSpectrum> LoadFromAssembly(Assembly assembly)
    {
        return assembly
            .GetManifestResourceNames()
            .Where(name => name.EndsWith(".jdx", StringComparison.OrdinalIgnoreCase))
            .OrderBy(name => name, StringComparer.OrdinalIgnoreCase)
            .Select(name =>
            {
                try
                {
                    using var stream = assembly.GetManifestResourceStream(name);
                    if (stream is null)
                    {
                        return null;
                    }

                    using var reader = new StreamReader(stream);
                    var resourceId = Path.GetFileNameWithoutExtension(name);
                    return JcampDxParser.Parse(reader.ReadToEnd(), resourceId);
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
