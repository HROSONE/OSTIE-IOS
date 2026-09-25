# Registro de validação

## Evidência confirmada

- A [execução 36053957320](https://github.com/HROSONE/OSTIE-IOS/actions/runs/36053957320), no commit `7c2d98b4db0ce84c7243737b60e96ba5fdaeffb0`, compilou o aplicativo e as duas extensões e passou nos cinco testes XCTest então existentes. Também publicou o aplicativo de simulador.
- Esse resultado se aplica àquele commit. As melhorias posteriores em anexos, voz e preparo do ícone não estão cobertas por essa execução.
- Os 77 arquivos em `android-reference/` podem ser conferidos contra os hashes do snapshot Android com `python3 scripts/verify_reference.py`.

## Bloqueio atual

A [execução 36079700728](https://github.com/HROSONE/OSTIE-IOS/actions/runs/36079700728), no commit `28b9bb34ebdfa7aed94bbab8cec380f7a5f057c6`, não iniciou nenhuma etapa. O GitHub informa que pagamentos recentes falharam ou que o limite de gastos precisa ser aumentado e orienta consultar **Billing & plans**. A mensagem não permite distinguir qual das duas condições se aplica.

Esse commit moveu a geração do ícone para uma etapa anterior ao Xcode, para resolver as falhas de SDK e sandbox observadas nas tentativas anteriores. A correção está publicada, mas ainda não foi comprovada em uma nova compilação completa.

## Retomar a validação

1. O titular da conta deve verificar o aviso em Billing & plans e restabelecer a disponibilidade do GitHub Actions. Este projeto não altera cobrança, limites ou meios de pagamento.
2. Após resolver o bloqueio, abrir **Actions → iOS build and tests → Run workflow**, na branch `main`.
3. Conferir compilação, XCTest, captura do simulador e artefatos. Corrigir qualquer erro que essa execução revelar antes de considerar a versão atual validada.

Também é possível compilar em um Mac com Xcode 16 ou posterior, seguindo o preparo do ícone e a abertura do projeto descritos no README. A compilação local não depende do limite do GitHub Actions.

## Validação em aparelho e distribuição

Ainda é necessário testar em iPhone real as integrações com provedores, áudio Live, câmera, compartilhamento de tela, permissões e ações do sistema. Esses testes exigem as chaves do usuário e assinatura pela equipe Apple correspondente.

Não foi gerado um IPA assinado nem publicada uma versão no TestFlight ou na App Store. Os artefatos de simulador não são instaladores de iPhone. As adaptações e limitações em relação ao Android estão em [PARIDADE.md](PARIDADE.md).
