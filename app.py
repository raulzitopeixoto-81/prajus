"""Prajus – API de gestão jurídica (Flask + SQLite).
Multi-organização: toda tabela carrega org_id e toda consulta o filtra."""
import base64, hashlib, hmac, json, os, re, secrets, sqlite3, struct, time, uuid
from datetime import date, datetime, timedelta, timezone
from functools import wraps
from flask import Flask, g, jsonify, request, send_file
from werkzeug.utils import secure_filename

DB = os.environ.get("PRAJUS_DB", "prajus.db")
UPLOADS = os.environ.get("PRAJUS_UPLOADS", "uploads")
DEV = os.environ.get("PRAJUS_DEV") == "1"          # devolve tokens na resposta (sem e-mail real)
CORS = os.environ.get("PRAJUS_CORS_ORIGIN", "")
SESSION_H, INVITE_D, RESET_H = 12, 7, 1
EXTS = {"pdf", "doc", "docx", "xls", "xlsx", "odt", "png", "jpg", "jpeg", "txt"}

PERMS = {  # matriz aplicada no backend
    "admin": {"read", "write", "archive", "export", "import", "users", "audit", "brand"},
    "advogado": {"read", "write", "archive", "export"},
    "assistente": {"read", "write"},
    "leitura": {"read"},
}
SCHEMA = """
CREATE TABLE IF NOT EXISTS orgs(id INTEGER PRIMARY KEY, nome TEXT NOT NULL, created_at TEXT);
CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL REFERENCES orgs(id), nome TEXT NOT NULL,
 email TEXT NOT NULL UNIQUE, pw TEXT NOT NULL, role TEXT NOT NULL, oab TEXT, email_verified INTEGER DEFAULT 0,
 mfa_secret TEXT, mfa_on INTEGER DEFAULT 0, status TEXT DEFAULT 'ativo', created_at TEXT);
CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, org_id INTEGER NOT NULL,
 token_hash TEXT UNIQUE NOT NULL, created_at TEXT, expires_at TEXT, revoked_at TEXT, ua TEXT);
CREATE TABLE IF NOT EXISTS tokens(id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, kind TEXT NOT NULL,
 token_hash TEXT UNIQUE NOT NULL, expires_at TEXT, used_at TEXT);
CREATE TABLE IF NOT EXISTS invites(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, nome TEXT, email TEXT NOT NULL, role TEXT NOT NULL,
 token_hash TEXT UNIQUE NOT NULL, expires_at TEXT, accepted_at TEXT, revoked_at TEXT, created_by INTEGER, created_at TEXT);
CREATE TABLE IF NOT EXISTS login_attempts(email TEXT, ip TEXT, at REAL);
CREATE TABLE IF NOT EXISTS clients(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, nome TEXT NOT NULL, doc TEXT, email TEXT, tel TEXT,
 archived_at TEXT, created_at TEXT, updated_at TEXT);
CREATE TABLE IF NOT EXISTS cases(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, client_id INTEGER NOT NULL, titulo TEXT NOT NULL,
 numero TEXT, area TEXT, status TEXT DEFAULT 'Ativo', resp_id INTEGER, prox_acao TEXT, prox_data TEXT, obs TEXT,
 archived_at TEXT, created_at TEXT, updated_at TEXT);
CREATE TABLE IF NOT EXISTS items(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, case_id INTEGER NOT NULL, tipo TEXT NOT NULL,
 titulo TEXT NOT NULL, data TEXT NOT NULL, resp_id INTEGER, status TEXT DEFAULT 'Pendente', prio TEXT DEFAULT 'Média',
 origem TEXT, revisado INTEGER DEFAULT 0, archived_at TEXT, created_at TEXT, updated_at TEXT);
CREATE TABLE IF NOT EXISTS documents(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, case_id INTEGER NOT NULL, nome TEXT NOT NULL,
 tipo TEXT, archived_at TEXT, created_at TEXT);
CREATE TABLE IF NOT EXISTS doc_versions(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, doc_id INTEGER NOT NULL, n INTEGER NOT NULL,
 path TEXT NOT NULL, sha256 TEXT, size INTEGER, por_id INTEGER, created_at TEXT, UNIQUE(doc_id,n));
CREATE TABLE IF NOT EXISTS timeline(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, case_id INTEGER NOT NULL, txt TEXT, user_id INTEGER, at TEXT);
CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, user_id INTEGER NOT NULL, txt TEXT,
 key TEXT, lida INTEGER DEFAULT 0, created_at TEXT, UNIQUE(user_id,key));
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, org_id INTEGER NOT NULL, user_id INTEGER, acao TEXT, entity TEXT, entity_id INTEGER, ip TEXT, at TEXT);
CREATE INDEX IF NOT EXISTS i_cases ON cases(org_id,status,archived_at);
CREATE INDEX IF NOT EXISTS i_items ON items(org_id,data,status);
CREATE INDEX IF NOT EXISTS i_docs ON documents(org_id,case_id);
CREATE INDEX IF NOT EXISTS i_tl ON timeline(org_id,case_id);
CREATE INDEX IF NOT EXISTS i_aud ON audit(org_id,at);
CREATE TABLE IF NOT EXISTS branding(org_id INTEGER PRIMARY KEY, cores TEXT, logo_horizontal TEXT, logo_vertical TEXT, icone TEXT, updated_at TEXT);
"""

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 25 * 1024 * 1024

# ---------- infraestrutura ----------
def now(): return datetime.now(timezone.utc).isoformat(timespec="seconds")
def later(**kw): return (datetime.now(timezone.utc) + timedelta(**kw)).isoformat(timespec="seconds")
def sha(s): return hashlib.sha256(s.encode()).hexdigest()
def db():
    if "db" not in g:
        g.db = sqlite3.connect(DB); g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys=ON")
    return g.db
