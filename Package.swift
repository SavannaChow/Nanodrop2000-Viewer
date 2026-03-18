// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "TBWKConverter",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .library(name: "TBWKCore", targets: ["TBWKCore"]),
        .executable(name: "tbwk-convert", targets: ["tbwk-convert"]),
    ],
    targets: [
        .target(
            name: "TBWKCore",
            path: "Sources/TBWKCore"
        ),
        .executableTarget(
            name: "tbwk-convert",
            dependencies: ["TBWKCore"],
            path: "Sources/tbwk-convert"
        ),
        .testTarget(
            name: "TBWKCoreTests",
            dependencies: ["TBWKCore"],
            path: "Tests/TBWKCoreTests"
        ),
    ]
)
