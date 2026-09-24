import SwiftUI
import WebKit
import UniformTypeIdentifiers

struct PlainDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self) }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}
struct WritingView: View {
    @EnvironmentObject var model: AppModel
    @State private var preview = false
    @State private var clear = false
    @State private var export = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if model.codeBusy { HStack { ProgressView(); Text("Escrevendo…"); Spacer(); Button("Cancelar") { model.cancelCode() } }.padding() }
                TextEditor(text: $model.document).font(.system(.body, design: .monospaced)).padding(8).accessibilityLabel("Documento editável").disabled(model.codeBusy)
                HStack(spacing: 20) {
                    Button("Play", systemImage: "play.fill") { preview = true }.disabled(model.document.isEmpty)
                    Button("Copiar", systemImage: "doc.on.doc") { UIPasteboard.general.string = model.document }
                    Button("Exportar", systemImage: "square.and.arrow.up") { export = true }
                    Spacer()
                    Button("Apagar", systemImage: "trash", role: .destructive) { clear = true }.labelStyle(.iconOnly).disabled(model.codeBusy)
                }.font(.subheadline).padding()
            }
            .navigationTitle("Aba de Escrita").navigationBarTitleDisplayMode(.inline)
            .onChange(of: model.document) { _, _ in if !model.codeBusy { model.saveDocument() } }
            .alert("Apagar o documento?", isPresented: $clear) { Button("Apagar", role: .destructive) { model.document = ""; model.saveDocument() }; Button("Cancelar", role: .cancel) { } }
            .sheet(isPresented: $preview) {
                NavigationStack {
                    if DocumentFormat.isWeb(model.document) { SafeWebPreview(html: DocumentFormat.unfenced(model.document)).navigationTitle("Preview").navigationBarTitleDisplayMode(.inline).toolbar { Button("Fechar") { preview = false } } }
                    else { ScrollView { Text(model.document).textSelection(.enabled).padding() }.navigationTitle("Documento").toolbar { Button("Fechar") { preview = false } } }
                }
            }
            .fileExporter(isPresented: $export, document: PlainDocument(text: model.document), contentType: .plainText, defaultFilename: DocumentFormat.isWeb(model.document) ? "documento.html" : "documento.txt") { result in if case .failure = result { model.report("Não foi possível exportar o documento.") } }
        }
    }
}
struct SafeWebPreview: UIViewRepresentable {
    let html: String
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator; view.uiDelegate = context.coordinator
        view.isOpaque = false
        // A sandboxed opaque iframe cannot reach the app or navigation. CSP is outside user HTML.
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        guard context.coordinator.loaded != html else { return }; context.coordinator.loaded = html
        let escaped = html.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
        let policy = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'self' about:; form-action 'none'; base-uri 'none'"
        let wrapper = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><meta http-equiv='Content-Security-Policy' content=\"\(policy)\"><style>html,body,iframe{margin:0;width:100%;height:100%;border:0}</style></head><body><iframe sandbox='allow-scripts' srcdoc=\"\(escaped)\"></iframe></body></html>"
        let rules = """
        [{"trigger":{"url-filter":"^https?://.*"},"action":{"type":"block"}},{"trigger":{"url-filter":"^wss?://.*"},"action":{"type":"block"}}]
        """
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "OSTIE-Offline", encodedContentRuleList: rules) { list, error in
            guard let list, error == nil else { return } // fail closed
            view.configuration.userContentController.add(list)
            view.loadHTMLString(wrapper, baseURL: nil)
        }
    }
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var loaded = ""
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let scheme = navigationAction.request.url?.scheme
            decisionHandler(navigationAction.navigationType == .other && (scheme == "about" || scheme == nil) ? .allow : .cancel)
        }
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}
