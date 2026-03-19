param(
    [string]$OutputPath,
    [string]$SourceImagePath
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$directory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $directory | Out-Null

$size = 256

if ($SourceImagePath) {
    $sourceBitmap = [System.Drawing.Image]::FromFile($SourceImagePath)
    $bitmap = New-Object System.Drawing.Bitmap $size, $size
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $graphics.DrawImage($sourceBitmap, 0, 0, $size, $size)
    $sourceBitmap.Dispose()
} else {
    $bitmap = New-Object System.Drawing.Bitmap $size, $size
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::FromArgb(246, 249, 252))

    $gradient = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        ([System.Drawing.Rectangle]::new(0, 0, $size, $size)),
        ([System.Drawing.Color]::FromArgb(15, 108, 189)),
        ([System.Drawing.Color]::FromArgb(4, 120, 87)),
        45
    )

    $graphics.FillEllipse($gradient, 20, 20, 216, 216)

    $linePen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, 10)
    $points = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(48, 170),
        [System.Drawing.PointF]::new(88, 138),
        [System.Drawing.PointF]::new(122, 152),
        [System.Drawing.PointF]::new(158, 96),
        [System.Drawing.PointF]::new(208, 78)
    )
    $graphics.DrawLines($linePen, $points)

    $font = New-Object System.Drawing.Font("Arial", 46, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $graphics.DrawString("TB", $font, [System.Drawing.Brushes]::White, [System.Drawing.RectangleF]::new(0, 28, $size, 90), $format)
}

$pngPath = [System.IO.Path]::ChangeExtension($OutputPath, ".png")
$bitmap.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png)

$graphics.Dispose()
$bitmap.Dispose()
if ($gradient) { $gradient.Dispose() }
if ($linePen) { $linePen.Dispose() }
if ($font) { $font.Dispose() }
if ($format) { $format.Dispose() }

$pngBytes = [System.IO.File]::ReadAllBytes($pngPath)
$stream = [System.IO.File]::Open($OutputPath, [System.IO.FileMode]::Create)
$writer = New-Object System.IO.BinaryWriter($stream)

$writer.Write([UInt16]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]1)
$writer.Write([byte]0)
$writer.Write([byte]0)
$writer.Write([byte]0)
$writer.Write([byte]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]32)
$writer.Write([UInt32]$pngBytes.Length)
$writer.Write([UInt32]22)
$writer.Write($pngBytes)
$writer.Flush()
$writer.Close()

Remove-Item $pngPath -Force
