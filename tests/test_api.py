"""Rode com:  python3 tests/test_api.py   (ou pytest)"""
import io, os, sys, tempfile
tmp = tempfile.mkdtemp()
os.environ.update(PRAJUS_DB=os.path.join(tmp, "t.db"), PRAJUS_UPLOADS=os.path.join(tmp, "up"), PRAJUS_DEV="1")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import app as A
c = A.app.test_client()
H = lambda t: {"Authorization": "Bearer " + t}
MP = "multipart/form-data"
def reg(email, org):
    r = c.post("/auth/cadastro", json=dict(nome="Helena", organizacao=org, email=email, senha="Senha12345", confirmacao="Senha12345", termos=True, privacidade=True))
    assert r.status_code == 202
    c.post("/auth/verificar-email", json=dict(token=r.json["token_verificacao_dev"]))
    r = c.post("/auth/login", json=dict(email=email, senha="Senha12345")); assert r.status_code == 200, r.json
    return r.json["token"]

def test_all():
    assert c.post("/auth/cadastro", json=dict(nome="x", organizacao="y", email="a@b.com", senha="curta", confirmacao="curta", termos=True, privacidade=True)).status_code == 422
    adm = reg("helena@a.com", "Escritório A"); other = reg("joao@b.com", "Escritório B")
    r = c.post("/auth/cadastro", json=dict(nome="H", organizacao="Dup", email="helena@a.com", senha="Senha12345", confirmacao="Senha12345", termos=True, privacidade=True))
    assert r.status_code == 202 and "token_verificacao_dev" not in r.json   # sem enumeração de e-mail
    assert c.get("/casos").status_code == 401
    cl = c.post("/clientes", json=dict(nome="Marina", email="m@x.com"), headers=H(adm)).json
    assert c.post("/clientes", json=dict(nome="X", email="ruim"), headers=H(adm)).status_code == 422
    cs = c.post("/casos", json=dict(titulo="Indenização", client_id=cl["id"]), headers=H(adm)); assert cs.status_code == 201; cs = cs.json
    assert c.post("/casos", json=dict(titulo="Z", client_id=9999), headers=H(adm)).status_code == 422
    pz = c.post("/itens", json=dict(case_id=cs["id"], tipo="Prazo", titulo="Réplica", data="2026-11-15"), headers=H(adm)).json
    assert c.post("/itens", json=dict(case_id=cs["id"], tipo="Prazo", titulo="R", data="07/10"), headers=H(adm)).status_code == 422
    assert c.post(f"/itens/{pz['id']}/confirmar", headers=H(adm)).status_code == 200
    assert c.patch(f"/itens/{pz['id']}", json=dict(data="2026-11-16"), headers=H(adm)).json["revisado"] == 0
    # isolamento entre organizações
    for url in (f"/casos/{cs['id']}", f"/clientes/{cl['id']}", f"/itens/{pz['id']}", f"/casos/{cs['id']}/linha-do-tempo"):
        assert c.get(url, headers=H(other)).status_code == 404, url
    assert c.patch(f"/casos/{cs['id']}", json=dict(titulo="hack"), headers=H(other)).status_code == 404
    assert c.get("/casos", headers=H(other)).json["total"] == 0
    assert c.post("/casos", json=dict(titulo="Z", client_id=cl["id"]), headers=H(other)).status_code == 422
    # arquivar, nunca excluir
    assert c.delete(f"/clientes/{cl['id']}", headers=H(adm)).status_code == 409
    assert c.delete(f"/casos/{cs['id']}", headers=H(adm)).status_code == 200
    assert c.get("/casos", headers=H(adm)).json["total"] == 0 and c.get("/casos?arquivados=1", headers=H(adm)).json["total"] == 1
    assert c.post(f"/casos/{cs['id']}/restaurar", headers=H(adm)).status_code == 200
    # convites e permissões no backend
    inv = c.post("/convites", json=dict(email="ana@a.com", role="assistente", nome="Ana"), headers=H(adm)); assert inv.status_code == 201
    assert c.post("/convites/aceitar", json=dict(token=inv.json["token_dev"], senha="OutraSenha99")).status_code == 201
    assert c.post("/convites/aceitar", json=dict(token=inv.json["token_dev"], senha="OutraSenha99")).status_code == 400
    ana = c.post("/auth/login", json=dict(email="ana@a.com", senha="OutraSenha99")).json["token"]
    assert c.post("/clientes", json=dict(nome="Novo"), headers=H(ana)).status_code == 201
    assert c.delete(f"/casos/{cs['id']}", headers=H(ana)).status_code == 403
    assert c.get("/exportar", headers=H(ana)).status_code == 403
    assert c.get("/auditoria", headers=H(ana)).status_code == 403
    assert c.post("/convites", json=dict(email="z@a.com", role="admin"), headers=H(ana)).status_code == 403
    me = c.get("/auth/eu", headers=H(adm)).json["usuario"]["id"]
    assert c.patch(f"/usuarios/{me}", json=dict(role="leitura"), headers=H(adm)).status_code == 409
    # documentos
    up = c.post(f"/casos/{cs['id']}/documentos", data=dict(arquivo=(io.BytesIO(b"%PDF contrato"), "Contrato.pdf"), tipo="Contrato"), headers=H(adm), content_type=MP)
    assert up.status_code == 201, up.json; did = up.json["id"]
    assert c.post(f"/documentos/{did}/versoes", data=dict(arquivo=(io.BytesIO(b"v2"), "c2.pdf")), headers=H(ana), content_type=MP).json["versao"] == 2
    assert c.post(f"/casos/{cs['id']}/documentos", data=dict(arquivo=(io.BytesIO(b"x"), "malware.exe")), headers=H(adm), content_type=MP).status_code == 415
    assert c.get(f"/documentos/{did}/versoes/1/download", headers=H(adm)).data == b"%PDF contrato"
    assert c.get(f"/documentos/{did}/versoes/1/download", headers=H(other)).status_code == 404
    # hoje, busca, linha do tempo, auditoria, exportação
    h = c.get("/hoje", headers=H(adm)).json; assert set(h) >= {"vencidas", "hoje", "prazos", "revisao", "casos_sem_proxima_acao"}
    assert any(x["id"] == cs["id"] for x in h["casos_sem_proxima_acao"])
    assert c.get("/busca?q=Indeniz", headers=H(adm)).json["casos"] and not c.get("/busca?q=Indeniz", headers=H(other)).json["casos"]
    assert len(c.get(f"/casos/{cs['id']}/linha-do-tempo", headers=H(adm)).json) >= 4
    assert any("Criou caso" in a["acao"] for a in c.get("/auditoria", headers=H(adm)).json)
    ex = c.get("/exportar", headers=H(adm)).json; assert ex["cases"] and "scrypt" not in str(ex)
    assert c.post("/importar", json=ex, headers=H(adm)).json["casos"] == 1
    # MFA
    sec = c.post("/auth/mfa/configurar", headers=H(adm)).json["segredo"]
    assert c.post("/auth/mfa/ativar", json=dict(codigo="000000"), headers=H(adm)).status_code == 400
    assert c.post("/auth/mfa/ativar", json=dict(codigo=A.totp(sec)), headers=H(adm)).status_code == 200
    r = c.post("/auth/login", json=dict(email="helena@a.com", senha="Senha12345")); assert r.status_code == 401 and r.json.get("mfa")
    assert c.post("/auth/login", json=dict(email="helena@a.com", senha="Senha12345", codigo=A.totp(sec))).status_code == 200
    # recuperação de senha revoga sessões; logout invalida
    tk = c.post("/auth/esqueci-senha", json=dict(email="joao@b.com")).json["token_dev"]
    assert c.post("/auth/redefinir-senha", json=dict(token=tk, senha="NovaSenha123")).status_code == 200
    assert c.get("/auth/eu", headers=H(other)).status_code == 401
    assert c.post("/auth/redefinir-senha", json=dict(token=tk, senha="NovaSenha123")).status_code == 400
    t2 = c.post("/auth/login", json=dict(email="joao@b.com", senha="NovaSenha123")).json["token"]
    c.post("/auth/logout", headers=H(t2)); assert c.get("/auth/eu", headers=H(t2)).status_code == 401
    # limite de tentativas
    for _ in range(5): c.post("/auth/login", json=dict(email="ana@a.com", senha="errada"))
    assert c.post("/auth/login", json=dict(email="ana@a.com", senha="OutraSenha99")).status_code == 429

