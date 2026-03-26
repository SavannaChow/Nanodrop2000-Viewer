using System.Windows;

namespace NanodropViewer.App;

public partial class RenameSampleWindow : Window
{
    public RenameSampleWindow(string currentName)
    {
        InitializeComponent();
        SampleNameTextBox.Text = currentName;
        SampleNameTextBox.SelectAll();
        SampleNameTextBox.Focus();
    }

    public string SampleName => SampleNameTextBox.Text.Trim();

    private void HandleRenameClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(SampleName))
        {
            MessageBox.Show(this, "Sample name cannot be empty.", "Rename sample", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        DialogResult = true;
    }
}
