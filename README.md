# Prajus – Backend

API REST em Flask + SQLite para a plataforma de gestão jurídica Prajus.

## Executar

```bash
pip install -r requirements.txt
PRAJUS_DEV=1 python3 app.py            # http://127.0.0.1:5000
python3 tests/test_api.py              # testes automatizados
```

Produção: `gunicorn -w 2 app:app` atrás de HTTPS (proxy reverso).

| Variável | Função |
|---|---|
| `PRAJUS_DB` | Caminho do banco SQLite (padrão `prajus.db`) |
| `PRAJUS_UPLOADS` | Pasta dos documentos (padrão `uploads/`) |
| `PRAJUS_CORS_ORIGIN` | Origem do front-end autorizada (ex.: `https://app.prajus.com.br`) |
| `PRAJUS_DEV=1` | Devolve os tokens de e-mail na resposta, pois não há envio real de e-mail |

## Autenticação

`Authorization: Bearer <token>` em todas as rotas, exceto `/auth/*` (cadastro, login, verificação, recuperação), `/convites/aceitar` e `/saude`.

## Rotas principais

- **Auth:** `POST /auth/cadastro`, `/auth/verificar-email`, `/auth/login`, `/auth/logout`, `/auth/esqueci-senha`, `/auth/redefinir-senha`; `GET /auth/eu`; `GET|DELETE /auth/sessoes`; `POST /auth/mfa/configurar|ativar`
- **Usuários e convites (admin):** `POST|GET /convites`, `POST /convites/<id>/reenviar`, `DELETE /convites/<id>`, `POST /convites/aceitar`; `GET /usuarios`, `PATCH /usuarios/<id>`
- **Clientes, casos, itens (tarefa/prazo/compromisso):** `GET|POST /clientes|/casos|/itens`, `GET|PATCH|DELETE /<recurso>/<id>` (DELETE arquiva), `POST /<recurso>/<id>/restaurar`; filtros `?q=`, `?arquivados=1`, `?limite=&offset=`
- **Prazos:** `POST /itens/<id>/confirmar` (revisão humana; alterar a data zera a confirmação)
- **Caso:** `GET /casos/<id>/linha-do-tempo`
- **Documentos:** `POST /casos/<id>/documentos` (multipart, campo `arquivo`), `POST /documentos/<id>/versoes`, `GET /documentos`, `GET /documentos/<id>/versoes`, `GET /documentos/<id>/versoes/<n>/download`
- **Painel e busca:** `GET /hoje`, `GET /busca?q=`, `GET /notificacoes`, `POST /notificacoes/<id>/lida`
- **Admin:** `GET /auditoria`, `GET /exportar`, `POST /importar`

## Permissões (aplicadas no servidor)

| Função | Leitura | Criar/editar | Arquivar | Exportar | Importar, usuários, auditoria |
|---|---|---|---|---|---|
| admin | ✔ | ✔ | ✔ | ✔ | ✔ |
| advogado | ✔ | ✔ | ✔ | ✔ | – |
| assistente | ✔ | ✔ | – | – | – |
| leitura | ✔ | – | – | – | – |

## Garantias implementadas

- Isolamento por organização: toda tabela tem `org_id` e toda consulta o filtra; referências cruzadas entre organizações são rejeitadas.
- Senhas com scrypt, sessões com token aleatório guardado só como hash, expiração de 12 h, revogação e logout.
- Limite de 5 falhas de login em 15 min, resposta genérica para e-mail inexistente, MFA por TOTP opcional.
- Convites com validade de 7 dias, uso único, reenvio e revogação; o administrador nunca define a senha de outra pessoa.
- Exclusão sempre lógica (arquivamento); auditoria de ações; linha do tempo por caso; downloads auditados.
- Upload com lista de extensões permitidas, limite de 25 MB, nome interno aleatório e SHA-256 por versão.

## Limitações conhecidas

- Não envia e-mail: com `PRAJUS_DEV=1` os tokens saem na resposta; em produção é preciso ligar um serviço de e-mail em `register`, `forgot` e `invite`.
- SQLite e arquivos em disco local atendem escritórios pequenos; para escalar, troque por PostgreSQL e armazenamento de objetos.
- O e-mail é único no sistema inteiro (uma pessoa, uma organização).
- Não há cálculo automático de prazos processuais; todo prazo é digitado e exige confirmação humana.
- Falta antivírus nos uploads e backup automático.

## Identidade visual (Paleta A)

Logotipos em `static/brand/` (recortados do material de referência, sem alterações). O administrador troca logotipos e cores em **Aparência**; a escolha vale só para a sua organização.
Rotas: `GET /marca`, `PUT /marca/cores`, `POST|DELETE /marca/logo/<horizontal|vertical|icone>`, `POST /marca/restaurar` (escrita só para o perfil admin).