def test_oauth():
    claims = {"email": "novo@gmail.com", "email_verified": True, "name": "Novo Usuário"}
    A.verify_oauth = lambda p, tok: dict(claims) if tok == "ok" else (_ for _ in ()).throw(A.ApiError(401, "Token do provedor inválido."))
    assert c.post("/auth/oauth/google", json=dict(id_token="falso")).status_code == 401
    assert c.post("/auth/oauth/facebook", json=dict(id_token="ok")).status_code == 404
    r = c.post("/auth/oauth/google", json=dict(id_token="ok")); assert r.status_code == 428 and r.json["precisa_cadastro"]   # exige aceite dos termos
    r = c.post("/auth/oauth/google", json=dict(id_token="ok", termos=True, privacidade=True)); assert r.status_code == 200, r.json
    tok = r.json["token"]; assert c.get("/auth/eu", headers=H(tok)).json["usuario"]["role"] == "admin"
    assert c.post("/auth/oauth/apple", json=dict(id_token="ok")).status_code == 200                 # segundo acesso entra direto
    claims["email_verified"] = False
    assert c.post("/auth/oauth/apple", json=dict(id_token="ok")).status_code == 401                 # e-mail não verificado pelo provedor
    A.verify_oauth = lambda *a: (_ for _ in ()).throw(A.ApiError(503, "x"))

