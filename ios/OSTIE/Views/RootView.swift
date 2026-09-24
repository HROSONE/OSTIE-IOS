import SwiftUI
import ReplayKit
import UniformTypeIdentifiers

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var phase
    var body: some View {
        TabView(selection: $model.tab) {
            ChatView().tabItem { Label("Conversa", systemImage: "bubble.left.and.bubble.right") }.tag(0)
            LiveView(live: model.live, camera: model.camera).tabItem { Label("Live", systemImage: "waveform") }.tag(1)
            WritingView().tabItem { Label("Escrita", systemImage: "square.and.pencil") }.tag(2)
            RoutinesView().tabItem { Label("Rotinas", systemImage: "clock") }.tag(3)
            SettingsView().tabItem { Label("Ajustes", systemImage: "gearshape") }.tag(4)
        }
        .tint(.cyan)
        .preferredColorScheme(model.settings.darkMode ? .dark : .light)
        .alert("OSTIE", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("Entendi") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .sheet(isPresented: Binding(get: { model.approvalText != nil }, set: { if !$0 { model.resolveApproval(false) } })) {
            VStack(spacing: 24) {
                Image(systemName: "hand.raised").font(.largeTitle).foregroundStyle(.cyan)
                Text("Autorizar ação").font(.title2.bold())
                ScrollView { Text(model.approvalText ?? "").frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled) }
                HStack { Button("Cancelar", role: .cancel) { model.resolveApproval(false) }; Spacer(); Button("Confirmar") { model.resolveApproval(true) }.buttonStyle(.borderedProminent) }
            }.padding(28).presentationDetents([.medium, .large])
        }
        .confirmationDialog("Qual modelo deve escrever o código?", isPresented: $model.showCodeChoice, titleVisibility: .visible) {
            Button("Modelo de voz") { model.chooseWriter("voice") }
            Button("Modelo de texto") { model.chooseWriter("text") }
            Button("Cancelar", role: .cancel) { model.chooseWriter("cancel") }
        }
        .onChange(of: phase) { _, phase in
            if phase == .background { model.backgrounded() }
            if phase == .active { model.importInbox() }
        }
        .onOpenURL { url in
            guard url.scheme == "ostie" else { return }
            if url.host == "live" { model.tab = 1 }
            if url.host == "writing" { model.tab = 2 }
        }
    }
}

struct ChatView: View {
    @EnvironmentObject var model: AppModel
    @State private var picker = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 18) {
                            if model.messages.isEmpty {
                                VStack(spacing: 12) {
                                    Image("Orb").resizable().scaledToFit().frame(width: 170, height: 170)
                                    Text(model.settings.name.isEmpty ? "Vamos conversar?" : "Olá, \(model.settings.name).") .font(.title2.bold())
                                    Text("Escreva, envie um arquivo ou abra o Live.").foregroundStyle(.secondary)
                                }.frame(maxWidth: .infinity).padding(.vertical, 50)
                            }
                            ForEach(model.messages) { message in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(message.role == "user" ? "Você" : "OSTIE").font(.caption.bold()).foregroundStyle(.secondary)
                                    Text(message.text.isEmpty ? "Pensando…" : message.text).textSelection(.enabled)
                                    if message.role == "assistant", DocumentFormat.isWeb(message.text) || message.text.contains("```") {
                                        Button("Abrir na Aba de Escrita", systemImage: "square.and.pencil") {
                                            Task {
                                                let allowed = model.document.isEmpty ? true : await model.confirm("Substituir o documento atual por esta resposta?")
                                                if allowed {
                                                    model.document = DocumentFormat.unfenced(message.text); model.saveDocument(); model.tab = 2
                                                }
                                            }
                                        }.font(.caption)
                                    }
                                }
                                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                                .background(message.role == "user" ? Color.cyan.opacity(0.1) : Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
                                .id(message.id)
                            }
                        }.padding()
                    }
                    .onChange(of: model.messages.last?.text) { _, _ in if let id = model.messages.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
                }
                if !model.attachments.isEmpty {
                    ScrollView(.horizontal) {
                        HStack {
                            ForEach(model.attachments) { attachment in
                                Button { model.attachments.removeAll { $0.id == attachment.id } } label: { Label(attachment.name, systemImage: "xmark.circle") }.buttonStyle(.bordered)
                            }
                        }.padding(.horizontal)
                    }
                    Text("Os anexos são enviados ao Gemini quando você tocar em Enviar.").font(.caption2).foregroundStyle(.secondary)
                }
                HStack(alignment: .bottom, spacing: 12) {
                    Button { picker = true } label: { Image(systemName: "paperclip").font(.title3) }.accessibilityLabel("Anexar arquivo").disabled(model.busy)
                    TextField("Fale com o OSTIE…", text: $model.draft, axis: .vertical).lineLimit(1...5).padding(12).background(.quaternary, in: RoundedRectangle(cornerRadius: 18))
                    if model.busy {
                        Button { model.cancelChat() } label: { Image(systemName: "stop.circle.fill").font(.title) }.accessibilityLabel("Parar resposta")
                    } else if model.draft.isEmpty && model.attachments.isEmpty {
                        Button { model.tab = 1 } label: { Image(systemName: "waveform.circle.fill").font(.title) }.accessibilityLabel("Abrir conversa Live")
                    } else {
                        Button { model.send() } label: { Image(systemName: "arrow.up.circle.fill").font(.title) }.accessibilityLabel("Enviar mensagem")
                    }
                }.padding()
            }
            .navigationTitle("OSTIE").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { model.tab = 4 } label: { Image(systemName: model.diagnostics.isEmpty ? "checkmark.circle" : "exclamationmark.circle").foregroundStyle(model.diagnostics.isEmpty ? .green : .orange) }.accessibilityLabel("Ajustes e diagnóstico") } }
            .fileImporter(isPresented: $picker, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
                switch result { case .success(let urls): for url in urls.prefix(5) { model.importFile(url) }; case .failure: model.report("Não foi possível abrir o arquivo.") }
            }
        }
    }
}

