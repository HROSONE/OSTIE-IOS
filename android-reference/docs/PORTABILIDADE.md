# Portabilidade do OSONE para Android

Fonte consultada: `HROSONE/OSONE-AI-code`, branch `main`, mapa `FEATURES.md` e instruções `AGENTS.md` em 23/09/2026.

| Sistema atual | Caminho Android | Estado |
| --- | --- | --- |
| Chat Gemini, OpenRouter e Groq | Kotlin, HTTPS, streaming SSE, chaves separadas e histórico privado | Implementado; validação dos provedores em aparelho pendente |
| Entrada e saída de voz | AudioRecord e AudioTrack no Live; TextToSpeech opcional no chat escrito | Live implementado, validação em aparelho pendente |
| Gemini Live, barge-in e reprodução PCM | AudioRecord, AudioTrack com saída de mídia e ganho ajustável, 30 vozes, WebSocket binário/texto, interrupção e fallback | Implementado; volume/vozes em aparelhos diversos pendentes |
| Memória e anexos | Room, indexação local e Android Photo Picker/SAF | Pendente |
| Ler tela e câmera | MediaProjection com consentimento, CameraX | Pendente |
| Abrir apps e links | Intents autorizadas e confirmação | Pendente |
| Automação do celular | Ações de sistema permitidas, AccessibilityService apenas com ativação consciente | Pendente |
| COWORK e navegação | Custom Tabs/WebView isolado e autorização de sessão | Pendente |
| OSONE HOME / Tuya | Backend autenticado; segredos Tuya só no servidor | Pendente |
| Handoff PC ↔ celular | Protocolo de autenticação e sincronização do backend atual | Pendente |
| OSONE CODE e ferramentas de PC | Ponte explícita para o agente desktop, sem shell no app | Pendente |
| Planos, contas e sincronização | Auth e contratos do backend atual; sem duplicar segredos | Pendente |

## Ordem de integração sugerida

1. Definir o contrato autenticado entre o backend OSONE e o Android, com testes de conversa e histórico.
2. Validar Gemini Live em aparelhos reais, medir latência e cancelamento de eco; usar tokens efêmeros ao adotar um backend de distribuição.
3. Portar anexos, câmera e leitura de tela com permissões temporárias e conteúdo visível ao usuário.
4. Integrar casa inteligente e handoff reutilizando o backend, sem distribuir chaves de servidor.
5. Implementar ferramentas Android específicas, com confirmação, auditoria e testes em aparelho real.

Uma semelhança visual ou um APK que simplesmente abra o site não equivalem à paridade funcional. Cada linha passa a "pronto" quando há integração, testes no aparelho e descrição de limites.
