# OSTIE iOS

- Trabalhe somente neste repositório. Não edite OSONE-APP ou o canal de releases Android.
- `android-reference/` é snapshot imutável, validado por hashes em `docs/android-source.json`.
- Não grave chaves, certificados ou dados pessoais em código, arquivos de diagnóstico ou commits.
- SwiftUI/Swift nativo, iOS 17+, três alvos (app, ReplayKit, Share) e XCTest.
- Regere o projeto após adicionar arquivos: `python3 scripts/generate_project.py`.
- Validação: `python3 scripts/verify_reference.py`, build Xcode para iPhone e XCTest em simulador.
- Recursos com hardware/API devem ser descritos como não testados em aparelho enquanto não houver evidência.
- Preserve as confirmações de ações e a revogação de câmera/tela/microfone.
- Português do Brasil em toda interface; todos os ícones interativos têm rótulo de acessibilidade.
