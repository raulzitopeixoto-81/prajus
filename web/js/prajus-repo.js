import {
  collection,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where
} from "https://www.gstatic.com/firebasejs/11.4.0/firebase-firestore.js";
import { loadDocumentBlob, saveDocumentBlob, sha256Hex } from "./local-store.js";

export const ALLOWED_DOC_EXTENSIONS = new Set([
  "pdf", "doc", "docx", "xls", "xlsx", "odt", "png", "jpg", "jpeg", "txt"
]);
export const MAX_DOC_BYTES = 25 * 1024 * 1024;
export const MAX_LOGO_BYTES = 600 * 1024;
export const DEFAULT_BRAND = {
  azulEscuro: "#1D2F4D",
  azul: "#2F5C8E",
  dourado: "#CFA75B"
};

export function todayIso() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

export function todayPlusDaysIso(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

export function laterIso(days = 7) {
  const d = new Date(Date.now() + days * 86400000);
  return d.toISOString().slice(0, 19) + "Z";
}

export function nowIso() {
  return new Date().toISOString().slice(0, 19) + "Z";
}

export function isValidIsoDate(v) {
  return /^\d{4}-\d{2}-\d{2}$/.test(String(v || "").trim());
}

export function isValidHexColor(v) {
  return /^#[0-9a-fA-F]{6}$/.test(String(v || "").trim());
}

export function normalizeEmail(raw) {
  const clean = String(raw || "").trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(clean) || clean.length > 254) {
    throw new Error("E-mail inválido.");
  }
  return clean;
}

export function formatDateBr(iso) {
  if (!iso) return "—";
  const part = String(iso).slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(part)) return part;
  return part.split("-").reverse().join("/");
}

export function formatTimestampBr(ts, withTime = false) {
  if (!ts) return "—";
  try {
    const date = typeof ts.toDate === "function" ? ts.toDate() : new Date(ts);
    if (Number.isNaN(date.getTime())) return "—";
    return withTime
      ? date.toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" })
      : date.toLocaleDateString("pt-BR");
  } catch {
    return "—";
  }
}

export function tsMillis(ts) {
  if (!ts) return 0;
  if (typeof ts.toMillis === "function") return ts.toMillis();
  const d = new Date(ts);
  return Number.isNaN(d.getTime()) ? 0 : d.getTime();
}

export class PrajusRepository {
  constructor(db) {
    this.db = db;
  }

  userDoc(uid) { return doc(this.db, "users", uid); }
  clientsCol(uid) { return collection(this.db, "users", uid, "clients"); }
  casesCol(uid) { return collection(this.db, "users", uid, "cases"); }
  itemsCol(uid) { return collection(this.db, "users", uid, "items"); }
  docsCol(uid) { return collection(this.db, "users", uid, "documents"); }
  timelineCol(uid) { return collection(this.db, "users", uid, "timeline"); }
  notificationsCol(uid) { return collection(this.db, "users", uid, "notifications"); }
  auditCol(uid) { return collection(this.db, "users", uid, "audit"); }
  teamCol(uid) { return collection(this.db, "users", uid, "team_members"); }
  invitesCol(uid) { return collection(this.db, "users", uid, "invites"); }
  brandingDoc(uid) { return doc(this.db, "users", uid, "branding", "config"); }

  async appendAudit(uid, acao, entity = null, entityRef = null, usuario = null) {
    const ref = doc(this.auditCol(uid));
    const payload = {
      userId: uid,
      acao: String(acao || "Ação").trim().slice(0, 300) || "Ação",
      createdAt: serverTimestamp()
    };
    if (entity) payload.entity = String(entity).trim().slice(0, 60);
    if (entityRef) payload.entityRef = String(entityRef).trim().slice(0, 128);
    if (usuario) payload.usuario = String(usuario).trim().slice(0, 200);
    await setDoc(ref, payload);
  }

  async appendTimeline(uid, caseId, txt, autor = null) {
    const ref = doc(this.timelineCol(uid));
    const payload = {
      userId: uid,
      caseId,
      txt: String(txt || "Atualização").trim().slice(0, 500) || "Atualização",
      createdAt: serverTimestamp()
    };
    if (autor) payload.autor = String(autor).trim().slice(0, 200);
    await setDoc(ref, payload);
  }

  // ---------- Snapshot Subscriptions ----------

