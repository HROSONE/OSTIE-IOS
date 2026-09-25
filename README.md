# OSTIE para iPhone

Versão iOS nativa em Swift e SwiftUI, em repositório independente do **HROSONE/OSONE-APP**. O original Android não foi alterado.

## Abrir

1. Em um Mac com Xcode 16 ou posterior, prepare o ícone pelo Terminal, na raiz deste repositório, com o comando abaixo. Depois abra `ios/OSTIE.xcodeproj`.
2. Escolha o esquema **OSTIE** e um simulador de iPhone; pressione Run.
3. Para instalar no seu iPhone, escolha sua equipe Apple em **Signing & Capabilities** nos alvos OSTIE, OSTIEBroadcast e OSTIEShare. Registre os três identificadores e o grupo `group.com.hrosone.ostie.ios` na mesma equipe.
4. Escolha o iPhone conectado e pressione Run. Em Ajustes do OSTIE, salve suas chaves Gemini, Groq e/ou OpenRouter.

```sh
xcrun --sdk macosx swift scripts/prepare_assets.swift ios/OSTIE/Assets.xcassets/Orb.imageset/orb.png ios/OSTIE/Assets.xcassets/AppIcon.appiconset/AppIcon.png
```

Execute esse preparo uma vez após clonar. O ícone é gerado localmente a partir do orbe original; no GitHub Actions, essa etapa é automática antes da compilação.

O aplicativo requer iOS 17+. Uma compilação sem assinatura ou um ZIP de simulador **não é um instalador de iPhone**. TestFlight e App Store exigem assinatura e distribuição pela sua conta Apple Developer. Não há certificados ou chaves privadas neste projeto.

## Implementação

- Conversa com streaming, histórico local, Gemini/Groq/OpenRouter, raciocínio selecionável, ferramentas, voz Gemini TTS ou iPhone e fallback Gemini sem repetir resposta parcial ou ferramenta executada.
- Live com áudio PCM 16 kHz de entrada / 24 kHz de saída, processamento de voz do iOS, interrupção de resposta, reconexões limitadas, retomada de sessão, 30 vozes e consulta de modelos disponíveis na chave.
- Orbe original reativo e ícone preparado da mesma arte antes da compilação, tema escuro/claro, microfone silenciável, legendas opcionais, conversa salva no histórico quando escolhida.
- Câmera frontal/traseira com imagens limitadas a um quadro por segundo; pausa ao sair do app. Falha da câmera não encerra o Live.
- ReplayKit Broadcast Extension para mostrar outros apps ao Live com confirmação do sistema, App Group e quadro temporário único; revogação quando o Live termina.
- Aba de Escrita com edição, Play HTML/SVG, copiar, exportar e apagar com confirmação. Preview sem rede e sem ponte para APIs nativas; conteúdo em iframe isolado. Código pode ser delegado ao modelo de texto enquanto o Live continua.
- Memória Markdown local, edição/importação/exportação; três chaves no Keychain, sem credenciais nos arquivos ou logs.
- Anexos multimodais via Gemini (imagens/PDF/texto/áudio/vídeo compatíveis; até 50 MB por arquivo e 100 MB por envio; Office/ZIP têm extração local; arquivos grandes usam Files API com limpeza após a resposta), extensão Compartilhar para receber arquivos e revisão antes do envio.
- Ações autorizadas: agenda, contatos, mensagens prontas, telefone, mapas, links, alguns apps conhecidos, brilho, lanterna e status do aparelho.
- Rotinas com notificações locais, dias da semana, ativar/desativar e teste manual. A execução da IA ocorre ao abrir a notificação; o iOS não garante execução arbitrária em segundo plano no horário marcado.
- Atalho de abertura via Siri/Atalhos e links `ostie://live` / `ostie://writing`.

## Cópia original e paridade

`android-reference/` preserva os **77 arquivos da versão main original**, incluindo Kotlin, recursos, testes e workflows, no commit registrado em `docs/android-source.json`. Os hashes Git são verificados por `python3 scripts/verify_reference.py`. É uma cópia integral dos arquivos daquele commit, **não um espelho de todas as branches, histórico de commits, issues, releases ou segredos**. O workflow Android está somente na pasta de referência e não executa no novo repositório.

A conversão de APIs Android para iOS não permite paridade integral de capacidades. Veja `docs/PARIDADE.md` para os recursos adaptados e os que ainda não foram portados. Código implementado e compilação aprovada não substituem teste de áudio/câmera/tela em aparelho real.

## Validação

**Situação atual:** o aplicativo e as duas extensões compilaram para iPhone; oito testes passaram e o app abriu no simulador. O repositório foi tornado público para usar o executor macOS padrão do GitHub Actions sem cobrança de minutos. Consulte [o registro de validação](docs/VALIDACAO.md) para a execução e os limites do teste. A versão atual ainda não foi validada em iPhone real.

O workflow `iOS build and tests` verifica os hashes, regenera o projeto sem dependências externas, compila o aplicativo e suas extensões para iPhone e executa XCTest no simulador. Publica logs, resultado de testes e app de simulador. Não publica uma versão na App Store nem usa o repositório de releases Android.

O projeto Xcode é versionado e também pode ser reproduzido com `python3 scripts/generate_project.py`. Fontes de referência: documentação oficial Apple para AVAudioEngine, ReplayKit, App Intents e UserNotifications; documentação Google da Live API WebSocket.