def init_db():
    c = sqlite3.connect(DB); c.executescript(SCHEMA); c.commit(); c.close(); os.makedirs(UPLOADS, exist_ok=True)
@app.teardown_appcontext
def _close(_):
    d = g.pop("db", None)
    if d: d.close()
def rows(sql, *a): return [dict(r) for r in db().execute(sql, a).fetchall()]
def one(sql, *a):
    r = db().execute(sql, a).fetchone(); return dict(r) if r else None
def run(sql, *a):
    c = db().execute(sql, a); db().commit(); return c.lastrowid

class ApiError(Exception):
    def __init__(s, code, msg, **extra): s.code, s.msg, s.extra = code, msg, extra
@app.errorhandler(ApiError)
def _api(e): return jsonify(erro=e.msg, **e.extra), e.code
@app.errorhandler(413)
def _big(_): return jsonify(erro="Arquivo maior que 25 MB."), 413
@app.errorhandler(404)
def _nf(_): return jsonify(erro="Não encontrado."), 404
@app.errorhandler(405)
def _na(_): return jsonify(erro="Método não permitido."), 405
@app.errorhandler(Exception)
def _500(e):
    app.logger.exception(e); return jsonify(erro="Erro interno."), 500

@app.after_request
def _headers(r):
    r.headers.update({"X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY", "Referrer-Policy": "no-referrer",
                      "Cache-Control": "no-store", "Strict-Transport-Security": "max-age=31536000"})
    if CORS and request.headers.get("Origin") == CORS:
        r.headers.update({"Access-Control-Allow-Origin": CORS, "Access-Control-Allow-Headers": "Authorization, Content-Type",
                          "Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS", "Vary": "Origin"})
    return r

def body():
    d = request.get_json(silent=True)
    if not isinstance(d, dict): raise ApiError(400, "Corpo JSON inválido.")
    return d
def ip(): return request.headers.get("X-Forwarded-For", request.remote_addr or "").split(",")[0].strip()

def audit(acao, entity=None, eid=None, org=None, uid=None):
    run("INSERT INTO audit(org_id,user_id,acao,entity,entity_id,ip,at) VALUES(?,?,?,?,?,?,?)",
        org or g.org, uid or getattr(g, "uid", None), acao, entity, eid, ip(), now())
def timeline(case_id, txt):
    run("INSERT INTO timeline(org_id,case_id,txt,user_id,at) VALUES(?,?,?,?,?)", g.org, case_id, txt, g.uid, now())

# ---------- senhas, TOTP ----------
def hash_pw(p):
    salt = secrets.token_bytes(16)
    return "scrypt$%s$%s" % (salt.hex(), hashlib.scrypt(p.encode(), salt=salt, n=2**14, r=8, p=1).hex())
def check_pw(p, stored):
    try: _, s, h = stored.split("$")
    except ValueError: return False
    return hmac.compare_digest(hashlib.scrypt(p.encode(), salt=bytes.fromhex(s), n=2**14, r=8, p=1).hex(), h)
DUMMY = hash_pw("dummy-password")
def strong(p):
    if not isinstance(p, str) or len(p) < 10 or not re.search(r"[A-Za-z]", p) or not re.search(r"\d", p):
        raise ApiError(422, "A senha deve ter ao menos 10 caracteres, com letras e números.")
def totp(secret, t=None):
    c = int((t or time.time()) // 30)
    h = hmac.new(base64.b32decode(secret), struct.pack(">Q", c), "sha1").digest()
    o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 10**6)
def totp_ok(secret, code):
    return any(hmac.compare_digest(totp(secret, time.time() + d * 30), str(code or "")) for d in (-1, 0, 1))
EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
def norm_email(e):
    e = (e or "").strip().lower()
    if not EMAIL.match(e) or len(e) > 254: raise ApiError(422, "E-mail inválido.")
    return e

# ---------- autenticação / autorização ----------
def auth(perm=None):
    def deco(f):
        @wraps(f)
        def w(*a, **k):
            h = request.headers.get("Authorization", "")
            if not h.startswith("Bearer "): raise ApiError(401, "Não autenticado.")
            s = one("""SELECT s.id sid,u.id uid,u.org_id,u.role,u.nome FROM sessions s JOIN users u ON u.id=s.user_id
                       WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>? AND u.status='ativo'""", sha(h[7:]), now())
            if not s: raise ApiError(401, "Sessão inválida ou expirada.")
            g.sid, g.uid, g.org, g.role, g.uname = s["sid"], s["uid"], s["org_id"], s["role"], s["nome"]
            if perm and perm not in PERMS[g.role]: raise ApiError(403, "Sem permissão para esta ação.")
            return f(*a, **k)
        return w
    return deco

def new_session(u):
    tok = secrets.token_urlsafe(32)
    run("INSERT INTO sessions(user_id,org_id,token_hash,created_at,expires_at,ua) VALUES(?,?,?,?,?,?)",
        u["id"], u["org_id"], sha(tok), now(), later(hours=SESSION_H), (request.user_agent.string or "")[:200])
    return tok
def new_token(uid, kind, **life):
    tok = secrets.token_urlsafe(32)
    run("INSERT INTO tokens(user_id,kind,token_hash,expires_at) VALUES(?,?,?,?)", uid, kind, sha(tok), later(**life))
    return tok
def use_token(tok, kind):
    t = one("SELECT * FROM tokens WHERE token_hash=? AND kind=? AND used_at IS NULL AND expires_at>?", sha(tok or ""), kind, now())
    if not t: raise ApiError(400, "Link inválido ou expirado.")
    run("UPDATE tokens SET used_at=? WHERE id=?", now(), t["id"]); return t

