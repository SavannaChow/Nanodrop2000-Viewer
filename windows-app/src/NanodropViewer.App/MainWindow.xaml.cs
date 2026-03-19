using System;
using System.IO;
using System.Windows;
using System.Windows.Input;

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
