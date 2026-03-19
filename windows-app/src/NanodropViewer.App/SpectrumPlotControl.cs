using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace NanodropViewer.App;

public sealed class SpectrumPlotControl : FrameworkElement
{
    public static readonly DependencyProperty SeriesProperty =
        DependencyProperty.Register(
            nameof(Series),
            typeof(IReadOnlyList<SpectrumSeriesItem>),
            typeof(SpectrumPlotControl),
            new FrameworkPropertyMetadata(
                Array.Empty<SpectrumSeriesItem>(),
                FrameworkPropertyMetadataOptions.AffectsRender,
                HandleSeriesChanged));

    private const double MinX = 220.0;
    private const double MaxX = 350.0;
    private SpectrumHitPoint? _selectedPoint;

    public IReadOnlyList<SpectrumSeriesItem> Series
    {
        get => (IReadOnlyList<SpectrumSeriesItem>)GetValue(SeriesProperty);
        set => SetValue(SeriesProperty, value);
    }

    private static void HandleSeriesChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var control = (SpectrumPlotControl)d;

        if (e.OldValue is INotifyCollectionChanged oldCollection)
        {
            oldCollection.CollectionChanged -= control.HandleSeriesCollectionChanged;
        }

        if (e.NewValue is INotifyCollectionChanged newCollection)
        {
            newCollection.CollectionChanged += control.HandleSeriesCollectionChanged;
        }

