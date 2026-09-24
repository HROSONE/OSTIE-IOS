import Foundation
import zlib

// Reads bounded ZIP members in memory only; never extracts paths to disk.
enum ArchiveReader {
    struct Entry { let name: String; let offset: Int; let compressed: Int; let size: Int; let method: Int; let flags: Int }
    static func describe(_ data: Data, office: Bool) throws -> String {
        let entries = try list(data)
        if !office { return "Lista de arquivos (conteúdo interno não analisado):\n" + entries.prefix(200).map { String($0.name.prefix(200)) }.joined(separator: "\n") }
        let selected = entries.filter { $0.name == "word/document.xml" || $0.name == "xl/sharedStrings.xml" || $0.name == "content.xml" || ($0.name.hasPrefix("ppt/slides/slide") && $0.name.hasSuffix(".xml")) }.prefix(40)
        var result = ""
        for entry in selected {
            guard result.utf8.count < 1_500_000 else { break }
            let xml = try extract(entry, data: data, limit: 1_000_000)
            let parser = XMLParser(data: xml); let collector = XMLText()
            parser.shouldResolveExternalEntities = false; parser.delegate = collector
            guard parser.parse() else { throw AppError.message("Documento XML inválido.") }
            result += collector.text + "\n"
        }
        guard !result.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw AppError.message("Não encontrei texto legível. Exporte para PDF.") }
        return String(result.prefix(1_000_000))
    }
    static func list(_ data: Data) throws -> [Entry] {
        guard data.count >= 22 else { throw AppError.message("ZIP inválido.") }
        var end: Int?
        for i in stride(from: data.count - 22, through: max(0, data.count - 65_557), by: -1) {
            if try u32(data, i) == 0x06054b50, i + 22 + (try u16(data, i + 20)) == data.count { end = i; break }
        }
        guard let end, try u16(data, end + 4) == 0, try u16(data, end + 6) == 0 else { throw AppError.message("ZIP multipart/ZIP64 não suportado.") }
        let count = try u16(data, end + 10); var cursor = try u32(data, end + 16)
        guard count < 10_000 else { throw AppError.message("ZIP tem arquivos demais.") }
        var entries: [Entry] = []
        for _ in 0..<count {
            guard try u32(data, cursor) == 0x02014b50 else { throw AppError.message("Diretório ZIP inválido.") }
            let n = try u16(data, cursor + 28), extra = try u16(data, cursor + 30), comment = try u16(data, cursor + 32)
            let next = cursor + 46 + n + extra + comment
            guard next <= data.count, let name = String(data: data[(cursor + 46)..<(cursor + 46 + n)], encoding: .utf8) else { throw AppError.message("Nome ZIP inválido.") }
            entries.append(Entry(name: name, offset: try u32(data, cursor + 42), compressed: try u32(data, cursor + 20), size: try u32(data, cursor + 24), method: try u16(data, cursor + 10), flags: try u16(data, cursor + 8)))
            cursor = next
        }
        return entries
    }
    static func extract(_ entry: Entry, data: Data, limit: Int) throws -> Data {
        guard entry.flags & 1 == 0, entry.size <= limit, entry.compressed <= 5_000_000, try u32(data, entry.offset) == 0x04034b50 else { throw AppError.message("Arquivo protegido ou conteúdo interno grande demais.") }
        let start = entry.offset + 30 + (try u16(data, entry.offset + 26)) + (try u16(data, entry.offset + 28))
        guard start <= data.count, entry.compressed <= data.count - start else { throw AppError.message("Membro ZIP truncado.") }
        let compressed = Data(data[start..<(start + entry.compressed)])
        if entry.method == 0 { guard compressed.count == entry.size else { throw AppError.message("Tamanho ZIP inválido.") }; return compressed }
        guard entry.method == 8 else { throw AppError.message("Compressão ZIP não suportada.") }
        var output = Data(count: max(1, entry.size)); var stream = z_stream()
        guard inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw AppError.message("Falha ao abrir compressão.") }
        defer { inflateEnd(&stream) }
        let status: Int32 = output.withUnsafeMutableBytes { destination in
            compressed.withUnsafeBytes { source in
                stream.next_in = UnsafeMutablePointer<Bytef>(mutating: source.bindMemory(to: Bytef.self).baseAddress)
                stream.avail_in = uInt(compressed.count); stream.next_out = destination.bindMemory(to: Bytef.self).baseAddress; stream.avail_out = uInt(max(1, entry.size))
                return inflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END, stream.total_out == entry.size else { throw AppError.message("Conteúdo ZIP inválido ou acima do limite.") }
        return output.prefix(entry.size)
    }
    private static func u16(_ data: Data, _ offset: Int) throws -> Int {
        guard offset >= 0, offset <= data.count - 2 else { throw AppError.message("ZIP truncado.") }
        return Int(data[offset]) | Int(data[offset + 1]) << 8
    }
    private static func u32(_ data: Data, _ offset: Int) throws -> Int { try u16(data, offset) | u16(data, offset + 2) << 16 }
    private final class XMLText: NSObject, XMLParserDelegate {
        var text = ""
        func parser(_ parser: XMLParser, foundCharacters string: String) { if text.utf8.count < 1_000_000 { text += string } }
        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) { if ["w:p", "a:p", "text:p", "row", "si"].contains(elementName) { text += "\n" } }
    }
}
