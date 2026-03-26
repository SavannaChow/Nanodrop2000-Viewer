using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Linq;
using Microsoft.Win32;

namespace NanodropViewer.App;

public partial class MainWindow : Window
{
    public MainWindow(MainWindowViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
    }

    private MainWindowViewModel ViewModel => (MainWindowViewModel)DataContext;

    private void HandleDragOver(object sender, DragEventArgs e)
    {
        e.Effects = TryGetDroppedFile(e.Data) is null ? DragDropEffects.None : DragDropEffects.Copy;
        e.Handled = true;
    }

    private void HandleDrop(object sender, DragEventArgs e)
    {
        var filePath = TryGetDroppedFile(e.Data);
        if (filePath is null)
        {
            return;
        }

        ViewModel.TryLoadFile(filePath);
    }

    private void HandleInfoClick(object sender, RoutedEventArgs e)
    {
        var infoWindow = new InfoWindow
        {
            Owner = this,
            WindowStartupLocation = WindowStartupLocation.CenterOwner
        };
        infoWindow.ShowDialog();
    }

    private void HandleSamplesSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (sender is not ListBox listBox)
        {
            return;
        }

        ViewModel.UpdateSelectedSamples(listBox.SelectedItems.Cast<SampleItem>().ToArray());
    }

    private void HandleSamplePreviewMouseRightButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem listBoxItem || listBoxItem.DataContext is not SampleItem sampleItem)
        {
            return;
        }

        SamplesListBox.SelectedItem = sampleItem;
        ViewModel.UpdateSelectedSamples(new[] { sampleItem });
    }

    private void HandleRenameSelectedSampleClick(object sender, RoutedEventArgs e)
    {
        if (ViewModel.SelectedSample is null)
        {
            return;
        }

        var dialog = new RenameSampleWindow(ViewModel.SelectedSample.Measurement.Title)
        {
            Owner = this,
            WindowStartupLocation = WindowStartupLocation.CenterOwner
        };

        if (dialog.ShowDialog() != true)
        {
            return;
        }

        ViewModel.RenameSelectedSample(dialog.SampleName);
    }

    private void HandleDeleteSelectedSampleClick(object sender, RoutedEventArgs e)
    {
        if (ViewModel.SelectedSample is null)
        {
            return;
        }

        var result = MessageBox.Show(
            this,
            $"Remove \"{ViewModel.SelectedSample.Measurement.Title}\" from the edited copy? The original TBWK file will not be overwritten.",
            "Delete sample?",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Warning);

        if (result != MessageBoxResult.OK)
        {
            return;
        }

        ViewModel.DeleteSelectedSample();
    }

    private void HandleSaveEditedClick(object sender, RoutedEventArgs e)
    {
        var dialog = new SaveFileDialog
        {
            Title = "Save edited TBWK file",
            OverwritePrompt = true,
            FileName = Path.GetFileName(ViewModel.SuggestedEditedFilePath()),
            InitialDirectory = Path.GetDirectoryName(ViewModel.SuggestedEditedFilePath()),
            Filter = "TBWK files (*.tbwk;*.twbk)|*.tbwk;*.twbk|All files (*.*)|*.*"
        };

        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        ViewModel.SaveEditedCopy(dialog.FileName);
    }

    private static string? TryGetDroppedFile(IDataObject data)
    {
        if (!data.GetDataPresent(DataFormats.FileDrop))
        {
            return null;
        }

        if (data.GetData(DataFormats.FileDrop) is not string[] paths)
        {
            return null;
        }

        foreach (var path in paths)
        {
            if (File.Exists(path) && string.Equals(Path.GetExtension(path), ".tbwk", StringComparison.OrdinalIgnoreCase))
            {
                return path;
            }
        }

        return null;
    }
}
