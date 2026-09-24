# Android → iOS: escopo e limites

Fonte: snapshot registrado em android-source.json. “Implementado” descreve código presente; o teste de ponta a ponta dos provedores, permissões e hardware exige um iPhone assinado e chaves de API válidas.

| Android | iOS | Estado |
|---|---|---|
| Kotlin / Compose | Swift / SwiftUI | Implementado |
| Logo e tema | Mesmo PNG do orbe, tema persistente | Implementado |
| Gemini/Groq/OpenRouter streaming | URLSession SSE, contexto, tool loop | Implementado |
| Fallback Gemini | Somente indisponibilidade/cota, antes de qualquer efeito | Implementado |
| Catálogo Groq e Live | Consulta pela chave; paginação Gemini | Implementado; fallback automático Groq por 404 ainda não portado |
| Raciocínio rápido/equilibrado/profundo | Configuração padrão do provedor | Seletor ainda não portado |
| Gemini Live | WebSocket + AVAudioEngine | Implementado; testar latência/eco em aparelhos |
| 30 vozes | Mesmos nomes; depende do modelo | Implementado |
| TTS de respostas escritas Gemini | AVSpeechSynthesizer do iPhone | Adaptado; TTS Gemini ainda não portado |
| Câmera frontal/traseira | AVCaptureSession | Implementado; câmera pausa em segundo plano |
| Tela via MediaProjection | Extensão ReplayKit autorizada | Implementado; exige App Group e assinatura corretos |
| Bolha sobre outros apps | Sem sobreposição arbitrária | Não disponível pelas APIs públicas usadas |
| Toques/digitação por acessibilidade | Sem serviço equivalente para controle de outros apps | Não disponível |
| Ler/responder notificações de outros apps | Notificações próprias apenas | Não disponível |
| Descoberta irrestrita de apps | Lista pequena de esquemas conhecidos | Adaptado |
| Abrir configurações do sistema | Abrir configurações do OSTIE | Adaptado |
| Volume/rotação/timeout | Controles do próprio iOS | Não portado; brilho permitido implementado |
| Intents telefone/mensagens/mapas | URL schemes / composição com confirmação | Implementado; envio não automático |
| Agenda e contatos | EventKit / Contacts com permissão | Implementado |
| Memória fora do app | Documentos do app + exportação/importação Arquivos/iCloud | Adaptado; desinstalação pode remover documentos |
| Anexos até 50 MB + Office/ZIP | Inline Gemini até 15 MB por envio | Parcial; Office/ZIP e Files API ainda não portados |
| Editor e preview HTML/SVG | TextEditor + WKWebView isolado sem rede | Implementado |
| Delegação de código | Escolha modelo de texto/voz, cancelamento | Implementado |
| Rotinas IA em segundo plano | Aviso local + execução ao abrir | Adaptado; servidor seria necessário para garantia de execução sem abrir |
| Alarmes/timers do sistema | Rotinas/lembretes OSTIE | Adaptado; AlarmKit não implementado (mínimo iOS 17) |
| Palavra de ativação contínua | Abertura por Siri/Atalhos | Adaptado; não há escuta oculta contínua |
| Atualizador de APK | Xcode/TestFlight/App Store | Adaptado; depende da assinatura Apple |

## Verificação em aparelho antes de distribuir

- Salvar cada chave, encerrar e reabrir; texto streaming, cancelamento, cota e chave inválida.
- Live no alto-falante e fone, Bluetooth, interrupção pelo usuário, ligação recebida e troca de saída; nunca religar microfone sozinho após encerramento.
- Câmera frontal/traseira, recusa de permissão, bloqueio de tela, revogação e troca câmera/tela.
- ReplayKit com outro app aberto e ao encerrar Live; conferir que quadros param e a extensão encerra.
- Confirmações de mensagens, evento, ligações e substituição de documento; cancelamento não executa ação.
- Exportar memória, importar sem apagar anotações, Share Extension, rotinas ao tocar notificação.
- Preview com fetch/WebSocket/formulário/link remoto deve permanecer sem acesso à rede/arquivos locais.

## Fontes técnicas

- https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket
- https://ai.google.dev/gemini-api/docs/live-api/session-management
- https://developer.apple.com/documentation/avfaudio/avaudioionode/setvoiceprocessingenabled(_:)
- https://developer.apple.com/documentation/replaykit/rpbroadcastsamplehandler
- https://developer.apple.com/documentation/usernotifications/scheduling-a-notification-locally-from-your-app
- https://developer.apple.com/documentation/appintents
