import Foundation

struct ToolRegistry {
    static func declarations(search: Bool) -> [[String: Any]] {
        var list = [
            tool("device_status", "Consulte hora, bateria e brilho do iPhone.", [:]),
            tool("open_url", "Abra um link HTTPS após confirmação.", ["url": "Endereço HTTPS"], required: ["url"]),
            tool("open_settings", "Abra os ajustes do OSTIE. O iOS não permite alterar ajustes arbitrários.", [:]),
            tool("open_app", "Abra um app conhecido: safari, maps, whatsapp, shortcuts ou calendar.", ["app": "Nome do aplicativo"], required: ["app"]),
            tool("set_brightness", "Ajuste brilho da tela após confirmação. valor entre 0 e 1.", ["valor": "Brilho entre 0 e 1"], required: ["valor"]),
            tool("flashlight", "Ligue ou desligue a lanterna após confirmação.", ["estado": "on ou off"], required: ["estado"]),
            tool("compose_message", "Prepare SMS ou WhatsApp. O usuário confere e envia no aplicativo; nunca declare que enviou.", ["canal": "sms ou whatsapp", "numero": "Telefone com código do país", "texto": "Mensagem"], required: ["canal", "numero", "texto"]),
            tool("dial", "Abra o telefone após confirmação do número. Não confirme que a chamada ocorreu.", ["numero": "Telefone"], required: ["numero"]),
            tool("maps", "Abra uma rota no Mapas.", ["destino": "Lugar ou endereço"], required: ["destino"]),
            tool("find_contacts", "Procure contatos por nome após permissão do sistema.", ["nome": "Nome para pesquisar"], required: ["nome"]),
            tool("read_calendar", "Leia eventos de hoje ou de uma data ISO 8601, após permissão.", ["data": "Data ISO 8601 opcional"]),
            tool("create_event", "Crie um evento após o usuário confirmar título e datas.", ["titulo": "Título", "inicio": "ISO 8601 com fuso", "fim": "ISO 8601 com fuso"], required: ["titulo", "inicio", "fim"]),
            tool("memory_read", "Leia as anotações locais do OSTIE.", [:]),
            tool("memory_note", "Anote uma preferência ou informação útil. Não salve senhas, chaves ou dados bancários.", ["texto": "Anotação curta"], required: ["texto"]),
            tool("set_user_name", "Salve o nome informado pelo próprio usuário.", ["nome": "Nome"], required: ["nome"]),
            tool("write_document", "Escreva conteúdo na Aba de Escrita. HTML e SVG têm preview. Não invente que criou sem usar esta função.", ["conteudo": "Texto ou código completo"], required: ["conteudo"]),
            tool("request_code", "Delegue código ao modelo de texto ou pergunte ao usuário quem deve escrever.", ["pedido": "Requisitos completos"], required: ["pedido"]),
            tool("list_routines", "Liste as rotinas locais.", [:]),
            tool("create_routine", "Crie lembrete/rotina após confirmação. A tarefa de IA é executada quando o usuário abre a notificação, não há horário exato de IA em segundo plano.", ["titulo": "Nome", "pedido": "Tarefa", "hora": "0 a 23", "minuto": "0 a 59", "dias": "Dias separados por vírgula: domingo=1 até sábado=7; vazio para próxima ocorrência"], required: ["titulo", "hora", "minuto"])
        ]
        if search { list.append(tool("web_search", "Pesquise informações atuais na web e devolva fontes.", ["consulta": "Consulta"], required: ["consulta"])) }
        return list
    }
    private static func tool(_ name: String, _ description: String, _ properties: [String: String], required: [String] = []) -> [String: Any] {
        ["name": name, "description": description, "parameters": ["type": "object", "properties": properties.mapValues { ["type": "string", "description": $0] }, "required": required]]
    }
}
