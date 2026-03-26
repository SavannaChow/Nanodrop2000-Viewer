using Microsoft.Win32;
using NanodropViewer.Core;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace NanodropViewer.App;

public sealed class MainWindowViewModel : INotifyPropertyChanged
{
    private Worksheet? _worksheet;
    private TbwkEditableDocument? _editableDocument;
    private string _fileName = "No file selected";
    private string _statusMessage = "Import a TBWK file to begin.";
    private ObservableCollection<SpectrumSeriesItem> _plotSeries = new();
    private readonly ObservableCollection<SampleItem> _selectedSamples = new();
    private SampleItem? _selectedSample;
    private ReferenceNormalizationMode _selectedNormalizationMode = ReferenceNormalizationMode.PeakNormalize;
    private string? _currentFilePath;
    private string? _updateStatusMessage;
    private AppUpdateInfo? _availableUpdate;
    private string? _latestVersion;
    private bool _hasCheckedForUpdates;
    private bool _hasEditedChanges;
    private readonly DispatcherTimer _transientMessageTimer = new() { Interval = TimeSpan.FromSeconds(3.5) };
    private string? _pendingStatusMessageToClear;
    private string? _pendingUpdateMessageToClear;

    public MainWindowViewModel()
    {
        SelectedSamples = new ReadOnlyObservableCollection<SampleItem>(_selectedSamples);
        ImportCommand = new RelayCommand(ImportFile);
        ExportCommand = new RelayCommand(ExportFiles, () => HasWorksheet);
        CheckUpdatesCommand = new RelayCommand(() => _ = CheckForUpdatesAsync(true));
        DownloadUpdateCommand = new RelayCommand(DownloadUpdate, () => AvailableUpdate is not null);
        ResetReferenceSelectionCommand = new RelayCommand(ResetReferenceSelection, () => HasSelectedReferences);
        PreviousCommand = new RelayCommand(() => MoveSelection(-1), () => CanMovePrevious);
        NextCommand = new RelayCommand(() => MoveSelection(1), () => CanMoveNext);

        foreach (var spectrum in LoadReferenceSpectra())
        {
            var item = new ReferenceOptionItem(spectrum);
            item.PropertyChanged += HandleReferenceSelectionChanged;
            ReferenceOptions.Add(item);
        }

        PlotPlaceholderText = "Spectrum plot will render here in the Windows UI layer.";
        _transientMessageTimer.Tick += HandleTransientMessageTimerTick;
        _ = CheckForUpdatesAsync(false);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public ObservableCollection<SampleItem> Samples { get; } = new();
    public ReadOnlyObservableCollection<SampleItem> SelectedSamples { get; }
    public ObservableCollection<SummaryItem> SummaryItems { get; } = new();
    public ObservableCollection<ReferenceOptionItem> ReferenceOptions { get; } = new();

    public ObservableCollection<SpectrumSeriesItem> PlotSeries
    {
        get => _plotSeries;
        private set => SetProperty(ref _plotSeries, value);
    }

    public ICommand ImportCommand { get; }
    public ICommand ExportCommand { get; }
    public ICommand CheckUpdatesCommand { get; }
    public ICommand DownloadUpdateCommand { get; }
    public ICommand ResetReferenceSelectionCommand { get; }
    public ICommand PreviousCommand { get; }
    public ICommand NextCommand { get; }

    public IReadOnlyList<ReferenceNormalizationMode> NormalizationModes { get; } =
        Enum.GetValues<ReferenceNormalizationMode>();

    public string FileName
    {
        get => _fileName;
        private set => SetProperty(ref _fileName, value);
    }

    public string StatusMessage
    {
        get => _statusMessage;
        private set => SetProperty(ref _statusMessage, value);
    }

    public string PlotPlaceholderText { get; private set; }

    public string? UpdateStatusMessage
    {
        get => _updateStatusMessage;
        private set => SetProperty(ref _updateStatusMessage, value);
    }

    public string CurrentVersion => GitHubUpdateService.CurrentVersion;

    public string? LatestVersion
    {
        get => _latestVersion;
        private set => SetProperty(ref _latestVersion, value);
    }

    public bool HasAvailableUpdate => AvailableUpdate is not null;

    public AppUpdateInfo? AvailableUpdate
    {
        get => _availableUpdate;
        private set
        {
            if (SetProperty(ref _availableUpdate, value))
            {
                OnPropertyChanged(nameof(HasAvailableUpdate));
                ((RelayCommand)DownloadUpdateCommand).RaiseCanExecuteChanged();
            }
        }
    }

    public ReferenceNormalizationMode SelectedNormalizationMode
    {
        get => _selectedNormalizationMode;
        set
        {
            if (!SetProperty(ref _selectedNormalizationMode, value))
            {
                return;
            }

            RefreshSelectionState();
        }
    }

    public bool HasWorksheet => _worksheet is not null;
    public bool HasEditedChanges
    {
        get => _hasEditedChanges;
        private set => SetProperty(ref _hasEditedChanges, value);
    }
    public bool HasMultipleSelectedSamples => _selectedSamples.Count > 1;
    public bool CanMovePrevious => SelectedSample is not null && SamplePosition(SelectedSample) > 0;
    public bool CanMoveNext => SelectedSample is not null && SamplePosition(SelectedSample) < Samples.Count - 1;
    public bool HasSelectedReferences => ReferenceOptions.Any(item => item.IsSelected);
    public bool CanEditSelectedSample => SelectedSample is not null && _editableDocument is not null;

    public SampleItem? SelectedSample
    {
        get => _selectedSample;
        set
        {
            if (!SetProperty(ref _selectedSample, value))
            {
                return;
            }

            RefreshSelectionState();
        }
    }

    public void LoadFile(string filePath)
    {
        var bytes = File.ReadAllBytes(filePath);
        _editableDocument = TbwkEditableDocument.Parse(bytes);
        var worksheet = _editableDocument.Worksheet();
        _currentFilePath = filePath;
        HasEditedChanges = false;
        LoadWorksheet(worksheet);
        FileName = Path.GetFileName(filePath);
        StatusMessage = $"Loaded {worksheet.Measurements.Count} samples. Reference spectra: {ReferenceOptions.Count}.";
        RaiseCommandStates();
    }

    public void RenameSelectedSample(string newName)
    {
        if (_editableDocument is null || SelectedSample is null)
        {
            return;
        }

        _editableDocument.RenameMeasurement(SelectedSample.Index, newName);
        HasEditedChanges = true;
        ReloadEditedWorksheet(SelectedSample.Index);
    }

    public void DeleteSelectedSample()
    {
        if (_editableDocument is null || SelectedSample is null)
        {
            return;
        }

        var deletedIndex = SelectedSample.Index;
        _editableDocument.DeleteMeasurement(deletedIndex);
        HasEditedChanges = true;
        ReloadEditedWorksheet(Math.Max(0, deletedIndex - 1));
    }

    public string SuggestedEditedFilePath()
    {
        var currentPath = _currentFilePath ?? "worksheet.twbk";
        var directory = Path.GetDirectoryName(currentPath) ?? string.Empty;
        var fileName = Path.GetFileNameWithoutExtension(currentPath);
        var extension = Path.GetExtension(currentPath);
        return Path.Combine(directory, $"{fileName}_edited{extension}");
    }

    public void SaveEditedCopy(string destinationPath)
    {
        if (!HasEditedChanges || _editableDocument is null)
        {
            return;
        }

        _editableDocument.Save(destinationPath);
        _currentFilePath = destinationPath;
        FileName = Path.GetFileName(destinationPath);
        HasEditedChanges = false;
        StatusMessage = $"Saved edited file to {destinationPath}";
        ScheduleTransientMessageClear(statusMessage: StatusMessage, updateMessage: null);
    }

    public void UpdateSelectedSamples(IReadOnlyList<SampleItem> selectedSamples)
    {
        _selectedSamples.Clear();
        foreach (var sample in selectedSamples
                     .Distinct()
                     .OrderBy(SamplePosition))
        {
            _selectedSamples.Add(sample);
        }

        if (_selectedSamples.Count == 0 && SelectedSample is not null)
        {
            _selectedSamples.Add(SelectedSample);
        }

        if (_selectedSamples.Count > 0 &&
            (SelectedSample is null || !_selectedSamples.Contains(SelectedSample)))
        {
            _selectedSample = _selectedSamples[0];
            OnPropertyChanged(nameof(SelectedSample));
        }

        RefreshSelectionState();
        OnPropertyChanged(nameof(HasMultipleSelectedSamples));
    }

    public void TryLoadFile(string filePath)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(filePath) || !File.Exists(filePath))
            {
                StatusMessage = "TBWK file not found.";
                return;
            }

