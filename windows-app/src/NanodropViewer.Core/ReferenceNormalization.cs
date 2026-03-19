using System;
using System.Collections.Generic;
using System.Linq;

namespace NanodropViewer.Core;

public static class ReferenceNormalization
{
    public static IReadOnlyList<(double X, double Y)> Normalize(
        ReferenceSpectrum spectrum,
        Measurement measurement,
        ReferenceNormalizationMode mode,
        double minX = 220.0,
        double maxX = 350.0)
    {
        var rawPoints = spectrum.XValues.Zip(spectrum.YValues, (x, y) => (X: x, Y: y))
            .Where(point => point.X >= minX && point.X <= maxX)
            .ToArray();
        if (rawPoints.Length == 0)
        {
            return Array.Empty<(double X, double Y)>();
        }

        var samplePoints = measurement.XValues.Zip(measurement.YValues, (x, y) => (X: x, Y: y))
            .Where(point => point.X >= minX && point.X <= maxX)
            .ToArray();
        if (samplePoints.Length == 0)
        {
            return rawPoints;
        }

        var baselineCorrectedReference = BaselineCorrect(rawPoints);
        var baselineCorrectedSample = BaselineCorrect(samplePoints);

        var samplePeak = Math.Max(baselineCorrectedSample.Max(point => point.Y), 0.00001);
        var referencePeak = Math.Max(baselineCorrectedReference.Max(point => point.Y), 0.00001);

        double scale = mode switch
        {
            ReferenceNormalizationMode.PeakNormalize => samplePeak / referencePeak,
            ReferenceNormalizationMode.AreaNormalize => TrapezoidArea(baselineCorrectedSample) / Math.Max(TrapezoidArea(baselineCorrectedReference), 0.00001),
            ReferenceNormalizationMode.FitToSample => FitScale(baselineCorrectedReference, baselineCorrectedSample, samplePeak / referencePeak),
            _ => samplePeak / referencePeak
        };

        return baselineCorrectedReference
            .Select(point => (point.X, point.Y * scale))
            .ToArray();
    }

    public static IReadOnlyList<(double X, double Y)> BaselineCorrect(IReadOnlyList<(double X, double Y)> points)
    {
        if (points.Count == 0)
        {
            return Array.Empty<(double X, double Y)>();
        }

        var minY = points.Min(point => point.Y);
        return points.Select(point => (point.X, Math.Max(point.Y - minY, 0.0))).ToArray();
    }

    private static double FitScale(
        IReadOnlyList<(double X, double Y)> referencePoints,
        IReadOnlyList<(double X, double Y)> samplePoints,
        double fallbackScale)
    {
        double numerator = 0;
        double denominator = 0;

        foreach (var point in referencePoints)
        {
            var sampleY = InterpolateY(samplePoints, point.X);
            if (sampleY is null)
            {
                continue;
            }

            numerator += sampleY.Value * point.Y;
            denominator += point.Y * point.Y;
        }

        return denominator <= 0.00001 ? fallbackScale : numerator / denominator;
    }

    private static double TrapezoidArea(IReadOnlyList<(double X, double Y)> points)
    {
        if (points.Count < 2)
        {
            return 0;
        }

        double area = 0;
        for (var index = 0; index < points.Count - 1; index++)
        {
            var left = points[index];
            var right = points[index + 1];
            var width = right.X - left.X;
            area += width * (left.Y + right.Y) / 2.0;
        }

        return area;
    }

    private static double? InterpolateY(IReadOnlyList<(double X, double Y)> points, double x)
    {
        if (points.Count < 2)
        {
            return null;
        }

        for (var index = 0; index < points.Count - 1; index++)
        {
            var left = points[index];
            var right = points[index + 1];
            if (x < left.X || x > right.X || Math.Abs(right.X - left.X) < double.Epsilon)
            {
                continue;
            }

            var fraction = (x - left.X) / (right.X - left.X);
            return left.Y + ((right.Y - left.Y) * fraction);
        }

        return null;
    }
}
