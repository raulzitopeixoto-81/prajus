# Ativar o login com Google e Apple

O código já está pronto. Faltam só as credenciais, que precisam ser criadas por você nas contas de desenvolvedor.
Os dois provedores exigem **HTTPS** com um domínio real (ex.: `https://app.seudominio.com.br`). A Apple não aceita `localhost`.

## 1. Google (gratuito)
1. Acesse console.cloud.google.com, crie um projeto e abra **APIs e serviços → Tela de consentimento OAuth** (tipo Externo, informe nome, e-mail e domínio).
2. Em **Credenciais → Criar credenciais → ID do cliente OAuth**, escolha **Aplicativo da Web**.
3. Em **Origens JavaScript autorizadas**, informe `https://app.seudominio.com.br`.
4. Copie o ID do cliente (termina em `.apps.googleusercontent.com`).

## 2. Apple (exige Apple Developer Program, pago)
1. Em developer.apple.com → **Certificates, Identifiers & Profiles → Identifiers**, crie um **App ID** com a capacidade *Sign in with Apple*.
2. Crie um **Services ID** (ex.: `br.com.prajus.web`), ative *Sign in with Apple* e clique em Configure:
   - Domínio: `app.seudominio.com.br`
   - Return URL: `https://app.seudominio.com.br/`
3. O Services ID é o seu `APPLE_CLIENT_ID`.

## 3. Variáveis do servidor
```bash
export GOOGLE_CLIENT_ID="123-abc.apps.googleusercontent.com"
export APPLE_CLIENT_ID="br.com.prajus.web"
export APPLE_REDIRECT_URI="https://app.seudominio.com.br/"
export PRAJUS_DB=/var/lib/prajus/prajus.db PRAJUS_UPLOADS=/var/lib/prajus/uploads
gunicorn -w 2 -b 127.0.0.1:8000 app:app      # atrás de Nginx/Caddy com HTTPS
```
Não defina `PRAJUS_DEV` em produção. Sem as variáveis, os botões aparecem desativados.

## Como funciona
O navegador recebe o ID token do Google ou da Apple e o envia a `POST /auth/oauth/<provedor>`. O servidor valida assinatura, emissor, público e validade, exige e-mail verificado pelo provedor, e então entra na conta existente ou cria o escritório após o aceite dos termos. Contas criadas assim ficam com senha aleatória; a pessoa pode definir uma em "Esqueci a senha".
