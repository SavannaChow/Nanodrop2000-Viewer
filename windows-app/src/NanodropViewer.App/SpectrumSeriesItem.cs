using System.Collections.Generic;
using System.Windows.Media;

namespace NanodropViewer.App;

public sealed record SpectrumSeriesItem(
    string Label,
    IReadOnlyList<SpectrumPoint> Points,
    Color Color,
    bool IsDashed,
    bool IsReference
);

public sealed record SpectrumPoint(double X, double Y);
