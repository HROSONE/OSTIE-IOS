---
name: aprimorar
description: Analisa o OSTIE inteiro, encontra os maiores ganhos (bugs, recursos, integração com o celular, qualidade) e os implementa em ciclos até publicar a atualização. Use quando o usuário pedir para aprimorar, melhorar, evoluir ou "ver o que dá para melhorar" no OSTIE, mesmo sem dizer o quê. Aceita "direto" (faz sem pedir confirmação) ou um tema (ex.: "Live", "memória", "visual").
---

# Aprimorar o OSTIE

O usuário quer que o OSTIE fique melhor e confia em você para descobrir como. Ele não precisa saber o que pedir. Seu papel é agir como dono do produto e engenheiro: entender o app, enxergar o potencial, escolher o que mais importa, implementar, publicar e contar o que fez.

Converse sempre em português do Brasil, em linguagem simples, sem jargão desnecessário.

## Modos

Leia o argumento do comando:

- **sem argumento** → modo **plano**: analise, apresente o plano e espere o "pode fazer" (ou a escolha de itens) antes de mexer no código.
- **`direto`** → modo **autônomo**: analise, escolha e implemente sem pedir confirmação; conte tudo no final.
- **um tema** (ex.: `Live`, `rotinas`, `visual`, `bateria`) → foque a análise nesse tema; combine com `direto` se vier junto (`/aprimorar direto Live`).

## 1. Entender o estado atual

1. Leia `CLAUDE.md` (contexto e regras do projeto) e `docs/MELHORIAS.md` (backlog das sessões anteriores, o que foi feito e o que ficou pendente).
2. Veja o que mudou desde a última vez: `git log --oneline -30` e PRs abertos.
3. Percorra o código em `app/src/main/java/com/osone/app/` com foco em: o que o usuário vê (telas), o que pode quebrar (rede, permissões, áudio, WebSocket, WorkManager), e o que falta para o OSTIE ser um agente de verdade no celular.
4. Se o usuário mandou print de erro ou diagnóstico nesta conversa, isso vem primeiro.

## 2. Encontrar melhorias

Procure em quatro frentes e seja ambicioso. Melhorias "radicais" são bem-vindas quando cabem em um ciclo:

- **Quebrado ou frágil**: bugs, falhas silenciosas, erros que o diagnóstico registra, casos sem tratamento.
- **Potencial não usado**: recursos da API Gemini/Live ainda não aproveitados, integrações do Android (intents, notificações, agenda, acessibilidade, widgets, atalhos), coisas que o OSTIE poderia fazer sozinho.
- **Experiência**: telas confusas, passos demais, textos pouco claros, visual inconsistente, acessibilidade.
- **Saúde do projeto**: testes faltando, código duplicado, custo de CI, segurança de chaves e dados.

Para cada ideia, estime **impacto** para o usuário (alto/médio/baixo), **esforço** (P/M/G) e **risco** (o que pode quebrar). Priorize alto impacto com esforço P/M. Descarte o que depende de coisas que você não consegue verificar sem avisar o usuário.

## 3. Plano

Monte uma lista numerada, da mais valiosa para a menos, agrupada em **lotes** que cabem num PR cada (3 a 6 itens relacionados). Para cada item: uma frase do que muda para o usuário e uma frase de como.

- Modo **plano**: mostre a lista e pergunte o que fazer ("pode fazer tudo", "faz 1 a 4", "tira o 3"). Pare aqui até a resposta.
- Modo **autônomo**: siga com o primeiro lote (e os seguintes, se couberem) sem perguntar.

Registre o plano em `docs/MELHORIAS.md` (seção "Pendentes"), para a próxima sessão saber o que ficou.

## 4. Ciclo de implementação (repita por lote)

1. Trabalhe na branch designada pela sessão; se o PR anterior dela já foi mesclado, recomece a branch a partir da `main`.
2. Implemente seguindo o estilo do código ao redor (Kotlin + Compose, comentários curtos em português, sem dependências novas sem motivo forte).
3. **Valide antes de enviar** (não há SDK Android no ambiente):
   - lógica pura nova → teste JVM em `app/src/test/`; rode-o num projeto Gradle JVM separado no scratchpad (copie só os arquivos puros), como descrito em `CLAUDE.md`;
   - releia o diff procurando erro de compilação: imports, tipos, nomes, parâmetros de funções alteradas em todos os chamadores;
   - telas novas ou alteradas → acrescente ou ajuste um teste em `ScreensTest.kt` (Robolectric).
4. Commit claro em português, push, PR com resumo e seção de testes honesta (o que foi e o que não foi testado).
5. Acompanhe a CI do PR (inscreva-se nos eventos do PR). Vermelha → leia o log, corrija na causa, envie de novo. Nunca desative teste.
6. Verde → mescle o PR, espere a publicação e confira `latest.json` do canal (versão nova e SHA-256 do APK batendo).
7. Atualize `docs/MELHORIAS.md`: mova os itens para "Feitos" com a versão em que saíram.

No modo autônomo, continue para o próximo lote até acabar a lista ou até encontrar algo que precise de decisão do usuário (aí pergunte e pare).

## 5. Relatório final

Conte ao usuário, curto e sem jargão:

- o que mudou para ele, item por item, e em qual versão já está disponível;
- o que precisa testar no celular (e como: onde tocar, o que falar);
- o que não foi possível verificar (ex.: chamadas reais à API, comportamento em aparelho);
- as próximas melhorias sugeridas (as que ficaram em "Pendentes").

## Limites

- **Minutos de CI**: cada ciclo custa cerca de 4 min no PR e de 5 a 6 min na `main`. Agrupe mudanças; mudanças só em documentação (`*.md`, `docs/`) não rodam CI.
- **Chaves e segredos**: nunca grave chaves, tokens ou dados pessoais em arquivos, commits ou logs. Se o usuário passar uma chave para teste, use só em memória na sessão.
- **Mudanças que exigem ação do usuário** (permissões novas, troca de conta, pagamento, configurações no GitHub): explique antes e deixe claro o passo a passo.
- **Nunca** apague dados do usuário (memória, rotinas, conversa) sem migração ou cópia de segurança.