  subscribeWorkspace(uid, onUpdate, onError) {
    const unsubs = [];
    const safeSnap = (refOrQuery, mapFn, label) => {
      return onSnapshot(
        refOrQuery,
        (snap) => mapFn(snap),
        (err) => {
          console.error(`Firestore error (${label}):`, err);
          onError(err?.message || `Erro ao sincronizar ${label}.`);
        }
      );
    };

    unsubs.push(
      safeSnap(
        this.userDoc(uid),
        (snap) => onUpdate("profile", snap.exists() ? { id: snap.id, ...snap.data() } : null),
        "perfil"
      )
    );

    unsubs.push(
      safeSnap(
        this.brandingDoc(uid),
        (snap) => {
          if (!snap.exists()) onUpdate("branding", { ...DEFAULT_BRAND });
          else onUpdate("branding", { ...DEFAULT_BRAND, ...snap.data() });
        },
        "aparência"
      )
    );

    unsubs.push(
      safeSnap(
        this.clientsCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => (a.nome || "").localeCompare(b.nome || "", "pt-BR"));
          onUpdate("clients", rows);
        },
        "clientes"
      )
    );

    unsubs.push(
      safeSnap(
        this.casesCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => tsMillis(b.updatedAt) - tsMillis(a.updatedAt));
          onUpdate("cases", rows);
        },
        "casos"
      )
    );

    unsubs.push(
      safeSnap(
        this.itemsCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => String(a.data || "").localeCompare(String(b.data || "")));
          onUpdate("items", rows);
        },
        "itens"
      )
    );

    unsubs.push(
      safeSnap(
        this.docsCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => tsMillis(b.updatedAt) - tsMillis(a.updatedAt));
          onUpdate("documents", rows);
        },
        "documentos"
      )
    );

    unsubs.push(
      safeSnap(
        this.teamCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => (a.nome || "").localeCompare(b.nome || "", "pt-BR"));
          onUpdate("teamMembers", rows);
        },
        "equipe"
      )
    );

    unsubs.push(
      safeSnap(
        this.invitesCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => tsMillis(b.createdAt) - tsMillis(a.createdAt));
          onUpdate("invites", rows);
        },
        "convites"
      )
    );

    unsubs.push(
      safeSnap(
        this.auditCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => tsMillis(b.createdAt) - tsMillis(a.createdAt))
            .slice(0, 200);
          onUpdate("audit", rows);
        },
        "auditoria"
      )
    );

    unsubs.push(
      safeSnap(
        this.notificationsCol(uid),
        (snap) => {
          const rows = snap.docs
            .map((d) => ({ id: d.id, ...d.data() }))
            .sort((a, b) => tsMillis(b.createdAt) - tsMillis(a.createdAt))
            .slice(0, 50);
          onUpdate("notifications", rows);
        },
        "notificações"
      )
    );

    return () => unsubs.forEach((u) => u());
  }

  subscribeCaseTimeline(uid, caseId, onData, onError) {
    const q = query(this.timelineCol(uid), where("caseId", "==", caseId));
    return onSnapshot(
      q,
      (snap) => {
        const rows = snap.docs
          .map((d) => ({ id: d.id, ...d.data() }))
          .sort((a, b) => tsMillis(b.createdAt) - tsMillis(a.createdAt));
        onData(rows);
      },
      (err) => {
        console.error("Timeline error:", err);
        if (onError) onError(err?.message || "Erro ao carregar linha do tempo.");
      }
    );
  }

  async getCaseTimeline(uid, caseId) {
    const q = query(this.timelineCol(uid), where("caseId", "==", caseId));
    const snap = await getDocs(q);
    return snap.docs
      .map((d) => ({ id: d.id, ...d.data() }))
      .sort((a, b) => tsMillis(b.createdAt) - tsMillis(a.createdAt));
  }

  // ---------- Profile ----------

  async saveUserProfile(uid, nome, email, orgName, oab) {
    const cleanNome = String(nome || "").trim().slice(0, 200);
    const cleanOrg = String(orgName || "").trim().slice(0, 200);
    const cleanEmail = String(email || "").trim().slice(0, 254) || "usuario@prajus.app";
    const cleanOab = String(oab || "").trim().slice(0, 30);
    if (!cleanNome) throw new Error("Informe seu nome.");
    if (!cleanOrg) throw new Error("Informe o nome do escritório.");

    const ref = this.userDoc(uid);
    const existing = await getDoc(ref);
    if (!existing.exists()) {
      const payload = {
        userId: uid,
        nome: cleanNome,
        email: cleanEmail,
        orgName: cleanOrg,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      };
      if (cleanOab) payload.oab = cleanOab;
      await setDoc(ref, payload);

      const ownerMemberRef = doc(this.teamCol(uid), uid);
      const memberSnap = await getDoc(ownerMemberRef);
      if (!memberSnap.exists()) {
        await setDoc(ownerMemberRef, {
          userId: uid,
          nome: cleanNome,
          email: cleanEmail,
          role: "admin",
          status: "ativo",
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp()
        });
      }
      await this.appendAudit(uid, `Escritório '${cleanOrg}' configurado (Web)`, "users", uid, cleanNome);
    } else {
      await updateDoc(ref, {
        nome: cleanNome,
        orgName: cleanOrg,
        oab: cleanOab || deleteField(),
        updatedAt: serverTimestamp()
      });
      await this.appendAudit(uid, "Perfil do escritório atualizado (Web)", "users", uid, cleanNome);
    }
  }

  // ---------- Clients ----------

  async createClient(uid, nome, docVal, email, tel, authorName) {
    const cleanNome = String(nome || "").trim().slice(0, 200);
    if (!cleanNome) throw new Error("Informe o nome do cliente.");
    const cleanEmail = email?.trim() ? normalizeEmail(email) : null;
    const cleanDoc = docVal?.trim()?.slice(0, 30) || null;
    const cleanTel = tel?.trim()?.slice(0, 30) || null;

    const ref = doc(this.clientsCol(uid));
    const payload = {
      userId: uid,
      nome: cleanNome,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    };
    if (cleanDoc) payload.doc = cleanDoc;
    if (cleanEmail) payload.email = cleanEmail;
    if (cleanTel) payload.tel = cleanTel;

    await setDoc(ref, payload);
    await this.appendAudit(uid, `Cliente '${cleanNome}'`, "clients", ref.id, authorName);
    return ref.id;
  }

  async updateClient(uid, clientId, nome, docVal, email, tel, authorName) {
    const cleanNome = String(nome || "").trim().slice(0, 200);
    if (!cleanNome) throw new Error("Informe o nome do cliente.");
    const cleanEmail = email?.trim() ? normalizeEmail(email) : null;
    const cleanDoc = docVal?.trim()?.slice(0, 30) || null;
    const cleanTel = tel?.trim()?.slice(0, 30) || null;

    await updateDoc(doc(this.clientsCol(uid), clientId), {
      nome: cleanNome,
      doc: cleanDoc || deleteField(),
      email: cleanEmail || deleteField(),
      tel: cleanTel || deleteField(),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, `Cliente '${cleanNome}' editado`, "clients", clientId, authorName);
  }

  async archiveClient(uid, clientId, restore, authorName) {
    if (!restore) {
      const casesSnap = await getDocs(query(this.casesCol(uid), where("clientId", "==", clientId)));
      const hasActive = casesSnap.docs.some((d) => !d.data().archivedAt);
      if (hasActive) {
        throw new Error("Arquive ou encerre os casos deste cliente antes.");
      }
    }
    await updateDoc(doc(this.clientsCol(uid), clientId), {
      archivedAt: restore ? deleteField() : nowIso(),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(
      uid,
      restore ? "Cliente restaurado" : "Cliente arquivado",
      "clients",
      clientId,
      authorName
    );
  }

  // ---------- Cases ----------

  async createCase(uid, titulo, clientId, numero, area, obs, authorName) {
    const cleanTitulo = String(titulo || "").trim().slice(0, 300);
    if (!cleanTitulo) throw new Error("Informe o título do caso.");
    if (!clientId) throw new Error("Selecione um cliente.");

    const ref = doc(this.casesCol(uid));
    const payload = {
      userId: uid,
      clientId,
      titulo: cleanTitulo,
      status: "Ativo",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    };
    if (numero?.trim()) payload.numero = numero.trim().slice(0, 40);
    if (area?.trim()) payload.area = area.trim().slice(0, 80);
    if (obs?.trim()) payload.obs = obs.trim().slice(0, 5000);

    await setDoc(ref, payload);
    await this.appendAudit(uid, `Caso '${cleanTitulo}'`, "cases", ref.id, authorName);
    await this.appendTimeline(uid, ref.id, "Caso criado", authorName);
    return ref.id;
  }

  async updateCaseNextAction(uid, caseId, proxAcao, proxData, authorName) {
    const cleanAcao = String(proxAcao || "").trim().slice(0, 300);
    const cleanData = String(proxData || "").trim().slice(0, 20);
    if (!cleanAcao) throw new Error("Informe a próxima ação.");
    if (cleanData && !isValidIsoDate(cleanData)) {
      throw new Error("Data da próxima ação inválida.");
    }

    await updateDoc(doc(this.casesCol(uid), caseId), {
      proxAcao: cleanAcao,
      proxData: cleanData || deleteField(),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, "Próxima ação definida", "cases", caseId, authorName);
    await this.appendTimeline(uid, caseId, `Próxima ação: ${cleanAcao}`, authorName);
  }

  async updateCaseDetails(uid, caseId, titulo, numero, area, status, obs, authorName) {
    const cleanTitulo = String(titulo || "").trim().slice(0, 300);
    if (!cleanTitulo) throw new Error("Título obrigatório.");
    if (!["Ativo", "Aguardando", "Encerrado"].includes(status)) {
      throw new Error("Status inválido.");
    }

    await updateDoc(doc(this.casesCol(uid), caseId), {
      titulo: cleanTitulo,
      numero: numero?.trim()?.slice(0, 40) || deleteField(),
      area: area?.trim()?.slice(0, 80) || deleteField(),
      status,
      obs: obs?.trim()?.slice(0, 5000) || deleteField(),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, `Caso '${cleanTitulo}' editado`, "cases", caseId, authorName);
    await this.appendTimeline(uid, caseId, `Caso editado (status: ${status})`, authorName);
  }

  async archiveCase(uid, caseId, caseTitle, restore, authorName) {
    await updateDoc(doc(this.casesCol(uid), caseId), {
      archivedAt: restore ? deleteField() : nowIso(),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(
      uid,
      restore ? `Caso '${caseTitle}' restaurado` : `Caso '${caseTitle}' arquivado`,
      "cases",
      caseId,
      authorName
    );
    await this.appendTimeline(uid, caseId, restore ? "Caso restaurado" : "Caso arquivado", authorName);
  }

  // ---------- Items & Deadlines ----------

  async createItem(uid, caseId, tipo, titulo, data, prio, authorName) {
    if (!["Tarefa", "Prazo", "Compromisso"].includes(tipo)) throw new Error("Tipo inválido.");
    if (!["Baixa", "Média", "Alta"].includes(prio)) throw new Error("Prioridade inválida.");
    const cleanTitulo = String(titulo || "").trim().slice(0, 300);
    if (!cleanTitulo) throw new Error("Informe o título do item.");
    if (!isValidIsoDate(data)) throw new Error("Data inválida.");

    const revisado = tipo !== "Prazo";
    const ref = doc(this.itemsCol(uid));
    await setDoc(ref, {
      userId: uid,
      caseId,
      tipo,
      titulo: cleanTitulo,
      data: data.trim(),
      status: "Pendente",
      prio,
      revisado,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, `${tipo} '${cleanTitulo}'`, "items", ref.id, authorName);
    await this.appendTimeline(uid, caseId, `${tipo} “${cleanTitulo}” para ${formatDateBr(data)}`, authorName);
  }

  async updateItemStatus(uid, item, status, authorName) {
    await updateDoc(doc(this.itemsCol(uid), item.id), {
      status,
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, `Item '${item.titulo}' -> ${status}`, "items", item.id, authorName);
    await this.appendTimeline(uid, item.caseId, `${item.tipo} “${item.titulo}” marcada como ${status}`, authorName);
  }

  async confirmDeadline(uid, item, authorName) {
    await updateDoc(doc(this.itemsCol(uid), item.id), {
      revisado: true,
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, `Prazo '${item.titulo}' confirmado`, "items", item.id, authorName);
    await this.appendTimeline(uid, item.caseId, `Prazo “${item.titulo}” confirmado por revisão humana`, authorName);
  }

  async updateItemDate(uid, item, newDate, authorName) {
    if (!isValidIsoDate(newDate)) throw new Error("Data inválida.");
    const resetRevisado = item.tipo === "Prazo" ? false : Boolean(item.revisado);
    await updateDoc(doc(this.itemsCol(uid), item.id), {
      data: newDate.trim(),
      revisado: resetRevisado,
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, `Data do item '${item.titulo}' alterada`, "items", item.id, authorName);
    await this.appendTimeline(
      uid,
      item.caseId,
      `Data de “${item.titulo}” alterada para ${formatDateBr(newDate)}`,
      authorName
    );
  }

  async syncDeadlineNotifications(uid, casesList, itemsList, existingNotifications) {
    const today = todayIso();
    const limit3 = todayPlusDaysIso(3);
    const activeCases = new Map(casesList.filter((c) => !c.archivedAt).map((c) => [c.id, c]));
    const existingKeys = new Set(existingNotifications.map((n) => n.notifKey));

    for (const item of itemsList) {
      if (item.archivedAt || item.tipo !== "Prazo" || item.status !== "Pendente") continue;
      if (item.data < today || item.data > limit3) continue;
      const caseObj = activeCases.get(item.caseId);
      if (!caseObj) continue;

      const key = `prazo:${item.id}:${item.data}`;
      if (existingKeys.has(key)) continue;
      existingKeys.add(key);

      const ref = doc(this.notificationsCol(uid));
      await setDoc(ref, {
        userId: uid,
        txt: `Prazo em breve (${formatDateBr(item.data)}): ${item.titulo} — ${caseObj.titulo}`.slice(0, 500),
        notifKey: key.slice(0, 150),
        lida: false,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    }
  }

  async markNotificationRead(uid, notifId) {
    await updateDoc(doc(this.notificationsCol(uid), notifId), {
      lida: true,
      updatedAt: serverTimestamp()
    });
  }

  // ---------- Documents ----------

  async uploadDocument(uid, caseId, file, tipo, existingDoc, authorName) {
    if (!file) throw new Error("Selecione um arquivo.");
    const fileName = String(file.name || "documento").trim().slice(0, 300) || "documento";
    const ext = fileName.includes(".") ? fileName.split(".").pop().toLowerCase() : "";
    if (!ALLOWED_DOC_EXTENSIONS.has(ext)) {
      throw new Error("Formato de arquivo não permitido. Use PDF, Office, imagem ou TXT.");
    }
    if (file.size > MAX_DOC_BYTES) {
      throw new Error("O arquivo excede o limite de 25 MB.");
    }

    const buffer = await file.arrayBuffer();
    const hash = await sha256Hex(buffer);
    const size = buffer.byteLength;

    let docId = existingDoc ? existingDoc.id : doc(this.docsCol(uid)).id;
    let targetCaseId = existingDoc ? existingDoc.caseId : caseId;
    let newVersion = existingDoc ? Number(existingDoc.versao || 1) + 1 : 1;

    const localKey = await saveDocumentBlob(uid, docId, newVersion, fileName, file.type || "application/octet-stream", buffer);
    const localPath = `browser-idb://${localKey}`.slice(0, 600);

    const docRef = doc(this.docsCol(uid), docId);
    if (existingDoc) {
      await updateDoc(docRef, {
        versao: newVersion,
        sha256: hash,
        size,
        localPath,
        updatedAt: serverTimestamp()
      });
    } else {
      await setDoc(docRef, {
        userId: uid,
        caseId: targetCaseId,
        nome: fileName,
        tipo: tipo?.trim()?.slice(0, 60) || "Outro",
        versao: newVersion,
        sha256: hash,
        size,
        localPath,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    }

    const verRef = doc(collection(docRef, "versions"), `v${newVersion}`);
    await setDoc(verRef, {
      userId: uid,
      docId,
      n: newVersion,
      sha256: hash,
      size,
      porNome: authorName?.trim()?.slice(0, 200) || "Advogado(a)",
      localPath,
      createdAt: serverTimestamp()
    });

    await this.appendAudit(uid, `Documento '${fileName}' versão ${newVersion}`, "documents", docId, authorName);
    await this.appendTimeline(uid, targetCaseId, `Documento “${fileName}” enviado (versão ${newVersion})`, authorName);
    return newVersion;
  }

  async getDocumentVersions(uid, docId) {
    const snap = await getDocs(collection(doc(this.docsCol(uid), docId), "versions"));
    return snap.docs
      .map((d) => ({ id: d.id, ...d.data() }))
      .sort((a, b) => Number(b.n || 0) - Number(a.n || 0));
  }

  async downloadDocumentVersion(uid, docItem, versionNum, authorName) {
    const stored = await loadDocumentBlob(uid, docItem.id, versionNum);
    if (!stored) {
      throw new Error(
        "O arquivo binário desta versão foi salvo em outro dispositivo/navegador. Os metadados e o SHA-256 permanecem registrados na nuvem."
      );
    }
    const blob = new Blob([stored.buffer], { type: stored.mimeType || "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = stored.fileName || docItem.nome || "documento";
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
    await this.appendAudit(uid, `Download '${docItem.nome}' v${versionNum}`, "documents", docItem.id, authorName);
  }

  async archiveDocument(uid, docId, authorName) {
    await updateDoc(doc(this.docsCol(uid), docId), {
      archivedAt: nowIso(),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, "Documento arquivado", "documents", docId, authorName);
  }

  // ---------- Team & Invites ----------

  async createInvite(uid, nome, email, role, authorName) {
    const cleanEmail = normalizeEmail(email);
    if (!["admin", "advogado", "assistente", "leitura"].includes(role)) {
      throw new Error("Perfil inválido.");
    }
    const cleanNome = nome?.trim()?.slice(0, 200) || null;
    const ref = doc(this.invitesCol(uid));
    const payload = {
      userId: uid,
      email: cleanEmail,
      role,
      status: "pendente",
      expiresAt: laterIso(7),
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    };
    if (cleanNome) payload.nome = cleanNome;
    await setDoc(ref, payload);
    await this.appendAudit(uid, `Convite enviado para ${cleanEmail}`, "invites", ref.id, authorName);
  }

  async resendInvite(uid, inviteId, authorName) {
    await updateDoc(doc(this.invitesCol(uid), inviteId), {
      expiresAt: laterIso(7),
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, "Validade do convite renovada", "invites", inviteId, authorName);
  }

  async revokeInvite(uid, inviteId, authorName) {
    await updateDoc(doc(this.invitesCol(uid), inviteId), {
      status: "revogado",
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, "Convite revogado", "invites", inviteId, authorName);
  }

  async updateMember(uid, memberId, role, status, authorName) {
    if (!["admin", "advogado", "assistente", "leitura"].includes(role)) {
      throw new Error("Perfil inválido.");
    }
    if (!["ativo", "inativo"].includes(status)) {
      throw new Error("Status inválido.");
    }
    await updateDoc(doc(this.teamCol(uid), memberId), {
      role,
      status,
      updatedAt: serverTimestamp()
    });
    await this.appendAudit(uid, `Membro atualizado (${role}, ${status})`, "team_members", memberId, authorName);
  }

  // ---------- Branding & JSON Export / Import ----------

  async saveBrandColors(uid, azulEscuro, azul, dourado, authorName) {
    if (!isValidHexColor(azulEscuro) || !isValidHexColor(azul) || !isValidHexColor(dourado)) {
      throw new Error("Use cores hexadecimais válidas no formato #RRGGBB.");
    }
    const ref = this.brandingDoc(uid);
    const snap = await getDoc(ref);
    if (!snap.exists()) {
      await setDoc(ref, {
        userId: uid,
        azulEscuro: azulEscuro.trim(),
        azul: azul.trim(),
        dourado: dourado.trim(),
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
    } else {
      await updateDoc(ref, {
        azulEscuro: azulEscuro.trim(),
        azul: azul.trim(),
        dourado: dourado.trim(),
        updatedAt: serverTimestamp()
      });
    }
    await this.appendAudit(uid, "Paleta de cores atualizada (Web)", "branding", "config", authorName);
  }

  async resetBrandColors(uid, authorName) {
    await deleteDoc(this.brandingDoc(uid));
    await this.appendAudit(uid, "Identidade visual restaurada", "branding", "config", authorName);
  }

  async exportJson(uid, clients, cases, items, docs, authorName) {
    const timelineSnap = await getDocs(this.timelineCol(uid));
    const bundle = {
      clients: clients.map((c) => ({
        id: c.id,
        nome: c.nome,
        doc: c.doc || null,
        email: c.email || null,
        tel: c.tel || null
      })),
      cases: cases.map((c) => ({
        id: c.id,
        client_id: c.clientId,
        titulo: c.titulo,
        numero: c.numero || null,
        area: c.area || null,
        status: c.status,
        prox_acao: c.proxAcao || null,
        prox_data: c.proxData || null,
        obs: c.obs || null
      })),
      items: items.map((i) => ({
        id: i.id,
        case_id: i.caseId,
        tipo: i.tipo,
        titulo: i.titulo,
        data: i.data,
        status: i.status,
        prio: i.prio,
        revisado: Boolean(i.revisado)
      })),
      documents: docs.map((d) => ({
        id: d.id,
        case_id: d.caseId,
        nome: d.nome,
        tipo: d.tipo || null
      })),
      timeline: timelineSnap.docs.map((t) => {
        const data = t.data();
        return {
          id: t.id,
          case_id: data.caseId,
          txt: data.txt,
          at: formatTimestampBr(data.createdAt, true)
        };
      })
    };
    await this.appendAudit(uid, "Backup JSON exportado (Web)", "export", uid, authorName);
    return JSON.stringify(bundle, null, 2);
  }

  async importJson(uid, rawText, authorName) {
    let data;
    try {
      data = JSON.parse(rawText);
    } catch {
      throw new Error("Arquivo JSON inválido.");
    }

    const clientIdMap = new Map();
    const caseIdMap = new Map();
    let clientsCount = 0;
    let casesCount = 0;
    let itemsCount = 0;

    for (const cl of data.clients || []) {
      const ref = doc(this.clientsCol(uid));
      const payload = {
        userId: uid,
        nome: String(cl.nome || "Cliente").trim().slice(0, 200) || "Cliente",
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      };
      if (cl.doc) payload.doc = String(cl.doc).trim().slice(0, 30);
      if (cl.email) payload.email = String(cl.email).trim().slice(0, 254);
      if (cl.tel) payload.tel = String(cl.tel).trim().slice(0, 30);
      await setDoc(ref, payload);
      clientIdMap.set(String(cl.id), ref.id);
      clientsCount++;
    }

    for (const cs of data.cases || []) {
      const mappedClient = clientIdMap.get(String(cs.client_id));
      if (!mappedClient) continue;
      const ref = doc(this.casesCol(uid));
      const status = ["Ativo", "Aguardando", "Encerrado"].includes(cs.status) ? cs.status : "Ativo";
      const payload = {
        userId: uid,
        clientId: mappedClient,
        titulo: String(cs.titulo || "Caso").trim().slice(0, 300) || "Caso",
        status,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      };
      if (cs.numero) payload.numero = String(cs.numero).trim().slice(0, 40);
      if (cs.area) payload.area = String(cs.area).trim().slice(0, 80);
      if (cs.prox_acao) payload.proxAcao = String(cs.prox_acao).trim().slice(0, 300);
      if (cs.prox_data && isValidIsoDate(cs.prox_data)) payload.proxData = cs.prox_data;
      if (cs.obs) payload.obs = String(cs.obs).trim().slice(0, 5000);
      await setDoc(ref, payload);
      caseIdMap.set(String(cs.id), ref.id);
      casesCount++;
    }

    for (const it of data.items || []) {
      const mappedCase = caseIdMap.get(String(it.case_id));
      if (!mappedCase) continue;
      const ref = doc(this.itemsCol(uid));
      const tipo = ["Tarefa", "Prazo", "Compromisso"].includes(it.tipo) ? it.tipo : "Tarefa";
      const status = ["Pendente", "Concluída", "Cancelada"].includes(it.status) ? it.status : "Pendente";
      const prio = ["Baixa", "Média", "Alta"].includes(it.prio) ? it.prio : "Média";
      const itemDate = isValidIsoDate(it.data) ? it.data : todayIso();
      await setDoc(ref, {
        userId: uid,
        caseId: mappedCase,
        tipo,
        titulo: String(it.titulo || "Item").trim().slice(0, 300) || "Item",
        data: itemDate,
        status,
        prio,
        revisado: Boolean(it.revisado),
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });
      itemsCount++;
    }

    await this.appendAudit(
      uid,
      `JSON importado (${clientsCount} clientes, ${casesCount} casos, ${itemsCount} itens)`,
      "import",
      uid,
      authorName
    );
    return { clientsCount, casesCount, itemsCount };
  }
}
