import SwiftUI
import AppIntents

@main
struct OSTIEApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene { WindowGroup { RootView().environmentObject(model) } }
}
struct OpenOSTIEIntent: AppIntent {
    static var title: LocalizedStringResource = "Abrir OSTIE"
    static var description = IntentDescription("Abra seu assistente para conversar.")
    static var openAppWhenRun = true
    func perform() async throws -> some IntentResult { .result() }
}
struct OSTIEShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: OpenOSTIEIntent(), phrases: ["Abrir \(.applicationName)", "Conversar com \(.applicationName)"], shortTitle: "Abrir OSTIE", systemImageName: "waveform")
    }
}
