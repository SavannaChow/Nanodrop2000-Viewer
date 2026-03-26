using System;
using System.Diagnostics;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;

namespace NanodropViewer.App;

public sealed record AppUpdateInfo(string Version, string Notes, string DownloadUrl);
public sealed record UpdateCheckResult(string LatestVersion, AppUpdateInfo? Update);

internal static class GitHubUpdateService
{
    private const string LatestReleaseUrl = "https://api.github.com/repos/SavannaChow/Nanodrop2000-Viewer/releases/latest";

    public static string CurrentVersion =>
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

    public static async Task<UpdateCheckResult> CheckForUpdatesAsync()
    {
        using var client = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(8)
        };
        client.DefaultRequestHeaders.Add("Accept", "application/vnd.github+json");
        client.DefaultRequestHeaders.Add("User-Agent", "nanodrop-2000-viewer-windows");

        using var response = await client.GetAsync(LatestReleaseUrl).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        await using var stream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false);
        var release = await JsonSerializer.DeserializeAsync<GitHubReleaseResponse>(stream).ConfigureAwait(false);
        if (release is null)
        {
            throw new InvalidOperationException("GitHub latest release response was empty.");
        }

        var latestVersion = NormalizeVersion(release.TagName);
        if (!IsVersionNewer(latestVersion, NormalizeVersion(CurrentVersion)))
        {
            return new UpdateCheckResult(latestVersion, null);
        }

        var asset = release.Assets.FirstOrDefault(asset =>
            asset.Name.Contains("windows", StringComparison.OrdinalIgnoreCase) &&
            (asset.Name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase) ||
             asset.Name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)));

        if (asset is null)
        {
            return new UpdateCheckResult(latestVersion, null);
        }

        return new UpdateCheckResult(
            latestVersion,
            new AppUpdateInfo(latestVersion, release.Body?.Trim() ?? string.Empty, asset.BrowserDownloadUrl)
        );
    }

    public static void OpenDownload(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }

    private static string NormalizeVersion(string raw)
    {
        return raw.Trim().TrimStart('v', 'V');
    }

    private static bool IsVersionNewer(string latest, string current)
    {
        var left = latest.Split('.').Select(ParsePart).ToArray();
        var right = current.Split('.').Select(ParsePart).ToArray();
        var count = Math.Max(left.Length, right.Length);
        for (var index = 0; index < count; index++)
        {
            var l = index < left.Length ? left[index] : 0;
            var r = index < right.Length ? right[index] : 0;
            if (l != r)
            {
                return l > r;
            }
        }

        return false;
    }

    private static int ParsePart(string value) => int.TryParse(value, out var parsed) ? parsed : 0;

    private sealed record GitHubReleaseResponse(string TagName, string? Body, GitHubReleaseAsset[] Assets)
    {
        public string TagName { get; init; } = TagName;
        public string? Body { get; init; } = Body;
        public GitHubReleaseAsset[] Assets { get; init; } = Assets;
    }

    private sealed record GitHubReleaseAsset(string Name, string BrowserDownloadUrl)
    {
        public string Name { get; init; } = Name;
        public string BrowserDownloadUrl { get; init; } = BrowserDownloadUrl;
    }
}
