# Prajus – Gestão Jurídica (Android & Web)

Sistema completo de gestão jurídica para advogados autônomos e pequenos escritórios com aplicativo Android nativo (**Kotlin + Jetpack Compose + Material Design 3**) e versão web para navegadores (`web/`), ambos sincronizados em tempo real pelo **Firebase Authentication** e **Cloud Firestore**.

## Funcionalidades principais

- **Sincronização em tempo real (Android + Web):** Mesma estrutura de dados e regras de segurança no Cloud Firestore (`users/{uid}`), permitindo trabalhar no computador e consultar no celular instantaneamente.
- **Autenticação:** Login com Google no aplicativo Android (`Credential Manager`) e suporte na versão web para **Google**, **Apple** e **e-mail com senha** (incluindo criação de conta e recuperação de senha).
- **Painel Hoje:** Tarefas vencidas, compromissos/itens de hoje, prazos nos próximos 10 dias, casos sem próxima ação e alertas automáticos para prazos em até 3 dias.
- **Casos em grade e linha do tempo:** Central de casos em grade adaptativa com status, cliente, próximo prazo destacado por urgência, revisão humana obrigatória para prazos e linha do tempo cronológica.
- **Clientes, Documentos, Equipe, Auditoria e Aparência:** CRUD completo, envio de documentos com cálculo SHA-256 e histórico de versões, gestão de membros e convites, log de auditoria com exportação/importação JSON e personalização de paleta de cores e logotipos.

## Publicação e uso da versão Web (`web/`)

1. No **Console Firebase** do projeto `gen-lang-client-0019377953`:
   - Em **Authentication -> Sign-in method**, ative os provedores **E-mail/senha** e **Apple** (além do **Google** já configurado).
   - Em **Configurações do projeto -> Seus aplicativos**, adicione um aplicativo **Web** (caso ainda não exista) e copie o `appId` para `web/js/firebase-config.js` ou informe-o diretamente pelo botão *Configurar Web App ID* na tela de login.
   - Em **Authentication -> Settings -> Authorized domains**, garanta que o domínio de hospedagem esteja autorizado.
2. Para publicar no Firebase Hosting ou testar localmente:

```bash
firebase deploy --only hosting
# ou localmente:
npx serve web
```

## Compilação Android

```bash
gradle assembleDebug
```
