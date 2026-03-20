import AppKit
import Foundation

struct AppUpdateInfo: Equatable {
    let version: String
    let notes: String
    let downloadURL: URL
}

private struct GitHubReleaseResponse: Decodable {
    struct Asset: Decodable {
        let name: String
        let browserDownloadURL: URL

        enum CodingKeys: String, CodingKey {
            case name
            case browserDownloadURL = "browser_download_url"
        }
    }

    let tagName: String
    let body: String?
    let assets: [Asset]

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case body
        case assets
    }
}

enum UpdateService {
    private static let latestReleaseURL = URL(string: "https://api.github.com/repos/SavannaChow/Nanodrop2000-Viewer/releases/latest")!

    static func currentVersion() -> String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
    }

    static func checkForUpdate() async throws -> AppUpdateInfo? {
        var request = URLRequest(url: latestReleaseURL)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("nanodrop-2000-viewer-macos", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 8

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }

        let release = try JSONDecoder().decode(GitHubReleaseResponse.self, from: data)
        let latestVersion = normalizedVersion(release.tagName)
        guard isVersion(latestVersion, newerThan: normalizedVersion(currentVersion())) else {
            return nil
        }

        guard let asset = release.assets.first(where: {
            let name = $0.name.lowercased()
            return name.contains("macos") && name.hasSuffix(".dmg")
        }) else {
            return nil
        }

        return AppUpdateInfo(
            version: latestVersion,
            notes: (release.body ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
            downloadURL: asset.browserDownloadURL
        )
    }

    static func openDownloadURL(_ url: URL) {
        NSWorkspace.shared.open(url)
    }

    private static func normalizedVersion(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"^[vV]"#, with: "", options: .regularExpression)
    }

    private static func isVersion(_ lhs: String, newerThan rhs: String) -> Bool {
        let left = lhs.split(separator: ".").map { Int($0) ?? 0 }
        let right = rhs.split(separator: ".").map { Int($0) ?? 0 }
        let count = max(left.count, right.count)
        for index in 0..<count {
            let l = index < left.count ? left[index] : 0
            let r = index < right.count ? right[index] : 0
            if l != r {
                return l > r
            }
        }
        return false
    }
}
