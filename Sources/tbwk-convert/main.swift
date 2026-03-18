import Foundation
import TBWKCore

struct CLI {
    static func run() -> Int32 {
        do {
            let arguments = Array(CommandLine.arguments.dropFirst())
            if arguments.isEmpty || arguments.contains("--help") || arguments.contains("-h") {
                printUsage()
                return arguments.isEmpty ? 64 : 0
            }

            let parsed = try parse(arguments: arguments)

            for inputURL in parsed.inputFiles {
                let result = try TBWKExporter.export(
                    fileURL: inputURL,
                    outputDirectory: parsed.outputDirectory,
                    baseName: parsed.baseName
                )
                print("Input file: \(inputURL.path)")
                print("Summary CSV: \(result.summaryURL.path)")
                print("Spectrum CSV: \(result.spectrumURL.path)")
                print("Spectra PDF: \(result.pdfURL.path)")
            }

            return 0
        } catch {
            fputs("Error: \(error.localizedDescription)\n", stderr)
            return 1
        }
    }

    private static func parse(arguments: [String]) throws -> (inputFiles: [URL], outputDirectory: URL?, baseName: String?) {
        var outputDirectory: URL?
        var baseName: String?
        var inputFiles: [URL] = []
        var index = 0

        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--output-dir":
                index += 1
                guard index < arguments.count else {
                    throw CLIError.invalidArguments("--output-dir requires a value.")
                }
                outputDirectory = URL(fileURLWithPath: arguments[index], isDirectory: true)
            case "--base-name":
                index += 1
                guard index < arguments.count else {
                    throw CLIError.invalidArguments("--base-name requires a value.")
                }
                baseName = arguments[index]
            default:
                inputFiles.append(URL(fileURLWithPath: argument))
            }
            index += 1
        }

        if inputFiles.isEmpty {
            throw CLIError.invalidArguments("At least one .tbwk file is required.")
        }

        if inputFiles.count > 1, baseName != nil {
            throw CLIError.invalidArguments("--base-name can only be used with a single input file.")
        }

        return (inputFiles, outputDirectory, baseName)
    }

    private static func printUsage() {
        let usage = """
        Usage:
          tbwk-convert <file.tbwk> [--output-dir <dir>] [--base-name <name>]
          tbwk-convert <file1.tbwk> <file2.tbwk> ...

        Converts TBWK files into:
          - summary CSV
          - spectrum CSV
          - merged spectra PDF
        """
        print(usage)
    }
}

enum CLIError: LocalizedError {
    case invalidArguments(String)

    var errorDescription: String? {
        switch self {
        case .invalidArguments(let message):
            return message
        }
    }
}

exit(CLI.run())
