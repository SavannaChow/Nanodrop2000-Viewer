import Foundation

enum ReferenceNormalizationMode: String, CaseIterable, Identifiable {
    case peakNormalize = "Peak Normalize"
    case areaNormalize = "Area Normalize"
    case fitToSample = "Fit To Sample"

    var id: String { rawValue }
}

struct ReferenceSpectrum: Identifiable, Hashable {
    let id: String
    let shortTitle: String
    let title: String
    let xValues: [Double]
    let yValues: [Double]
    let xUnits: String
    let yUnits: String
}

enum ReferenceSpectrumLibrary {
    static func loadBundledSpectra() -> [ReferenceSpectrum] {
        guard let resourceURL = Bundle.module.resourceURL else {
            return []
        }

        let fileManager = FileManager.default
        let enumerator = fileManager.enumerator(
            at: resourceURL,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )

        let urls = (enumerator?.allObjects as? [URL]) ?? []

        return urls
            .filter { $0.pathExtension.lowercased() == "jdx" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .compactMap { url in
                let fallbackID = url.deletingPathExtension().lastPathComponent
                return try? JCAMPDXParser.parse(url: url, fallbackID: fallbackID)
            }
    }
}

enum JCAMPDXParser {
    static func parse(url: URL, fallbackID: String) throws -> ReferenceSpectrum {
        let raw = try String(contentsOf: url, encoding: .utf8)
        let lines = raw.components(separatedBy: .newlines)

        var title = fallbackID.capitalized
        var xUnits = "Wavelength (nm)"
        var yUnits = "Normalized reference"
        var xValues: [Double] = []
        var yValues: [Double] = []
        var inXYSection = false

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }

            if trimmed.hasPrefix("##TITLE=") {
                title = String(trimmed.dropFirst("##TITLE=".count))
                continue
            }
            if trimmed.hasPrefix("##XUNITS=") {
                xUnits = String(trimmed.dropFirst("##XUNITS=".count))
                continue
            }
            if trimmed.hasPrefix("##YUNITS=") {
                yUnits = String(trimmed.dropFirst("##YUNITS=".count))
                continue
            }
            if trimmed.hasPrefix("##XYPOINTS=") {
                inXYSection = true
                continue
            }
            if trimmed.hasPrefix("##END=") {
                break
            }

            guard inXYSection else { continue }

            let parts = trimmed.split(separator: ",").map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            guard parts.count >= 2,
                  let x = Double(parts[0]),
                  let y = Double(parts[1]) else {
                continue
            }
            xValues.append(x)
            yValues.append(y)
        }

        guard !xValues.isEmpty, xValues.count == yValues.count else {
            throw NSError(domain: "JCAMPDXParser", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Could not parse \(url.lastPathComponent)."
            ])
        }

        return ReferenceSpectrum(
            id: fallbackID,
            shortTitle: shortTitle(for: fallbackID),
            title: title,
            xValues: xValues,
            yValues: yValues,
            xUnits: xUnits,
            yUnits: yUnits
        )
    }

    private static func shortTitle(for id: String) -> String {
        switch id {
        case "dsDNA":
            return "DNA"
        case "RNA":
            return "RNA"
        case "guanidine_hydrochloride_GuHCl":
            return "GuHCl"
        case "guanidine_thiocyanate_GTC":
            return "GTC"
        case "protein_BSA":
            return "BSA"
        case "phenol":
            return "Phenol"
        case "ethanol":
            return "Ethanol"
        case "EDTA":
            return "EDTA"
        default:
            return id
        }
    }
}