@app.post("/auth/cadastro")
def register():
    d = body(); email = norm_email(d.get("email")); strong(d.get("senha"))
    if d.get("senha") != d.get("confirmacao"): raise ApiError(422, "A confirmação da senha não confere.")
    if not (d.get("nome") or "").strip() or not (d.get("organizacao") or "").strip(): raise ApiError(422, "Informe seu nome e o nome do escritório.")
    if not (d.get("termos") and d.get("privacidade")): raise ApiError(422, "Aceite os termos de uso e a política de privacidade.")
    out = {"mensagem": "Se o cadastro for possível, enviaremos um e-mail de confirmação."}
    if not one("SELECT id FROM users WHERE email=?", email):  # resposta idêntica evita enumerar e-mails
        oid = run("INSERT INTO orgs(nome,created_at) VALUES(?,?)", d["organizacao"].strip()[:200], now())
        uid = run("INSERT INTO users(org_id,nome,email,pw,role,oab,created_at) VALUES(?,?,?,?,?,?,?)",
                  oid, d["nome"].strip()[:200], email, hash_pw(d["senha"]), "admin", (d.get("oab") or "")[:20], now())
        tok = new_token(uid, "verify", days=2)
        audit("Cadastro da organização", "org", oid, org=oid, uid=uid)
        if DEV: out["token_verificacao_dev"] = tok
    return jsonify(out), 202

@app.post("/auth/verificar-email")
def verify():
    t = use_token(body().get("token"), "verify")
    run("UPDATE users SET email_verified=1 WHERE id=?", t["user_id"]); return jsonify(ok=True)

@app.post("/auth/login")
def login():
    d = body(); email = (d.get("email") or "").strip().lower(); t = time.time()
    recent = one("SELECT COUNT(*) n FROM login_attempts WHERE (email=? OR ip=?) AND at>?", email, ip(), t - 900)["n"]
    if recent >= 5: raise ApiError(429, "Muitas tentativas. Aguarde alguns minutos.")
    u = one("SELECT * FROM users WHERE email=?", email)
    ok = check_pw(str(d.get("senha") or ""), u["pw"] if u else DUMMY) and u and u["status"] == "ativo"
    if ok and u["mfa_on"] and not totp_ok(u["mfa_secret"], d.get("codigo")):
        if not d.get("codigo"): raise ApiError(401, "Informe o código de autenticação.", mfa=True)
        ok = False
    if not ok:
        run("INSERT INTO login_attempts VALUES(?,?,?)", email, ip(), t); raise ApiError(401, "Credenciais inválidas.")
    if not u["email_verified"]: raise ApiError(403, "Confirme seu e-mail antes de entrar.")
    g.uid = u["id"]; tok = new_session(u); audit("Login", "user", u["id"], org=u["org_id"], uid=u["id"])
    return jsonify(token=tok, usuario=public_user(u))

def public_user(u): return {k: u[k] for k in ("id", "nome", "email", "role", "oab", "mfa_on", "org_id")}

@app.get("/auth/eu")
@auth()
def me():
    return jsonify(usuario=public_user(one("SELECT * FROM users WHERE id=?", g.uid)), permissoes=sorted(PERMS[g.role]))

@app.post("/auth/logout")
@auth()
def logout():
    run("UPDATE sessions SET revoked_at=? WHERE id=?", now(), g.sid); audit("Logout"); return jsonify(ok=True)

@app.get("/auth/sessoes")
@auth()
def sessions():
    return jsonify(rows("SELECT id,created_at,expires_at,ua,(id=?) atual FROM sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>?",
                        g.sid, g.uid, now()))
@app.delete("/auth/sessoes/<int:sid>")
@auth()
def revoke_session(sid):
    run("UPDATE sessions SET revoked_at=? WHERE id=? AND user_id=?", now(), sid, g.uid); audit("Sessão revogada", "session", sid); return jsonify(ok=True)

@app.post("/auth/esqueci-senha")
def forgot():
    u = one("SELECT id FROM users WHERE email=?", (body().get("email") or "").strip().lower())
    out = {"mensagem": "Se o e-mail existir, enviaremos instruções."}
    if u:
        tok = new_token(u["id"], "reset", hours=RESET_H)
        if DEV: out["token_dev"] = tok
    return jsonify(out), 202

