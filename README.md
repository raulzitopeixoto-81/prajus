# Prajus – Gestão Jurídica (Android)

Aplicativo Android nativo em **Kotlin + Jetpack Compose + Material Design 3 + Firebase Auth & Cloud Firestore** para gestão jurídica de advogados autônomos e pequenos escritórios.

## Funcionalidades principais

- **Autenticação com Google e isolamento na nuvem:** Login via Google (`Credential Manager` + Firebase Authentication) com dados persistidos no **Cloud Firestore** e isolados por usuário (`users/{uid}`) por meio de regras de segurança validadas.
- **Painel Hoje:** Tarefas vencidas, compromissos/itens de hoje, prazos nos próximos 10 dias, casos sem próxima ação e geração automática de alertas para prazos em até 3 dias.
- **Prazos com revisão humana:** Prazos são criados como não revisados (`aguarda revisão`), exigem confirmação humana (`Confirmar prazo`) e zeram a confirmação caso a data seja alterada.
- **Casos, clientes e linha do tempo:** CRUD completo com arquivamento lógico e restauração, bloqueio de arquivamento de cliente com casos ativos, busca global e linha do tempo cronológica por caso.
- **Documentos versionados:** Upload via seletor de arquivos do Android (`OpenDocument`) com validação de extensão e limite de 25 MB, cálculo de SHA-256, metadados sincronizados no Firestore, histórico de versões e abertura/download auditados via `FileProvider`.
- **Equipe, convites e auditoria:** Gestão de membros e convites no escritório, log de auditoria, exportação JSON e personalização de logotipos (`PickVisualMedia`) e paleta de cores (Paleta A padrão `#1D2F4D`, `#2F5C8E`, `#CFA75B`).

## Compilação

```bash
gradle assembleDebug
```
