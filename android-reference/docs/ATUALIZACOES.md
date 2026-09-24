# Atualizações do OSTIE

O Android instala uma versão por cima da anterior somente quando o `applicationId` continua `com.osone.app`, o `versionCode` aumenta e o novo APK é assinado por uma chave compatível com a instalada. Cada execução da CI até agora produziu um APK de depuração com uma chave temporária. O botão de atualização não consegue substituir esse certificado nem contornar a aprovação do instalador do Android.

## Configurar uma assinatura permanente

1. Se você possuir a chave que assinou o APK atualmente instalado, use **essa mesma chave**. Caso não tenha, a primeira migração para a assinatura permanente exige desinstalar o APK antigo uma única vez; anote suas chaves de API antes de desinstalar, pois o Android apagará os dados.
2. Crie e guarde uma chave em lugar seguro, fora do repositório. Por exemplo, num computador com JDK: `keytool -genkeypair -v -keystore ostie.jks -alias ostie -keyalg RSA -keysize 4096 -validity 10000`. Guarde backups do arquivo e da senha; perder a chave impede futuras atualizações deste pacote.
3. No GitHub, abra **OSONE-APP → Settings → Secrets and variables → Actions → New repository secret** e configure `OSTIE_SIGNING_KEYSTORE_BASE64` com o conteúdo base64 da chave (`base64 -w 0 ostie.jks` no Linux), `OSTIE_SIGNING_PASSWORD` com a senha da chave e `OSTIE_SIGNING_ALIAS` com o alias (por exemplo, `ostie`). A CI usa a mesma senha para o keystore e a chave; escolha a mesma nas duas perguntas do `keytool`. Nunca envie o arquivo `.jks` nem esses valores ao Git.
4. Execute o workflow **Android** no GitHub Actions. Baixe apenas o artefato **ostie-release-assinado** para instalar e atualizar. O artefato **ostie-debug-assinatura-temporaria** é apenas para desenvolvimento; a assinatura muda em cada execução. Guarde também um backup seguro do primeiro APK assinado para comparar o certificado em versões futuras.
5. A cada lançamento, aumente o `versionCode` em `app/build.gradle.kts`, sem mudar o `applicationId` nem a chave.

## Atualização automática (canal oficial)

O repositório `OSONE-APP` é privado, então o celular não consegue baixar os Releases dele sem senha. A CI publica cada versão assinada no repositório público **`HROSONE/OSTIE-AI-releases`**, só com os APKs do OSTIE, sem código nem segredos.

Para não misturar os dois apps:

- cada versão do OSTIE vira um Release `ostie-v<versão>` marcado como **pré-lançamento** e nunca como *latest*, então o atualizador do OSONE desktop não o enxerga;
- o repositório de releases passa a se chamar `OSTIE-AI-releases`: a CI pergunta ao GitHub o nome atual antes de publicar, e o app tenta o nome novo e depois os anteriores;
- o nome de usuário no GitHub mudou de `zerobob623-bit` para `HROSONE`: a CI publica no dono atual do repositório, e o app tenta o endereço novo e, se falhar, o antigo (desde a versão que trouxe essa mudança);
- o app lê um Release fixo, `ostie-latest`, cujo `latest.json` é substituído a cada versão: `https://github.com/HROSONE/OSTIE-AI-releases/releases/download/ostie-latest/latest.json`.

Configuração, feita uma única vez:

1. Crie um token *fine-grained* em **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens**, com acesso somente ao repositório `OSTIE-AI-releases` e permissão **Contents: Read and write**.
2. Em **OSONE-APP → Settings → Secrets and variables → Actions**, crie o secret `OSTIE_RELEASES_TOKEN` com esse token. (Para usar outro repositório, crie a variável de Actions `OSTIE_RELEASES_REPO` com `dono/nome` e ajuste `UpdateFeed.OFFICIAL` no app.)
3. Faça merge na `main` ou rode o workflow **Android** manualmente. A CI numera a versão automaticamente (`versionCode = 100 + número da execução`), assina e publica `ostie-<versão>.apk` e o `latest.json`.
4. No celular, instale uma vez o artefato **ostie-release-assinado** (desinstalando antes uma versão de depuração, se for o caso). Daí em diante:
   - o app procura versões ao abrir (no máximo a cada 6 h) e uma vez por dia em segundo plano;
   - avisa com "Atualizar agora" ou por notificação; um toque baixa, confere SHA-256, pacote, versão e certificado, e instala;
   - no Android 12+, quando a versão anterior também foi instalada pelo próprio OSTIE, o Android pode aplicar a atualização sem pedir confirmação. Na primeira vez (e em versões anteriores do Android), confirme no instalador.

Em **Configurações → Atualizações** é possível desligar "Atualizar automaticamente", procurar agora e, em **Opções avançadas**, usar um canal próprio ou instalar um APK já baixado.

Formato do `latest.json` (gerado pela CI; útil para canais próprios):

```json
{
  "versionCode": 140,
  "versionName": "0.15.40",
  "apkUrl": "https://github.com/HROSONE/OSTIE-AI-releases/releases/download/ostie-v0.15.40/ostie-140.apk",
  "sha256": "64 dígitos hexadecimais do SHA-256 do APK",
  "notes": "Novidades desta versão"
}
```

Não coloque tokens no JSON, no link ou no APK.

Se o certificado do APK antigo for diferente do novo, o app avisará antes de tentar instalar. Não há atualização por cima sem uma chave compatível; antes de migrar, preserve suas chaves de API para cadastrar novamente.
