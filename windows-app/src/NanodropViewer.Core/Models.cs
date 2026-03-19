using System;
using System.Collections.Generic;

namespace NanodropViewer.Core;

public sealed record Worksheet(IReadOnlyList<Measurement> Measurements);

public sealed record Measurement(
    string Title,
    IReadOnlyList<double> XValues,
    string XLabel,
    IReadOnlyList<double> YValues,
    string YLabel,
    DateTimeOffset Time,
    PropertyBag Properties
);

public sealed record PropertyBag(
    string MethodTitle,
    string MethodDescription,
    string MethodFilename,
    IReadOnlyDictionary<string, MeasurementProperty> Properties
)
{
    public static PropertyBag Empty { get; } = new(
        string.Empty,
        string.Empty,
        string.Empty,
        new Dictionary<string, MeasurementProperty>()
    );
}

public sealed record MeasurementProperty(
    string Id,
    string Type,
    QuantValue Value,
    QuantValue? RawValue
);

public sealed record QuantValue(
    string Title,
    int Digits,
    double Value,
    string? Unit,
    double? Factor
);

public sealed record ReferenceSpectrum(
    string Id,
    string ShortTitle,
    string Title,
    IReadOnlyList<double> XValues,
    IReadOnlyList<double> YValues,
    string XUnits,
    string YUnits
);

public enum ReferenceNormalizationMode
{
    PeakNormalize,
    AreaNormalize,
    FitToSample
}
