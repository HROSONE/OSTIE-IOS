import Foundation
import Security

struct KeyStore {
    private let service = "com.hrosone.ostie.ios.api"
    func read(_ provider: Provider) throws -> String? {
        var query = base(provider); query[kSecReturnData as String] = true; query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw AppError.message("Não foi possível abrir o chaveiro do iPhone.") }
        return String(data: data, encoding: .utf8)
    }
    func save(_ value: String, provider: Provider) throws {
        let key = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw AppError.message("Digite a chave antes de salvar.") }
        let attributes: [String: Any] = [kSecValueData as String: Data(key.utf8), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(base(provider) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(base(provider).merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess else { throw AppError.message("Não foi possível salvar a chave.") }
        } else if status != errSecSuccess { throw AppError.message("Não foi possível atualizar a chave.") }
        guard try read(provider) == key else { throw AppError.message("A verificação da chave falhou.") }
    }
    private func base(_ provider: Provider) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: provider.rawValue]
    }
}
struct LocalFiles {
    let root: URL
    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("OSTIE", isDirectory: true)
    }
    func read<T: Decodable>(_ name: String, as: T.Type) throws -> T? {
        let url = root.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: url))
    }
    func write<T: Encodable>(_ value: T, name: String) throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try writeData(encoder.encode(value), name: name)
    }
    func text(_ name: String) throws -> String? {
        let url = root.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try String(contentsOf: url, encoding: .utf8)
    }
    func writeText(_ text: String, name: String) throws { try writeData(Data(text.utf8), name: name) }
    private func writeData(_ data: Data, name: String) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try data.write(to: root.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUnlessOpen])
    }
}
