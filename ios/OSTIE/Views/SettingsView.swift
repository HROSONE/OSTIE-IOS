import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var liveModels = ModelCatalog.live
    @State private var groqModels: [String] = []
    @State private var loading = false
    @State private var exportMemory = false
    @State private var importMemory = false
    var body: some View {
        NavigationStack {
            Form {
                Section("Seu perfil") {
                    TextField("Como você quer ser chamado?", text: $model.settings.name)
                    Toggle("Modo escuro", isOn: $model.settings.darkMode)
                }
                Section("Chaves de API") {
                    ForEach(Provider.allCases) { provider in KeyEntry(provider: provider) }
                    Text("As chaves ficam no chaveiro deste iPhone. Mensagens e memória contextual vão ao provedor escolhido. Áudio, imagens e anexos vão ao Gemini quando você usa esses recursos. Contatos e agenda consultados pelo agente podem integrar a resposta da ferramenta.").font(.caption)
                }
                Section("Modelo de texto") {
                    Picker("Provedor", selection: $model.settings.provider) { ForEach(Provider.allCases) { Text($0.rawValue).tag($0) } }
                    if model.settings.provider == .gemini { TextField("ID do modelo Gemini", text: $model.settings.geminiModel).autocorrectionDisabled().textInputAutocapitalization(.never) }
                    if model.settings.provider == .groq {
                        TextField("ID do modelo Groq", text: $model.settings.groqModel).autocorrectionDisabled().textInputAutocapitalization(.never)
                        Button("Consultar modelos Groq") { loadModels(.groq) }.disabled(loading)
                        if !groqModels.isEmpty { Picker("Modelos disponíveis", selection: $model.settings.groqModel) { ForEach(Array(Set(groqModels + [model.settings.groqModel])).sorted(), id: \.self) { Text($0).tag($0) } } }
                    }
                    if model.settings.provider == .openRouter { TextField("ID do modelo OpenRouter", text: $model.settings.routerModel).autocorrectionDisabled().textInputAutocapitalization(.never) }
                    Picker("Raciocínio Gemini", selection: $model.settings.thinking) {
                        Text("Rápido").tag("low"); Text("Equilibrado").tag("medium"); Text("Profundo").tag("high")
                    }
                    Toggle("Fallback de modelos", isOn: $model.settings.fallback)
                    Toggle("Pesquisa Google", isOn: $model.settings.googleSearch)
                    Toggle("Ler respostas em voz alta", isOn: $model.settings.speakChat)
                    if model.settings.speakChat {
                        Picker("Voz do chat", selection: $model.settings.ttsModel) {
                            Text("Gemini Flash-Lite TTS · rápida").tag("gemini-3.8-flash-lite-tts")
                            Text("Gemini Flash TTS · expressiva").tag("gemini-3.8-flash-tts")
                            Text("Voz do iPhone").tag("iphone")
                        }
                    }
                }
                Section("Voz em tempo real") {
                    Picker("Modelo Live", selection: $model.settings.liveModel) { ForEach(Array(Set(liveModels + [model.settings.liveModel])).sorted(), id: \.self) { Text($0).tag($0) } }
                    TextField("ID personalizado do Live", text: $model.settings.liveModel).autocorrectionDisabled().textInputAutocapitalization(.never)
                    Button("Consultar modelos da minha chave") { loadModels(.gemini) }.disabled(loading)
                    Picker("Voz", selection: $model.settings.voice) { ForEach(ModelCatalog.voices, id: \.self) { Text($0).tag($0) } }
                    Picker("Código pedido por voz", selection: $model.settings.codeWriter) {
                        Text("Perguntar sempre").tag("ask"); Text("Modelo de voz").tag("voice"); Text("Modelo de texto").tag("text")
                    }
                    Toggle("Salvar transcrição no histórico", isOn: $model.settings.saveTranscript)
                    Text("Mudanças de modelo e voz valem na próxima conexão. A conversa usa áudio nativo Gemini; a lista não garante acesso ou cota para cada modelo.").font(.caption)
                    if model.live.active { Button("Reconectar com estes ajustes") { model.live.stop(); model.toggleLive() } }
                }
                Section("Memória do OSTIE") {
                    NavigationLink("Ler e editar memória") { MemoryView() }
                    Button("Exportar memória") { exportMemory = true }
                    Button("Importar memória") { importMemory = true }
                    Text("Exporte para Arquivos ou iCloud Drive para preservar fora do app. Apagar o aplicativo pode remover os documentos locais.").font(.caption)
                }
                Section("No iPhone") {
                    Text("Agenda, contatos, mapas, mensagens prontas, lanterna, brilho, câmera, tela autorizada e Atalhos.")
                    Text("O OSTIE não controla toques em outros apps nem lê suas notificações. Rotinas avisam no horário; a IA executa a tarefa ao abrir a notificação. A câmera pausa quando você sai do app.").font(.caption).foregroundStyle(.secondary)
                    Button("Permissões do OSTIE") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
                Section("Diagnóstico") {
                    if model.diagnostics.isEmpty { Label("Nenhuma falha registrada nesta sessão", systemImage: "checkmark.circle").foregroundStyle(.green) }
                    ForEach(Array(model.diagnostics.enumerated()), id: \.offset) { _, line in Text(line).font(.caption).textSelection(.enabled) }
                    if !model.diagnostics.isEmpty { Button("Limpar diagnóstico") { model.diagnostics = [] } }
                }
                Section("Versão e atualização") {
                    Text("OSTIE iOS · \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0")")
                    Text("Atualizações são instaladas pelo TestFlight, App Store ou pelo Xcode, conforme o canal usado para instalar. Não é necessário desinstalar para atualizar com a mesma assinatura.").font(.caption)
                    Link("Abrir TestFlight", destination: URL(string: "https://apps.apple.com/app/testflight/id899247664")!)
                }
            }
            .navigationTitle("Ajustes")
            .onDisappear { model.saveSettings() }
            .fileExporter(isPresented: $exportMemory, document: PlainDocument(text: model.memory), contentType: .plainText, defaultFilename: "memoria.md") { result in if case .failure = result { model.report("Não foi possível exportar a memória.") } }
            .fileImporter(isPresented: $importMemory, allowedContentTypes: [.plainText, .data]) { result in
                guard case .success(let url) = result else { return }
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                do {
                    let data = try Data(contentsOf: url); guard data.count <= 100_000, let text = String(data: data, encoding: .utf8) else { throw AppError.message("Use um arquivo UTF-8 de até 100 KB.") }
                    Task { if await model.confirm("Adicionar as anotações deste arquivo à memória do OSTIE?") { model.memory += "\n" + text; model.saveMemory() } }
                } catch { model.report(error.localizedDescription) }
            }
        }
    }
    private func loadModels(_ provider: Provider) {
        loading = true
        Task {
            defer { loading = false }
            do {
                guard let key = try model.keys.read(provider) else { throw AppError.message("Salve a chave primeiro.") }
                let models = try await model.textClient.models(provider: provider, key: key)
                if provider == .gemini { liveModels = models } else { groqModels = models }
            } catch { model.report(error.localizedDescription) }
        }
    }
}
struct KeyEntry: View {
    @EnvironmentObject var model: AppModel
    let provider: Provider
    @State private var value = ""
    @State private var saved = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text(provider.rawValue).font(.subheadline.bold()); Spacer(); if saved { Label("Salva", systemImage: "lock.fill").font(.caption).foregroundStyle(.green) } }
            HStack {
                SecureField("Nova chave \(provider.rawValue)", text: $value).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Salvar") {
                    do { try model.keys.save(value, provider: provider); value = ""; saved = true }
                    catch { model.report(error.localizedDescription) }
                }.disabled(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }.onAppear { saved = (try? model.keys.read(provider)) != nil }
    }
}
struct MemoryView: View {
    @EnvironmentObject var model: AppModel
    var body: some View { TextEditor(text: $model.memory).padding().navigationTitle("Memória").toolbar { Button("Salvar") { model.saveMemory() } }.onDisappear { model.saveMemory() } }
}
