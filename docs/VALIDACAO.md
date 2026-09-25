# Registro de validação

## Evidência confirmada

- A [execução 36084734232](https://github.com/HROSONE/OSTIE-IOS/actions/runs/36084734232), no commit `c3ce63d97aa70d6fee659a3725cfa3417eb58f80`, concluiu a compilação do aplicativo e das duas extensões, passou em **oito testes XCTest sem falhas**, iniciou o OSTIE no simulador, capturou a tela e publicou o ZIP do app para simulador, o resultado de testes e o ícone. Esse commit inclui as melhorias de anexos, voz e preparo do ícone.
- A alteração posterior no workflow apenas antecipa a publicação do ZIP e limita o tempo de espera para a captura de tela; não muda o código do aplicativo. A execução desse novo workflow pode ser vista na [página Actions](https://github.com/HROSONE/OSTIE-IOS/actions/workflows/ios.yml).
- Os 77 arquivos em `android-reference/` podem ser conferidos contra os hashes do snapshot Android com `python3 scripts/verify_reference.py`.

## Como o bloqueio foi resolvido

A [execução 36079700728](https://github.com/HROSONE/OSTIE-IOS/actions/runs/36079700728), no commit `28b9bb34ebdfa7aed94bbab8cec380f7a5f057c6`, foi impedida de iniciar enquanto o repositório era privado. A mensagem do GitHub apontava falha de pagamento ou limite de gastos, sem distinguir a causa. Depois da autorização do proprietário para tornar o repositório público, uma nova tentativa iniciou no executor macOS e revelou uma falha no preparo do ícone; corrigimos a falha, e a execução 36084734232 passou. O executor padrão do GitHub Actions é gratuito e ilimitado para repositórios públicos.

O código, o histórico de commits e os logs do GitHub Actions agora estão visíveis publicamente. Não foram incluídas credenciais ou certificados no repositório; cada usuário informa suas chaves no aplicativo.

## Repetir a validação

1. Abrir **Actions → iOS build and tests → Run workflow**, na branch `main`, ou publicar uma alteração nas pastas `ios/` ou `scripts/`.
2. Conferir a compilação, os oito testes e o artefato **OSTIE-iOS-simulator**. A captura de tela é uma verificação adicional limitada por tempo; falhar nessa etapa não impede a publicação do ZIP se o build e os testes passarem.

Também é possível compilar em um Mac com Xcode 16 ou posterior, seguindo o preparo do ícone e a abertura do projeto descritos no README. A compilação local não depende do limite do GitHub Actions.

## Validação em aparelho e distribuição

Ainda é necessário testar em iPhone real as integrações com provedores, áudio Live, câmera, compartilhamento de tela, permissões e ações do sistema. Esses testes exigem as chaves do usuário e assinatura pela equipe Apple correspondente.

Não foi gerado um IPA assinado nem publicada uma versão no TestFlight ou na App Store. Os artefatos de simulador não são instaladores de iPhone. As adaptações e limitações em relação ao Android estão em [PARIDADE.md](PARIDADE.md).