struct LiveView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var live: LiveSession
    @ObservedObject var camera: CameraController
    @State private var showScreen = false
    @State private var captions = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer()
                ZStack {
                    Circle().fill(.cyan.opacity(0.08)).frame(width: 270, height: 270).blur(radius: 20)
                    Image("Orb").resizable().scaledToFit().frame(maxWidth: 290)
                        .scaleEffect(1 + CGFloat(min(live.level, 0.4)) * 0.35)
                        .animation(.easeOut(duration: 0.12), value: live.level)
                }.accessibilityLabel(live.connected ? "OSTIE está ouvindo" : "Orbe do OSTIE")
                HStack(spacing: 8) { Circle().fill(live.connected ? .green : .secondary).frame(width: 7, height: 7); Text(live.state).font(.subheadline).multilineTextAlignment(.center) }.padding(.horizontal)
                if camera.active, let preview = camera.preview {
                    HStack { Image(uiImage: preview).resizable().scaledToFit().frame(height: 120).clipShape(RoundedRectangle(cornerRadius: 12)); Button("Trocar câmera", systemImage: "arrow.triangle.2.circlepath.camera") { camera.flip() }.labelStyle(.iconOnly) }
                }
                if model.screenSharing { Text("Tela autorizada · \(live.framesSent) quadros enviados").font(.caption).foregroundStyle(.secondary) }
                if captions {
                    VStack(alignment: .leading, spacing: 8) { Text(live.inputCaption).foregroundStyle(.secondary); Text(live.outputCaption) }.font(.callout).lineLimit(5).padding(.horizontal)
                }
                Spacer()
                if live.active {
                    HStack(spacing: 23) {
                        control(live.muted ? "mic.slash.fill" : "mic.fill", "Silenciar microfone") { live.muted.toggle() }
                        control(camera.active ? "video.fill" : "video", "Mostrar câmera") { model.toggleCamera() }
                        control(model.screenSharing ? "rectangle.inset.filled" : "rectangle.on.rectangle", "Compartilhar tela") {
                            if model.screenSharing { model.stopScreen() } else { model.prepareScreen(); showScreen = model.screenSharing }
                        }
                        control("captions.bubble", "Mostrar legendas") { captions.toggle() }
                        Button { model.toggleLive() } label: { Image(systemName: "phone.down.fill").foregroundStyle(.white).padding(17).background(.red, in: Circle()) }.accessibilityLabel("Encerrar Live")
                    }.padding(.bottom, 28)
                } else {
                    Button("Iniciar conversa", systemImage: "waveform") { model.toggleLive() }.buttonStyle(.borderedProminent).controlSize(.large).padding(.bottom, 28)
                }
            }
            .navigationTitle("Conversa Live").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Ajustes", systemImage: "gearshape") { model.tab = 4 }.labelStyle(.iconOnly) }
            .sheet(isPresented: $showScreen) {
                VStack(spacing: 24) {
                    Text("Mostrar a tela ao OSTIE").font(.title2.bold())
                    Text("Toque no botão abaixo e confirme Iniciar Transmissão. O que aparecer na tela será enviado ao Gemini durante o Live. Para parar, use o controle de gravação do iPhone ou o botão de tela do OSTIE.")
                    BroadcastPicker().frame(width: 60, height: 60)
                    Button("Voltar à conversa") { showScreen = false }
                }.padding(28).presentationDetents([.medium, .large])
            }
        }
    }
    private func control(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).font(.title3).frame(width: 32, height: 48) }.accessibilityLabel(label)
    }
}
struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 60, height: 60))
        picker.preferredExtension = "com.hrosone.ostie.ios.broadcast"; picker.showsMicrophoneButton = false
        return picker
    }
    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) { }
}