        control._selectedPoint = null;
        control.InvalidateVisual();
    }

    private void HandleSeriesCollectionChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        _selectedPoint = null;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext drawingContext)
    {
        base.OnRender(drawingContext);

        var bounds = new Rect(0, 0, ActualWidth, ActualHeight);
        drawingContext.DrawRoundedRectangle(new SolidColorBrush(Color.FromRgb(0xF8, 0xFA, 0xFC)), new Pen(new SolidColorBrush(Color.FromRgb(0xD0, 0xD7, 0xE2)), 1), bounds, 16, 16);

        if (bounds.Width <= 120 || bounds.Height <= 120)
        {
            return;
        }

        var plotRect = new Rect(72, 20, Math.Max(bounds.Width - 92, 1), Math.Max(bounds.Height - 68, 1));
        var yRange = ComputeYRange();
        DrawGrid(drawingContext, plotRect);
        DrawAxes(drawingContext, plotRect, yRange);
        DrawMarkerLines(drawingContext, plotRect, yRange);
        DrawSeries(drawingContext, plotRect, yRange);
        DrawSelectedPoint(drawingContext, plotRect, yRange);
    }

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonDown(e);
        Focus();
        CaptureMouse();
        _selectedPoint = FindNearestPoint(e.GetPosition(this));
        InvalidateVisual();
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonUp(e);
        if (IsMouseCaptured)
        {
            ReleaseMouseCapture();
        }
    }

    private void DrawGrid(DrawingContext dc, Rect plotRect)
    {
        var gridPen = new Pen(new SolidColorBrush(Color.FromRgb(0xE2, 0xE8, 0xF0)), 1);
        for (var index = 0; index <= 5; index++)
        {
            var fraction = index / 5.0;
            var x = plotRect.Left + (plotRect.Width * fraction);
            var y = plotRect.Top + (plotRect.Height * fraction);
            dc.DrawLine(gridPen, new Point(x, plotRect.Top), new Point(x, plotRect.Bottom));
            dc.DrawLine(gridPen, new Point(plotRect.Left, y), new Point(plotRect.Right, y));
        }
    }

    private void DrawAxes(DrawingContext dc, Rect plotRect, AxisRange yRange)
    {
        var axisPen = new Pen(new SolidColorBrush(Color.FromRgb(0x94, 0xA3, 0xB8)), 1.5);
        dc.DrawRectangle(null, axisPen, plotRect);

        for (var index = 0; index <= 5; index++)
        {
            var fraction = index / 5.0;
            var xValue = MinX + ((MaxX - MinX) * fraction);
            var yValue = yRange.Max - (yRange.Span * fraction);

            DrawText(dc, $"{xValue:0.0}", new Point(plotRect.Left + (plotRect.Width * fraction) - 12, plotRect.Bottom + 8), 12, Brushes.DimGray);
            DrawText(dc, $"{yValue:0.00}", new Point(8, plotRect.Top + (plotRect.Height * fraction) - 8), 12, Brushes.DimGray);
        }

        DrawText(dc, "Absorbance (nm)", new Point(plotRect.Left, 2), 14, Brushes.Black, FontWeights.SemiBold);
    }

    private void DrawMarkerLines(DrawingContext dc, Rect plotRect, AxisRange yRange)
    {
        var markerPen = new Pen(new SolidColorBrush(Color.FromArgb(0x66, 0x4B, 0x55, 0x63)), 2)
        {
            DashStyle = new DashStyle(new[] { 6.0, 6.0 }, 0)
        };

        foreach (var marker in new[] { 230.0, 260.0, 280.0 })
        {
            var point = ToCanvasPoint(marker, yRange.Min, plotRect, yRange);
            dc.DrawLine(markerPen, new Point(point.X, plotRect.Top), new Point(point.X, plotRect.Bottom));
            DrawText(dc, marker.ToString("0", CultureInfo.InvariantCulture), new Point(point.X - 10, plotRect.Bottom - 20), 12, Brushes.DimGray);
        }

    }

    private void DrawSeries(DrawingContext dc, Rect plotRect, AxisRange yRange)
    {
        dc.PushClip(new RectangleGeometry(plotRect));
        foreach (var series in Series)
        {
            var visiblePoints = series.Points.Where(point => point.X >= MinX && point.X <= MaxX).ToArray();
            if (visiblePoints.Length == 0)
            {
                continue;
            }

            var geometry = new StreamGeometry();
            using (var context = geometry.Open())
            {
                var start = ToCanvasPoint(visiblePoints[0].X, visiblePoints[0].Y, plotRect, yRange);
                context.BeginFigure(start, false, false);
                for (var index = 1; index < visiblePoints.Length; index++)
                {
                    var point = ToCanvasPoint(visiblePoints[index].X, visiblePoints[index].Y, plotRect, yRange);
                    context.LineTo(point, true, false);
                }
            }

            geometry.Freeze();
            var pen = new Pen(new SolidColorBrush(series.Color), 2.5)
            {
                StartLineCap = PenLineCap.Round,
                EndLineCap = PenLineCap.Round
            };
            if (series.IsDashed)
            {
                pen.DashStyle = new DashStyle(new[] { 8.0, 5.0 }, 0);
            }

            dc.DrawGeometry(null, pen, geometry);
        }
        dc.Pop();
    }

    private void DrawSelectedPoint(DrawingContext dc, Rect plotRect, AxisRange yRange)
    {
        if (_selectedPoint is null)
        {
            return;
        }

        var point = ToCanvasPoint(_selectedPoint.X, _selectedPoint.Y, plotRect, yRange);
        dc.DrawEllipse(Brushes.White, null, point, 8, 8);
        dc.DrawEllipse(new SolidColorBrush(_selectedPoint.Color), null, point, 5, 5);
        DrawText(
            dc,
            $"{_selectedPoint.Label}  x={_selectedPoint.X:0.0}  y={_selectedPoint.Y:0.00}",
            new Point(Math.Min(point.X + 12, Math.Max(0, plotRect.Right - 220)), Math.Max(0, point.Y - 20)),
            13,
            Brushes.Black,
            FontWeights.SemiBold);
    }

    private SpectrumHitPoint? FindNearestPoint(Point mouse)
    {
        var plotRect = new Rect(72, 20, Math.Max(ActualWidth - 92, 1), Math.Max(ActualHeight - 68, 1));
        if (!plotRect.Contains(mouse))
        {
            return null;
        }

        var yRange = ComputeYRange();
        SpectrumHitPoint? best = null;
        var bestDistance = 24.0;

        foreach (var series in Series)
        {
            foreach (var point in series.Points.Where(point => point.X >= MinX && point.X <= MaxX))
            {
                var canvasPoint = ToCanvasPoint(point.X, point.Y, plotRect, yRange);
                var distance = (canvasPoint - mouse).Length;
                if (distance >= bestDistance)
                {
                    continue;
                }

                bestDistance = distance;
                best = new SpectrumHitPoint(series.Label, point.X, point.Y, series.Color);
            }
        }

        return best;
    }

    private Point ToCanvasPoint(double x, double y, Rect plotRect, AxisRange yRange)
    {
        var xFraction = (x - MinX) / Math.Max(MaxX - MinX, 0.00001);
        var yFraction = (y - yRange.Min) / yRange.Span;
        return new Point(
            plotRect.Left + (plotRect.Width * xFraction),
            plotRect.Bottom - (plotRect.Height * yFraction));
    }

    private AxisRange ComputeYRange()
    {
        var visiblePoints = Series
            .SelectMany(series => series.Points)
            .Where(point => point.X >= MinX && point.X <= MaxX)
            .ToArray();

        if (visiblePoints.Length == 0)
        {
            return new AxisRange(0, 1.0);
        }

        var max = visiblePoints.Max(point => point.Y);
        var paddedMin = 0.0;
        var padding = Math.Max(0.05, max * 0.08);
        var paddedMax = max + padding;

        if (Math.Abs(paddedMax - paddedMin) < 0.00001)
        {
            paddedMax += 0.5;
        }

        return new AxisRange(paddedMin, paddedMax);
    }

    private static void DrawText(DrawingContext dc, string text, Point origin, double fontSize, Brush brush, FontWeight? fontWeight = null)
    {
        var formatted = new FormattedText(
            text,
            CultureInfo.InvariantCulture,
            FlowDirection.LeftToRight,
            new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, fontWeight ?? FontWeights.Normal, FontStretches.Normal),
            fontSize,
            brush,
            1.0);

        dc.DrawText(formatted, origin);
    }

    private sealed record SpectrumHitPoint(string Label, double X, double Y, Color Color);

    private sealed record AxisRange(double Min, double Max)
    {
        public double Span => Math.Max(Max - Min, 0.00001);
    }
}