@app.post("/auth/redefinir-senha")
def reset():
    d = body(); strong(d.get("senha")); t = use_token(d.get("token"), "reset")
    run("UPDATE users SET pw=? WHERE id=?", hash_pw(d["senha"]), t["user_id"])
    run("UPDATE sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now(), t["user_id"])
    return jsonify(ok=True)

@app.post("/auth/mfa/configurar")
@auth()
def mfa_setup():
    sec = base64.b32encode(secrets.token_bytes(20)).decode()
    run("UPDATE users SET mfa_secret=?,mfa_on=0 WHERE id=?", sec, g.uid)
    return jsonify(segredo=sec, uri=f"otpauth://totp/Prajus:{g.uid}?secret={sec}&issuer=Prajus")
@app.post("/auth/mfa/ativar")
@auth()
def mfa_on():
    u = one("SELECT mfa_secret FROM users WHERE id=?", g.uid)
    if not u["mfa_secret"] or not totp_ok(u["mfa_secret"], body().get("codigo")): raise ApiError(400, "Código inválido.")
    run("UPDATE users SET mfa_on=1 WHERE id=?", g.uid); audit("MFA ativado", "user", g.uid); return jsonify(ok=True)

# ---------- login social (Google / Apple) ----------
OAUTH = {"google": dict(jwks="https://www.googleapis.com/oauth2/v3/certs", iss=("https://accounts.google.com", "accounts.google.com"), env="GOOGLE_CLIENT_ID"),
         "apple": dict(jwks="https://appleid.apple.com/auth/keys", iss=("https://appleid.apple.com",), env="APPLE_CLIENT_ID")}
def verify_oauth(provider, id_token):
    """Valida assinatura (RS256), emissor, audiência e validade do ID token do provedor; devolve os claims."""
    import jwt
    cfg = OAUTH[provider]; aud = os.environ.get(cfg["env"])
    if not aud: raise ApiError(503, f"Login com {provider.capitalize()} não está configurado no servidor.")
    try:
        key = jwt.PyJWKClient(cfg["jwks"]).get_signing_key_from_jwt(id_token).key
        claims = jwt.decode(id_token, key, algorithms=["RS256"], audience=aud, options={"require": ["exp", "iss", "sub"]})
    except Exception: raise ApiError(401, "Token do provedor inválido.")
    if claims.get("iss") not in cfg["iss"]: raise ApiError(401, "Token do provedor inválido.")
    return claims

@app.post("/auth/oauth/<provider>")
def oauth(provider):
    if provider not in OAUTH: raise ApiError(404, "Provedor desconhecido.")
    d = body(); cl = verify_oauth(provider, d.get("id_token") or "")
    email = (cl.get("email") or "").strip().lower()
    if not email or str(cl.get("email_verified")).lower() != "true": raise ApiError(401, "O provedor não confirmou o e-mail desta conta.")
    u = one("SELECT * FROM users WHERE email=?", email)
    if u:
        if u["status"] != "ativo": raise ApiError(401, "Credenciais inválidas.")
        if u["mfa_on"] and not totp_ok(u["mfa_secret"], d.get("codigo")):
            raise ApiError(401, "Informe o código de autenticação.", mfa=True)
        if not u["email_verified"]: run("UPDATE users SET email_verified=1 WHERE id=?", u["id"])
    else:  # primeiro acesso: cria o escritório, mas só após aceite dos termos
        if not (d.get("termos") and d.get("privacidade")):
            raise ApiError(428, "Aceite os termos de uso e a política de privacidade para criar a conta.", precisa_cadastro=True, email=email)
        nome = (d.get("nome") or cl.get("name") or email.split("@")[0]).strip()[:200]
        oid = run("INSERT INTO orgs(nome,created_at) VALUES(?,?)", (d.get("organizacao") or f"Escritório de {nome}").strip()[:200], now())
        uid = run("INSERT INTO users(org_id,nome,email,pw,role,email_verified,created_at) VALUES(?,?,?,?,?,1,?)",
                  oid, nome, email, hash_pw(secrets.token_urlsafe(32)), "admin", now())  # senha aleatória: acesso só pelo provedor
        audit(f"Cadastro via {provider.capitalize()}", "org", oid, org=oid, uid=uid)
        u = one("SELECT * FROM users WHERE id=?", uid)
    g.uid = u["id"]; tok = new_session(u)
    audit(f"Login via {provider.capitalize()}", "user", u["id"], org=u["org_id"], uid=u["id"])
    return jsonify(token=tok, usuario=public_user(u))

# ---------- convites e usuários ----------
@app.post("/convites")
@auth("users")
def invite():
    d = body(); email = norm_email(d.get("email"))
    if d.get("role") not in PERMS: raise ApiError(422, "Função inválida.")
    if one("SELECT id FROM users WHERE email=?", email): raise ApiError(409, "Este e-mail já possui conta.")
    tok = secrets.token_urlsafe(32)
    i = run("INSERT INTO invites(org_id,nome,email,role,token_hash,expires_at,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
            g.org, (d.get("nome") or "")[:200], email, d["role"], sha(tok), later(days=INVITE_D), g.uid, now())
    audit("Convite criado", "invite", i)
    return jsonify(id=i, **({"token_dev": tok} if DEV else {})), 201
@app.get("/convites")
@auth("users")
def invites():
    return jsonify(rows("SELECT id,nome,email,role,expires_at,accepted_at,revoked_at FROM invites WHERE org_id=? ORDER BY id DESC", g.org))
@app.post("/convites/<int:i>/reenviar")
@auth("users")
def reinvite(i):
    inv = one("SELECT * FROM invites WHERE id=? AND org_id=? AND accepted_at IS NULL AND revoked_at IS NULL", i, g.org)
    if not inv: raise ApiError(404, "Convite não encontrado.")
    tok = secrets.token_urlsafe(32)
    run("UPDATE invites SET token_hash=?,expires_at=? WHERE id=?", sha(tok), later(days=INVITE_D), i); audit("Convite reenviado", "invite", i)
    return jsonify(ok=True, **({"token_dev": tok} if DEV else {}))
@app.delete("/convites/<int:i>")
@auth("users")
def revoke_invite(i):
    run("UPDATE invites SET revoked_at=? WHERE id=? AND org_id=? AND accepted_at IS NULL", now(), i, g.org); audit("Convite revogado", "invite", i)
    return jsonify(ok=True)
@app.post("/convites/aceitar")
def accept():
    d = body(); strong(d.get("senha"))
    inv = one("SELECT * FROM invites WHERE token_hash=? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at>?", sha(d.get("token") or ""), now())
    if not inv: raise ApiError(400, "Convite inválido ou expirado.")
    nome = (d.get("nome") or inv["nome"] or "").strip()
    if not nome: raise ApiError(422, "Informe seu nome.")
    # o usuário define a própria senha; o link enviado ao e-mail confirma o endereço
    uid = run("INSERT INTO users(org_id,nome,email,pw,role,email_verified,created_at) VALUES(?,?,?,?,?,1,?)",
              inv["org_id"], nome, inv["email"], hash_pw(d["senha"]), inv["role"], now())
    run("UPDATE invites SET accepted_at=? WHERE id=?", now(), inv["id"])
    audit("Convite aceito", "user", uid, org=inv["org_id"], uid=uid)
    return jsonify(ok=True), 201

@app.get("/usuarios")
@auth("read")
def users():
    return jsonify(rows("SELECT id,nome,email,role,status FROM users WHERE org_id=?", g.org))
@app.patch("/usuarios/<int:uid>")
@auth("users")
def patch_user(uid):
    d = body(); u = one("SELECT * FROM users WHERE id=? AND org_id=?", uid, g.org)
    if not u: raise ApiError(404, "Usuário não encontrado.")
    role, status = d.get("role", u["role"]), d.get("status", u["status"])
    if role not in PERMS or status not in ("ativo", "inativo"): raise ApiError(422, "Dados inválidos.")
    if u["role"] == "admin" and (role != "admin" or status != "ativo") and \
       one("SELECT COUNT(*) n FROM users WHERE org_id=? AND role='admin' AND status='ativo'", g.org)["n"] <= 1:
        raise ApiError(409, "A organização precisa de ao menos um administrador ativo.")
    run("UPDATE users SET role=?,status=? WHERE id=?", role, status, uid)
    if status == "inativo": run("UPDATE sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now(), uid)
    audit(f"Usuário alterado ({role}/{status})", "user", uid); return jsonify(ok=True)

# ---------- CRUD genérico (clientes, casos, itens) ----------
def S(req=False, **kw): return dict(req=req, **kw)
SPEC = {
 "clientes": dict(t="clients", label="cliente", f={"nome": S(True, max=200), "doc": S(max=30), "email": S(kind="email"), "tel": S(max=30)},
                  search=["nome", "doc", "email"]),
 "casos": dict(t="cases", label="caso", f={"titulo": S(True, max=300), "numero": S(max=40), "area": S(max=80),
        "status": S(enum=["Ativo", "Aguardando", "Encerrado"]), "client_id": S(True, ref="clients"), "resp_id": S(ref="users"),
        "prox_acao": S(max=300), "prox_data": S(kind="date"), "obs": S(max=5000)}, search=["titulo", "numero", "obs"]),
 "itens": dict(t="items", label="item", f={"case_id": S(True, ref="cases"), "tipo": S(True, enum=["Tarefa", "Prazo", "Compromisso"]),
        "titulo": S(True, max=300), "data": S(True, kind="date"), "resp_id": S(ref="users"),
        "status": S(enum=["Pendente", "Concluída", "Cancelada"]), "prio": S(enum=["Baixa", "Média", "Alta"]), "origem": S(max=300)}, search=["titulo"]),
}
def clean(spec, d, partial=False):
    out, err = {}, {}
    for k, s in spec["f"].items():
        if k not in d:
            if s["req"] and not partial: err[k] = "Campo obrigatório."
            continue
        v = d[k]
        if v in (None, "") and not s["req"]: out[k] = None; continue
        try:
            if "ref" in s:
                if not isinstance(v, int) or not one(f"SELECT id FROM {s['ref']} WHERE id=? AND org_id=?", v, g.org): raise ValueError("Referência inválida.")
            elif not isinstance(v, str) or not v.strip(): raise ValueError("Campo obrigatório." if s["req"] else "Texto inválido.")
            elif "enum" in s and v not in s["enum"]: raise ValueError("Valor inválido.")
            elif s.get("kind") == "date":
                try: date.fromisoformat(v)
                except ValueError: raise ValueError("Data inválida (use AAAA-MM-DD).")
            elif s.get("kind") == "email": v = norm_email(v)
            elif len(v) > s.get("max", 500): raise ValueError("Texto muito longo.")
            out[k] = v.strip() if isinstance(v, str) else v
        except ApiError: err[k] = "E-mail inválido."
        except ValueError as e: err[k] = str(e)
    if err: raise ApiError(422, "Dados inválidos.", campos=err)
    return out
def case_of(name, row): return row["id"] if name == "casos" else row.get("case_id")

def make_routes(name, sp):
    t = sp["t"]
    def lst():
        w, a = ["org_id=?"], [g.org]
        w.append("archived_at IS NOT NULL" if request.args.get("arquivados") == "1" else "archived_at IS NULL")
        for k in sp["f"]:
            if k in request.args and k != "titulo": w.append(f"{k}=?"); a.append(request.args[k])
        q = request.args.get("q")
        if q: w.append("(" + " OR ".join(f"{c} LIKE ?" for c in sp["search"]) + ")"); a += [f"%{q}%"] * len(sp["search"])
        lim = min(int(request.args.get("limite", 50) or 50), 100); off = max(int(request.args.get("offset", 0) or 0), 0)
        order = "data" if t == "items" else "id DESC"
        total = one(f"SELECT COUNT(*) n FROM {t} WHERE " + " AND ".join(w), *a)["n"]
        return jsonify(total=total, itens=rows(f"SELECT * FROM {t} WHERE " + " AND ".join(w) + f" ORDER BY {order} LIMIT ? OFFSET ?", *a, lim, off))
    def create():
        c = clean(sp, body()); c.update(org_id=g.org, created_at=now(), updated_at=now())
        if t == "items" and c["tipo"] == "Prazo": c["revisado"] = 0
        i = run(f"INSERT INTO {t}({','.join(c)}) VALUES({','.join('?'*len(c))})", *c.values())
        audit(f"Criou {sp['label']}", t, i); row = one(f"SELECT * FROM {t} WHERE id=?", i)
        if case_of(name, row): timeline(case_of(name, row), f"{sp['label'].capitalize()} criado: {row.get('titulo')}")
        return jsonify(row), 201
    def get(i):
        r = one(f"SELECT * FROM {t} WHERE id=? AND org_id=?", i, g.org)
        if not r: raise ApiError(404, "Não encontrado.")
        return jsonify(r)
    def patch(i):
        old = one(f"SELECT * FROM {t} WHERE id=? AND org_id=?", i, g.org)
        if not old: raise ApiError(404, "Não encontrado.")
        c = clean(sp, body(), partial=True)
        if not c: raise ApiError(422, "Nada para alterar.")
        if t == "items" and old["tipo"] == "Prazo" and "data" in c and c["data"] != old["data"]: c["revisado"] = 0  # mudou: exige nova revisão
        c["updated_at"] = now()
        run(f"UPDATE {t} SET {','.join(k+'=?' for k in c)} WHERE id=? AND org_id=?", *c.values(), i, g.org)
        audit(f"Alterou {sp['label']}: " + ", ".join(k for k in c if k != "updated_at"), t, i)
        new = one(f"SELECT * FROM {t} WHERE id=?", i)
        if case_of(name, new): timeline(case_of(name, new), f"{sp['label'].capitalize()} alterado: {new.get('titulo')}")
        return jsonify(new)
    def archive(i, undo=False):
        r = one(f"SELECT * FROM {t} WHERE id=? AND org_id=?", i, g.org)
        if not r: raise ApiError(404, "Não encontrado.")
        if name == "clientes" and not undo and one("SELECT id FROM cases WHERE client_id=? AND org_id=? AND archived_at IS NULL", i, g.org):
            raise ApiError(409, "Arquive os casos do cliente primeiro.")
        run(f"UPDATE {t} SET archived_at=? WHERE id=?", None if undo else now(), i)  # nunca exclui de verdade
        audit(("Restaurou " if undo else "Arquivou ") + sp["label"], t, i)
        if case_of(name, r): timeline(case_of(name, r), ("Restaurado: " if undo else "Arquivado: ") + str(r.get("titulo")))
        return jsonify(ok=True)
    P = f"/{name}"
    for rule, m, fn, perm in [(P, "GET", lst, "read"), (P, "POST", create, "write"), (P + "/<int:i>", "GET", get, "read"),
                              (P + "/<int:i>", "PATCH", patch, "write"), (P + "/<int:i>", "DELETE", archive, "archive"),
                              (P + "/<int:i>/restaurar", "POST", lambda i: archive(i, True), "archive")]:
        app.add_url_rule(rule, f"{name}_{fn.__name__}_{m}_{rule[-3:]}", auth(perm)(fn), methods=[m])
for n_, s_ in SPEC.items(): make_routes(n_, s_)

@app.post("/itens/<int:i>/confirmar")
@auth("write")
def confirm(i):
    it = one("SELECT * FROM items WHERE id=? AND org_id=?", i, g.org)
    if not it or it["tipo"] != "Prazo": raise ApiError(404, "Prazo não encontrado.")
    run("UPDATE items SET revisado=1 WHERE id=?", i); audit("Prazo confirmado por revisão humana", "items", i)
    timeline(it["case_id"], f"Prazo confirmado: {it['titulo']}"); return jsonify(ok=True)

@app.get("/casos/<int:i>/linha-do-tempo")
@auth("read")
def tl(i):
    if not one("SELECT id FROM cases WHERE id=? AND org_id=?", i, g.org): raise ApiError(404, "Caso não encontrado.")
    return jsonify(rows("""SELECT t.id,t.txt,t.at,u.nome autor FROM timeline t LEFT JOIN users u ON u.id=t.user_id
                           WHERE t.org_id=? AND t.case_id=? ORDER BY t.id DESC""", g.org, i))

# ---------- documentos ----------
def save_file(case_id, doc_id=None, tipo=None):
    f = request.files.get("arquivo")
    if not f or not f.filename: raise ApiError(422, "Envie o arquivo no campo 'arquivo'.")
    name = secure_filename(f.filename) or "arquivo"
    if name.rsplit(".", 1)[-1].lower() not in EXTS: raise ApiError(415, "Tipo de arquivo não permitido.")
    folder = os.path.join(UPLOADS, str(g.org)); os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, uuid.uuid4().hex); f.save(path)
    data = open(path, "rb").read()
    if doc_id is None:
        doc_id = run("INSERT INTO documents(org_id,case_id,nome,tipo,created_at) VALUES(?,?,?,?,?)", g.org, case_id, name, (tipo or "Outro")[:50], now())
    n = one("SELECT COALESCE(MAX(n),0)+1 n FROM doc_versions WHERE doc_id=?", doc_id)["n"]
    run("INSERT INTO doc_versions(org_id,doc_id,n,path,sha256,size,por_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
        g.org, doc_id, n, path, hashlib.sha256(data).hexdigest(), len(data), g.uid, now())
    audit(f"Documento '{name}' versão {n}", "documents", doc_id); timeline(case_id, f"Documento “{name}” enviado (versão {n})")
    return jsonify(id=doc_id, versao=n), 201
@app.post("/casos/<int:i>/documentos")
@auth("write")
def upload(i):
    if not one("SELECT id FROM cases WHERE id=? AND org_id=?", i, g.org): raise ApiError(404, "Caso não encontrado.")
    return save_file(i, tipo=request.form.get("tipo"))
@app.post("/documentos/<int:i>/versoes")
@auth("write")
def new_version(i):
    d = one("SELECT * FROM documents WHERE id=? AND org_id=? AND archived_at IS NULL", i, g.org)
    if not d: raise ApiError(404, "Documento não encontrado.")
    return save_file(d["case_id"], doc_id=i)
@app.get("/documentos")
@auth("read")
def docs():
    w, a = "d.org_id=? AND d.archived_at IS NULL", [g.org]
    if request.args.get("caso"): w += " AND d.case_id=?"; a.append(request.args["caso"])
    return jsonify(rows(f"""SELECT d.id,d.case_id,d.nome,d.tipo,d.created_at,MAX(v.n) versao FROM documents d
                            JOIN doc_versions v ON v.doc_id=d.id WHERE {w} GROUP BY d.id ORDER BY d.id DESC""", *a))
@app.get("/documentos/<int:i>/versoes")
@auth("read")
def versions(i):
    return jsonify(rows("""SELECT v.n,v.size,v.sha256,v.created_at,u.nome por FROM doc_versions v LEFT JOIN users u ON u.id=v.por_id
                           WHERE v.org_id=? AND v.doc_id=? ORDER BY v.n DESC""", g.org, i))
@app.get("/documentos/<int:i>/versoes/<int:n>/download")
@auth("read")
def download(i, n):
    v = one("SELECT v.path,d.nome FROM doc_versions v JOIN documents d ON d.id=v.doc_id WHERE v.doc_id=? AND v.n=? AND v.org_id=?", i, n, g.org)
    if not v: raise ApiError(404, "Versão não encontrada.")
    audit("Download de documento", "documents", i); return send_file(os.path.abspath(v["path"]), as_attachment=True, download_name=v["nome"])
@app.delete("/documentos/<int:i>")
@auth("archive")
def arch_doc(i):
    run("UPDATE documents SET archived_at=? WHERE id=? AND org_id=?", now(), i, g.org); audit("Arquivou documento", "documents", i); return jsonify(ok=True)

# ---------- Hoje, busca, notificações, auditoria, exportação ----------
@app.get("/hoje")
@auth("read")
def hoje():
    t = date.today().isoformat(); lim = (date.today() + timedelta(days=10)).isoformat(); soon = (date.today() + timedelta(days=3)).isoformat()
    base = """SELECT i.*,c.titulo caso_titulo FROM items i JOIN cases c ON c.id=i.case_id
              WHERE i.org_id=? AND i.archived_at IS NULL AND c.archived_at IS NULL AND i.status='Pendente' AND """
    out = {"vencidas": rows(base + "i.data<? AND i.tipo!='Compromisso' ORDER BY i.data", g.org, t),
           "hoje": rows(base + "i.data=? ORDER BY i.prio", g.org, t),
           "prazos": rows(base + "i.tipo='Prazo' AND i.data>? AND i.data<=? ORDER BY i.data", g.org, t, lim),
           "revisao": rows(base + "i.tipo='Prazo' AND i.revisado=0 ORDER BY i.data", g.org),
           "casos_sem_proxima_acao": rows("SELECT id,titulo,resp_id FROM cases WHERE org_id=? AND archived_at IS NULL AND status!='Encerrado' AND (prox_acao IS NULL OR prox_acao='')", g.org)}
    for p in rows(base + "i.tipo='Prazo' AND i.data<=?", g.org, soon):  # alertas internos, sem duplicar
        run("INSERT OR IGNORE INTO notifications(org_id,user_id,txt,key,created_at) VALUES(?,?,?,?,?)",
            g.org, p["resp_id"] or g.uid, f"Prazo “{p['titulo']}” em {p['data']}.", f"prazo:{p['id']}:{p['data']}", now())
    return jsonify(out)

@app.get("/busca")
@auth("read")
def search():
    q = (request.args.get("q") or "").strip()
    if len(q) < 2: raise ApiError(422, "Digite ao menos 2 caracteres.")
    L = f"%{q}%"
    return jsonify(
        casos=rows("SELECT id,titulo,numero FROM cases WHERE org_id=? AND archived_at IS NULL AND (titulo LIKE ? OR numero LIKE ?) LIMIT 20", g.org, L, L),
        clientes=rows("SELECT id,nome,email FROM clients WHERE org_id=? AND archived_at IS NULL AND (nome LIKE ? OR doc LIKE ? OR email LIKE ?) LIMIT 20", g.org, L, L, L),
        itens=rows("SELECT id,case_id,tipo,titulo,data FROM items WHERE org_id=? AND archived_at IS NULL AND titulo LIKE ? LIMIT 20", g.org, L),
        documentos=rows("SELECT id,case_id,nome FROM documents WHERE org_id=? AND archived_at IS NULL AND nome LIKE ? LIMIT 20", g.org, L))

@app.get("/notificacoes")
@auth("read")
def notifs():
    return jsonify(rows("SELECT id,txt,lida,created_at FROM notifications WHERE org_id=? AND user_id=? ORDER BY id DESC LIMIT 100", g.org, g.uid))
@app.post("/notificacoes/<int:i>/lida")
@auth("read")
def read_notif(i):
    run("UPDATE notifications SET lida=1 WHERE id=? AND user_id=?", i, g.uid); return jsonify(ok=True)

@app.get("/auditoria")
@auth("audit")
def audit_list():
    return jsonify(rows("""SELECT a.id,a.acao,a.entity,a.entity_id,a.at,a.ip,u.nome usuario FROM audit a LEFT JOIN users u ON u.id=a.user_id
                           WHERE a.org_id=? ORDER BY a.id DESC LIMIT ?""", g.org, min(int(request.args.get("limite", 100)), 500)))

@app.get("/exportar")
@auth("export")
def export():
    audit("Exportou dados da organização")
    return jsonify({t: rows(f"SELECT * FROM {t} WHERE org_id=?", g.org) for t in ("clients", "cases", "items", "documents", "timeline")})

@app.post("/importar")
@auth("import")
def import_():
    d = body(); cm, km = {}, {}
    for c in d.get("clients", []):
        cm[c["id"]] = run("INSERT INTO clients(org_id,nome,doc,email,tel,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                          g.org, str(c["nome"])[:200], c.get("doc"), c.get("email"), c.get("tel"), now(), now())
    for c in d.get("cases", []):
        if c["client_id"] in cm:
            km[c["id"]] = run("INSERT INTO cases(org_id,client_id,titulo,numero,area,status,prox_acao,prox_data,obs,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                              g.org, cm[c["client_id"]], str(c["titulo"])[:300], c.get("numero"), c.get("area"), c.get("status") or "Ativo",
                              c.get("prox_acao"), c.get("prox_data"), c.get("obs"), now(), now())
    n = 0
    for i in d.get("items", []):
        if i["case_id"] in km and i.get("tipo") in ("Tarefa", "Prazo", "Compromisso"):
            date.fromisoformat(i["data"])
            run("INSERT INTO items(org_id,case_id,tipo,titulo,data,status,prio,origem,revisado,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,0,?,?)",
                g.org, km[i["case_id"]], i["tipo"], str(i["titulo"])[:300], i["data"], i.get("status") or "Pendente", i.get("prio") or "Média", i.get("origem"), now(), now()); n += 1
    audit("Importou dados", "org", g.org)
    return jsonify(clientes=len(cm), casos=len(km), itens=n), 201

# ---------- identidade visual (logotipos e cores por organização) ----------
SLOTS = {"horizontal": "logo_horizontal", "vertical": "logo_vertical", "icone": "icone"}
DEFAULT_COLORS = {"azul_escuro": "#1D2F4D", "azul": "#2F5C8E", "dourado": "#CFA75B"}   # Paleta A
MAX_LOGO = 600 * 1024
def sniff(data):
    if data.startswith(b"\x89PNG\r\n\x1a\n"): return "image/png"
    if data[:3] == b"\xff\xd8\xff": return "image/jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP": return "image/webp"
    if b"<svg" in data[:2048].lower(): return "image/svg+xml"
    return None
def brand_json():
    r = one("SELECT * FROM branding WHERE org_id=?", g.org) or {}
    return {"cores": {**DEFAULT_COLORS, **json.loads(r.get("cores") or "{}")}, "padrao": DEFAULT_COLORS,
            "logos": {k: r.get(col) for k, col in SLOTS.items()}}
def brand_touch():
    run("INSERT INTO branding(org_id) VALUES(?) ON CONFLICT(org_id) DO NOTHING", g.org)
    run("UPDATE branding SET updated_at=? WHERE org_id=?", now(), g.org)

@app.get("/marca")
@auth("read")
def brand_get(): return jsonify(brand_json())

@app.put("/marca/cores")
@auth("brand")
def brand_colors():
    d = body(); c = {}
    for k in DEFAULT_COLORS:
        v = d.get(k, DEFAULT_COLORS[k])
        if not isinstance(v, str) or not re.fullmatch(r"#[0-9a-fA-F]{6}", v): raise ApiError(422, f"Cor inválida em '{k}' (use #RRGGBB).")
        c[k] = v.upper()
    brand_touch(); run("UPDATE branding SET cores=? WHERE org_id=?", json.dumps(c), g.org)
    audit("Alterou as cores do sistema", "branding", g.org); return jsonify(brand_json())

@app.post("/marca/logo/<slot>")
@auth("brand")
def brand_logo(slot):
    if slot not in SLOTS: raise ApiError(404, "Logotipo desconhecido.")
    f = request.files.get("arquivo")
    if not f: raise ApiError(422, "Envie o arquivo no campo 'arquivo'.")
    data = f.read(MAX_LOGO + 1)
    if len(data) > MAX_LOGO: raise ApiError(413, "O logotipo deve ter até 600 KB.")
    mime = sniff(data)
    if not mime: raise ApiError(415, "Use PNG, JPG, WEBP ou SVG.")
    if mime == "image/svg+xml" and re.search(rb"<script|\son\w+\s*=|javascript:|<foreignobject|<iframe|<!entity", data.lower()):
        raise ApiError(422, "O SVG contém elementos não permitidos.")
    brand_touch(); run(f"UPDATE branding SET {SLOTS[slot]}=? WHERE org_id=?", f"data:{mime};base64," + base64.b64encode(data).decode(), g.org)
    audit(f"Trocou o logotipo ({slot})", "branding", g.org); return jsonify(brand_json())

@app.delete("/marca/logo/<slot>")
@auth("brand")
def brand_logo_reset(slot):
    if slot not in SLOTS: raise ApiError(404, "Logotipo desconhecido.")
    brand_touch(); run(f"UPDATE branding SET {SLOTS[slot]}=NULL WHERE org_id=?", g.org)
    audit(f"Restaurou o logotipo padrão ({slot})", "branding", g.org); return jsonify(brand_json())

@app.post("/marca/restaurar")
@auth("brand")
def brand_reset():
    run("DELETE FROM branding WHERE org_id=?", g.org); audit("Restaurou a identidade visual padrão", "branding", g.org); return jsonify(brand_json())

# ---------- front-end estático e configuração pública ----------
STATIC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
CSP = ("default-src 'self'; script-src 'self' 'unsafe-inline' https://accounts.google.com https://appleid.cdn-apple.com; "
       "style-src 'self' 'unsafe-inline' https://accounts.google.com; frame-src https://accounts.google.com https://appleid.apple.com; "
       "connect-src 'self' https://accounts.google.com https://appleid.apple.com; img-src 'self' data: https:; frame-ancestors 'none'")
@app.get("/")
def index():
    r = send_file(os.path.join(STATIC, "index.html")); r.headers["Content-Security-Policy"] = CSP; return r
@app.get("/config.json")
def public_config():  # só IDs públicos; nenhum segredo
    return jsonify(google=os.environ.get("GOOGLE_CLIENT_ID", ""), apple=os.environ.get("APPLE_CLIENT_ID", ""),
                   appleRedirect=os.environ.get("APPLE_REDIRECT_URI", ""))

@app.get("/saude")
def health(): return jsonify(ok=True)

init_db()
if __name__ == "__main__":
    app.run(host="127.0.0.1", port=int(os.environ.get("PORT", 5000)))
