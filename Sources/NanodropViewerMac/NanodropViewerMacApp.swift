import SwiftUI

@main
struct NanodropViewerMacApp: App {
    @StateObject private var viewModel = ViewerViewModel()

    var body: some Scene {
        WindowGroup("nanodrop 2000 viewer") {
            ViewerRootView(viewModel: viewModel)
        }
        .defaultSize(width: 1440, height: 900)
    }
}
