# Melhorias do OSTIE

Backlog mantido pelo comando `/aprimorar`. Cada sessão lê este arquivo antes de analisar o app e o atualiza no fim.

## Pendentes

Ideias levantadas e ainda não feitas (da mais valiosa para a menos):

- **Ferramentas no chat com Groq e OpenRouter**: hoje só o Gemini usa as ações do app no chat escrito; os dois aceitam o formato de ferramentas da OpenAI.
- **Widget na tela inicial**: iniciar o Live ou ver a próxima rotina sem abrir o app.
- **Rotinas por evento**: disparar ao carregar, ao chegar em casa (Wi-Fi) ou ao receber notificação de um app, além do horário.
- **Memória usada pelo Live em tempo real**: reler a memória ao reconectar a sessão e após uma organização automática.
- **Testes de tela do Live e dos Ajustes**: exigem controlar as animações infinitas (`mainClock.autoAdvance = false`).

## Feitos

| Versão | O que mudou |
| --- | --- |
| 0.15.58 | Live 3.x volta a funcionar (degraus de configuração por modelo), preview da Aba de Escrita corrigido |
| 0.15.60 | Pesquisa pelo modelo de texto no Live 3.x, retomada de sessão, legendas e conversa de voz no chat, CI mais econômica |
| 0.15.62 | Ações no chat escrito, rotinas que leem a agenda e deixam botões na notificação, memória que se organiza, eco calibrado por aparelho, chave do Live no cabeçalho |
| 0.15.66 | Testes de tela com Robolectric; checagem da pasta de memória não quebra sem armazenamento externo |
| 0.15.68 | Canal de atualizações no nome de usuário novo (`HROSONE`), com o antigo como reserva |
| 0.15.70 | Escuta ativa: "Ei, Ostie" abre o Live de qualquer tela (Vosk offline) |
| próxima | Respostas do chat faladas com Gemini 3.8 Flash TTS / Flash-Lite TTS; repositório de releases pronto para virar `OSTIE-AI-releases` |
