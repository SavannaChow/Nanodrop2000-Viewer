import SwiftUI

@main
struct NanodropViewerMacApp: App {
    @StateObject private var viewModel = ViewerViewModel()

    var body: some Scene {
        WindowGroup("nanodrop 2000 viewer") {
            ViewerRootView(viewModel: viewModel)
                .onOpenURL { url in
                    guard url.isFileURL else { return }
                    viewModel.load(fileURL: url)
                }
        }
        .defaultSize(width: 1440, height: 900)
    }
}
