import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let label = UILabel()
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .systemBackground
        label.text = "Recebendo no OSTIE…"; label.numberOfLines = 0; label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(label)
        NSLayoutConstraint.activate([label.centerYAnchor.constraint(equalTo: view.centerYAnchor), label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24), label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24)])
        Task { await receive() }
    }
    @MainActor private func receive() async {
        do {
            guard let inbox = SharedContainer.inbox else { throw NSError(domain: "OSTIE", code: 1) }
            try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
            let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
            var count = 0
            for provider in providers.prefix(5) {
                if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    let item = try await load(provider, type: UTType.plainText.identifier)
                    let text = (item as? String) ?? (item as? URL)?.absoluteString ?? ""
                    guard !text.isEmpty, text.utf8.count <= 2_000_000 else { continue }
                    try Data(text.utf8).write(to: inbox.appendingPathComponent(UUID().uuidString + ".txt"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); count += 1
                } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    if let item = try await load(provider, type: UTType.url.identifier) as? URL {
                        try Data(item.absoluteString.utf8).write(to: inbox.appendingPathComponent(UUID().uuidString + ".txt"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); count += 1
                    }
                } else if let type = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .data) == true }) {
                    let saved: Bool = try await withCheckedThrowingContinuation { continuation in
                        provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                            do {
                                if let error { throw error }; guard let url else { continuation.resume(returning: false); return }
                                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                                guard size <= 50_000_000 else { throw NSError(domain: "OSTIE", code: 2) }
                                let name = UUID().uuidString + "-" + url.lastPathComponent
                                try FileManager.default.copyItem(at: url, to: inbox.appendingPathComponent(name)); continuation.resume(returning: true)
                            } catch { continuation.resume(throwing: error) }
                        }
                    }
                    if saved { count += 1 }
                }
            }
            label.text = count > 0 ? "Recebido! Abra o OSTIE para conferir e enviar à IA." : "Este conteúdo não é compatível."
            let button = UIButton(type: .system); button.setTitle("Concluir", for: .normal)
            button.addTarget(self, action: #selector(done), for: .touchUpInside); button.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(button); NSLayoutConstraint.activate([button.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 24), button.centerXAnchor.constraint(equalTo: view.centerXAnchor)])
        } catch { label.text = "Não foi possível receber o arquivo (limite de 50 MB). Feche esta tela e tente novamente." }
    }
    private func load(_ provider: NSItemProvider, type: String) async throws -> NSSecureCoding {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type, options: nil) { item, error in
                if let error { continuation.resume(throwing: error) }
                else if let item { continuation.resume(returning: item) }
                else { continuation.resume(throwing: NSError(domain: "OSTIE", code: 3)) }
            }
        }
    }
    @objc private func done() { extensionContext?.completeRequest(returningItems: [], completionHandler: nil) }
}