            var extension = Path.GetExtension(filePath);
            if (!string.Equals(extension, ".tbwk", StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(extension, ".twbk", StringComparison.OrdinalIgnoreCase))
            {
                StatusMessage = $"Unsupported file type: {extension}";
                return;
            }

            LoadFile(filePath);
        }
        catch (Exception ex)
        {
            StatusMessage = ex.Message;
        }
    }

    private void ImportFile()
    {
        var dialog = new OpenFileDialog
        {
            CheckFileExists = true,
            Multiselect = false,
            Filter = "TBWK files (*.tbwk;*.twbk)|*.tbwk;*.twbk|All files (*.*)|*.*"
        };

        if (dialog.ShowDialog() != true)
        {
            return;
        }

        TryLoadFile(dialog.FileName);
    }

    private void ExportFiles()
    {
        if (_worksheet is null || string.IsNullOrWhiteSpace(_currentFilePath))
        {
            StatusMessage = "No TBWK file is loaded.";
            return;
        }

        try
        {
            var dialog = new OpenFolderDialog
            {
                Title = "Choose export folder",
                InitialDirectory = Path.GetDirectoryName(_currentFilePath),
                Multiselect = false
            };

            if (dialog.ShowDialog() != true || string.IsNullOrWhiteSpace(dialog.FolderName))
            {
                StatusMessage = "Export cancelled.";
                return;
            }

            var result = WorksheetExporter.Export(_worksheet, _currentFilePath, dialog.FolderName);
            StatusMessage = $"Exported CSV and PDF to {result.OutputDirectory}";
            ScheduleTransientMessageClear(statusMessage: StatusMessage, updateMessage: null);
        }
        catch (Exception ex)
        {
            StatusMessage = ex.Message;
        }
    }

    private async Task CheckForUpdatesAsync(bool showNoUpdateMessage)
    {
        if (!showNoUpdateMessage && _hasCheckedForUpdates)
        {
            return;
        }

        if (!showNoUpdateMessage)
        {
            _hasCheckedForUpdates = true;
        }

        try
        {
            var result = await GitHubUpdateService.CheckForUpdatesAsync().ConfigureAwait(false);
            await App.Current.Dispatcher.InvokeAsync(() =>
            {
                LatestVersion = result.LatestVersion;
                if (result.Update is null)
                {
                    if (showNoUpdateMessage)
                    {
                        AvailableUpdate = null;
                        UpdateStatusMessage = "You are up to date.";
                        ScheduleTransientMessageClear(statusMessage: null, updateMessage: UpdateStatusMessage);
                    }
                    return;
                }

                AvailableUpdate = result.Update;
                UpdateStatusMessage = $"Update {result.Update.Version} is available.";
            });
        }
        catch
        {
            if (showNoUpdateMessage)
            {
                await App.Current.Dispatcher.InvokeAsync(() =>
                {
                    UpdateStatusMessage = "Unable to check for updates.";
                    ScheduleTransientMessageClear(statusMessage: null, updateMessage: UpdateStatusMessage);
                });
            }
        }
    }

    private void DownloadUpdate()
    {
        if (AvailableUpdate is null)
        {
            return;
        }

        GitHubUpdateService.OpenDownload(AvailableUpdate.DownloadUrl);
    }

    private void ResetReferenceSelection()
    {
        var changed = false;
        foreach (var option in ReferenceOptions.Where(option => option.IsSelected))
        {
            option.IsSelected = false;
            changed = true;
        }

        if (changed)
        {
            RefreshSelectionState();
        }
    }

    private void ScheduleTransientMessageClear(string? statusMessage, string? updateMessage)
    {
        _pendingStatusMessageToClear = statusMessage;
        _pendingUpdateMessageToClear = updateMessage;
        _transientMessageTimer.Stop();
        _transientMessageTimer.Start();
    }

    private void HandleTransientMessageTimerTick(object? sender, EventArgs e)
    {
        _transientMessageTimer.Stop();

        if (!string.IsNullOrEmpty(_pendingStatusMessageToClear) && StatusMessage == _pendingStatusMessageToClear)
        {
            StatusMessage = HasWorksheet ? $"Loaded {Samples.Count} samples. Reference spectra: {ReferenceOptions.Count}." : "Import a TBWK file to begin.";
        }

        if (!string.IsNullOrEmpty(_pendingUpdateMessageToClear) && UpdateStatusMessage == _pendingUpdateMessageToClear && AvailableUpdate is null)
        {
            UpdateStatusMessage = null;
        }

        _pendingStatusMessageToClear = null;
        _pendingUpdateMessageToClear = null;
    }

    private void MoveSelection(int delta)
    {
        if (SelectedSample is null)
        {
            return;
        }

        var currentPosition = SamplePosition(SelectedSample);
        var nextPosition = Math.Max(0, Math.Min(Samples.Count - 1, currentPosition + delta));
        SelectedSample = Samples[nextPosition];
        UpdateSelectedSamples(new[] { SelectedSample });
    }

    private int SamplePosition(SampleItem sample)
    {
        return Samples.IndexOf(sample);
    }

    private void RefreshSelectionState()
    {
        SummaryItems.Clear();
        if (SelectedSample is null)
        {
            PlotSeries = new ObservableCollection<SpectrumSeriesItem>();
            PlotPlaceholderText = "Spectrum plot will render here in the Windows UI layer.";
            OnPropertyChanged(nameof(PlotPlaceholderText));
        RaiseCommandStates();
        OnPropertyChanged(nameof(CanMovePrevious));
        OnPropertyChanged(nameof(CanMoveNext));
        OnPropertyChanged(nameof(HasSelectedReferences));
        OnPropertyChanged(nameof(CanEditSelectedSample));
        return;
        }

        foreach (var item in BuildSummaryItems(SelectedSample.Measurement))
        {
            SummaryItems.Add(item);
        }

        var plotSeries = new ObservableCollection<SpectrumSeriesItem>();
        var samplesToPlot = _selectedSamples.Count == 0 ? new[] { SelectedSample } : _selectedSamples.ToArray();
        foreach (var sample in samplesToPlot.Distinct())
        {
            plotSeries.Add(BuildSampleSeries(sample.Measurement, sample.Index));
        }

        AddReferenceSeries(SelectedSample.Measurement, plotSeries);

        PlotSeries = plotSeries;

        PlotPlaceholderText =
            PlotSeries.Count == 0
                ? "Spectrum plot will render here in the Windows UI layer."
                : $"Showing {PlotSeries.Count} series. Normalization: {SelectedNormalizationMode}.";
        OnPropertyChanged(nameof(PlotPlaceholderText));
        RaiseCommandStates();
        OnPropertyChanged(nameof(CanMovePrevious));
        OnPropertyChanged(nameof(CanMoveNext));
        OnPropertyChanged(nameof(HasSelectedReferences));
        OnPropertyChanged(nameof(HasMultipleSelectedSamples));
        OnPropertyChanged(nameof(CanEditSelectedSample));
    }

    private void LoadWorksheet(Worksheet worksheet)
    {
        _worksheet = worksheet;
        Samples.Clear();
        _selectedSamples.Clear();
        var ordered = worksheet.Measurements
            .Select((measurement, index) => new { Index = index, Measurement = measurement })
            .OrderBy(item => item.Measurement.Time)
            .ThenBy(item => item.Index)
            .ToArray();

        for (var displayOrder = 0; displayOrder < ordered.Length; displayOrder++)
        {
            Samples.Add(new SampleItem(ordered[displayOrder].Index, displayOrder, ordered[displayOrder].Measurement));
        }

        SelectedSample = Samples.FirstOrDefault();
        UpdateSelectedSamples(SelectedSample is null ? Array.Empty<SampleItem>() : new[] { SelectedSample });
        OnPropertyChanged(nameof(HasWorksheet));
    }

    private void ReloadEditedWorksheet(int preferredOriginalIndex)
    {
        if (_editableDocument is null)
        {
            return;
        }

        var worksheet = _editableDocument.Worksheet();
        LoadWorksheet(worksheet);
        SelectedSample = Samples.FirstOrDefault(sample => sample.Index == preferredOriginalIndex) ?? Samples.FirstOrDefault();
        UpdateSelectedSamples(SelectedSample is null ? Array.Empty<SampleItem>() : new[] { SelectedSample });
        StatusMessage = $"Edited worksheet has {worksheet.Measurements.Count} samples.";
        ScheduleTransientMessageClear(statusMessage: StatusMessage, updateMessage: null);
    }

    private static IReadOnlyList<ReferenceSpectrum> LoadReferenceSpectra()
    {
        var baseDir = AppDomain.CurrentDomain.BaseDirectory;
        var candidatePaths = new[]
        {
            Path.Combine(baseDir, "Assets", "reference_spectra"),
            Path.GetFullPath(Path.Combine(baseDir, "..", "..", "..", "..", "..", "..", "spectrum_database"))
        };

        foreach (var path in candidatePaths)
        {
            if (Directory.Exists(path))
            {
                return ReferenceSpectrumLibrary.LoadFromDirectory(path)
                    .Where(spectrum => !IsDisabledReference(spectrum.Id))
                    .ToArray();
            }
        }

        return ReferenceSpectrumLibrary.LoadFromAssembly(typeof(MainWindowViewModel).Assembly)
            .Where(spectrum => !IsDisabledReference(spectrum.Id))
            .ToArray();
    }

    private static bool IsDisabledReference(string id)
    {
        return string.Equals(id, "dsDNA", StringComparison.OrdinalIgnoreCase)
            || string.Equals(id, "RNA", StringComparison.OrdinalIgnoreCase);
    }

    private static IEnumerable<SummaryItem> BuildSummaryItems(Measurement measurement)
    {
        var items = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["Sample"] = measurement.Title,
            ["Time"] = measurement.Time.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture)
        };

        foreach (var entry in measurement.Properties.Properties.OrderBy(pair => pair.Key, StringComparer.Ordinal))
        {
            var unit = string.IsNullOrWhiteSpace(entry.Value.Value.Unit) ? string.Empty : $" {entry.Value.Value.Unit}";
            items[entry.Key] = $"{entry.Value.Value.Value:0.00}{unit}";
        }

        var preferredOrder = new[]
        {
            "Sample",
            "Nucleic Acid",
            "260/280",
            "260/230",
            "A260",
            "A280",
            "Time"
        };

        foreach (var key in preferredOrder)
        {
            if (items.Remove(key, out var value))
            {
                yield return new SummaryItem(key, value);
            }
        }

        foreach (var entry in items.OrderBy(pair => pair.Key, StringComparer.Ordinal))
        {
            yield return new SummaryItem(entry.Key, entry.Value);
        }
    }

    private void AddReferenceSeries(
        Measurement measurement,
        ICollection<SpectrumSeriesItem> plotSeries)
    {
        foreach (var reference in ReferenceOptions.Where(item => item.IsSelected).Select(item => item.Spectrum))
        {
            var normalized = ReferenceNormalization.Normalize(reference, measurement, SelectedNormalizationMode);
            if (normalized.Count == 0)
            {
                continue;
            }

            plotSeries.Add(new SpectrumSeriesItem(
                reference.ShortTitle,
                normalized.Select(point => new SpectrumPoint(point.X, point.Y)).ToArray(),
                ReferenceColor(reference.Id),
                true,
                true
            ));
        }
    }

    private static SpectrumSeriesItem BuildSampleSeries(Measurement measurement, int index)
    {
        return new SpectrumSeriesItem(
            measurement.Title,
            measurement.XValues.Zip(measurement.YValues, (x, y) => new SpectrumPoint(x, y)).ToArray(),
            SeriesColor(index),
            false,
            false
        );
    }

    private void HandleReferenceSelectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (!string.Equals(e.PropertyName, nameof(ReferenceOptionItem.IsSelected), StringComparison.Ordinal))
        {
            return;
        }

        RefreshSelectionState();
    }

    private bool SetProperty<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        OnPropertyChanged(propertyName);
        return true;
    }

    private void RaiseCommandStates()
    {
        ((RelayCommand)ExportCommand).RaiseCanExecuteChanged();
        ((RelayCommand)ResetReferenceSelectionCommand).RaiseCanExecuteChanged();
        ((RelayCommand)PreviousCommand).RaiseCanExecuteChanged();
        ((RelayCommand)NextCommand).RaiseCanExecuteChanged();
        OnPropertyChanged(nameof(CanEditSelectedSample));
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }

    private static Color SeriesColor(int index)
    {
        var palette = new[]
        {
            Color.FromRgb(0x0F, 0x6C, 0xBD),
            Colors.Red,
            Colors.Green,
            Colors.Orange,
            Colors.Purple,
            Colors.DeepPink,
            Colors.Teal,
            Colors.Brown
        };
        return palette[Math.Abs(index) % palette.Length];
    }

    private static Color ReferenceColor(string id)
    {
        return id switch
        {
            "phenol" => Color.FromRgb(0x92, 0x40, 0x0E),
            "guanidine_hydrochloride_GuHCl" => Color.FromRgb(0x04, 0x78, 0x57),
            "guanidine_thiocyanate_GTC" => Color.FromRgb(0x7C, 0x3A, 0xED),
            "protein_BSA" => Color.FromRgb(0xB4, 0x23, 0x18),
            "EDTA" => Color.FromRgb(0x0E, 0x74, 0x90),
            "ethanol" => Color.FromRgb(0xEA, 0x58, 0x0C),
            "dsDNA" => Color.FromRgb(0x1D, 0x4E, 0xD8),
            "RNA" => Color.FromRgb(0xBE, 0x18, 0x5D),
            _ => Color.FromRgb(0x47, 0x54, 0x67)
        };
    }
}

public sealed record SampleItem(int Index, int DisplayOrder, Measurement Measurement)
{
    public string DisplayName => $"#{DisplayOrder + 1} {Measurement.Title}";
}

public sealed record SummaryItem(string Label, string Value);

public sealed class ReferenceOptionItem : INotifyPropertyChanged
{
    private bool _isSelected;

    public ReferenceOptionItem(ReferenceSpectrum spectrum)
    {
        Spectrum = spectrum;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public ReferenceSpectrum Spectrum { get; }

    public string ShortTitle => Spectrum.ShortTitle;
    public string FullTitle => Spectrum.Title;

    public bool IsSelected
    {
        get => _isSelected;
        set
        {
            if (_isSelected == value)
            {
                return;
            }

            _isSelected = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsSelected)));
        }
    }
}