def test_brand():
    import sqlite3; d = sqlite3.connect(A.DB); d.execute("DELETE FROM login_attempts"); d.commit(); d.close()   # limpa o limite de tentativas do teste anterior
    adm = reg("marca@a.com", "Marca A"); other = reg("marca@b.com", "Marca B")
    png = b"\x89PNG\r\n\x1a\n" + b"0" * 50
    b0 = c.get("/marca", headers=H(adm)).json; assert b0["cores"]["azul_escuro"] == "#1D2F4D" and b0["logos"]["horizontal"] is None
    r = c.put("/marca/cores", json=dict(azul_escuro="#112233", azul="#2F5C8E", dourado="#cfa75b"), headers=H(adm)); assert r.status_code == 200 and r.json["cores"]["azul_escuro"] == "#112233"
    assert c.put("/marca/cores", json=dict(azul="vermelho"), headers=H(adm)).status_code == 422
    assert c.put("/marca/cores", json=dict(azul="url(x)"), headers=H(adm)).status_code == 422
    r = c.post("/marca/logo/horizontal", data=dict(arquivo=(io.BytesIO(png), "l.png")), headers=H(adm), content_type=MP); assert r.status_code == 200 and r.json["logos"]["horizontal"].startswith("data:image/png;base64,")
    assert c.post("/marca/logo/horizontal", data=dict(arquivo=(io.BytesIO(b"MZ exe"), "l.png")), headers=H(adm), content_type=MP).status_code == 415
    assert c.post("/marca/logo/vertical", data=dict(arquivo=(io.BytesIO(b"<svg><script>alert(1)</script></svg>"), "l.svg")), headers=H(adm), content_type=MP).status_code == 422
    assert c.post("/marca/logo/vertical", data=dict(arquivo=(io.BytesIO(b'<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>'), "l.svg")), headers=H(adm), content_type=MP).status_code == 200
    assert c.post("/marca/logo/vertical", data=dict(arquivo=(io.BytesIO(png + b"0" * 700000), "g.png")), headers=H(adm), content_type=MP).status_code == 413
    assert c.post("/marca/logo/outro", data=dict(arquivo=(io.BytesIO(png), "l.png")), headers=H(adm), content_type=MP).status_code == 404
    # só o administrador altera; cada organização tem a sua identidade
    inv = c.post("/convites", json=dict(email="adv@a.com", role="advogado"), headers=H(adm)).json
    c.post("/convites/aceitar", json=dict(token=inv["token_dev"], nome="Adv", senha="Senha123456"))
    adv = c.post("/auth/login", json=dict(email="adv@a.com", senha="Senha123456")).json["token"]
    assert c.put("/marca/cores", json=dict(azul="#000000"), headers=H(adv)).status_code == 403
    assert c.post("/marca/logo/icone", data=dict(arquivo=(io.BytesIO(png), "l.png")), headers=H(adv), content_type=MP).status_code == 403
    assert c.get("/marca", headers=H(adv)).json["cores"]["azul_escuro"] == "#112233"          # todos veem a marca da organização
    ob = c.get("/marca", headers=H(other)).json; assert ob["cores"]["azul_escuro"] == "#1D2F4D" and ob["logos"]["horizontal"] is None
    assert c.delete("/marca/logo/horizontal", headers=H(adm)).json["logos"]["horizontal"] is None
    assert c.post("/marca/restaurar", headers=H(adm)).json["cores"]["azul_escuro"] == "#1D2F4D"
    assert any("logotipo" in a["acao"] for a in c.get("/auditoria", headers=H(adm)).json)

if __name__ == "__main__":
    test_all(); test_oauth(); test_brand(); print("TODOS OS TESTES PASSARAM")
