// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Clipway",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "BridgeCore"),
        .executableTarget(name: "Clipway", dependencies: ["BridgeCore"]),
        .executableTarget(name: "ReleaseTool", dependencies: ["BridgeCore"]),
        .testTarget(name: "BridgeCoreTests", dependencies: ["BridgeCore"]),
    ],
    swiftLanguageModes: [.v5]
)
