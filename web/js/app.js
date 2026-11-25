import { initializeApp, getApps } from "https://www.gstatic.com/firebasejs/11.4.0/firebase-app.js";
import {
  GoogleAuthProvider,
  OAuthProvider,
  createUserWithEmailAndPassword,
  getAuth,
  onAuthStateChanged,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  updateProfile
} from "https://www.gstatic.com/firebasejs/11.4.0/firebase-auth.js";
import { getFirestore } from "https://www.gstatic.com/firebasejs/11.4.0/firebase-firestore.js";
import {
  FIRESTORE_DATABASE_ID,
  getFirebaseWebConfig,
  getStoredWebAppId,
  setStoredWebAppId
} from "./firebase-config.js";
import {
  MAX_LOGO_BYTES,
  clearAllLocalLogos,
  getLocalLogos,
  setLocalLogo
} from "./local-store.js";
import {
  DEFAULT_BRAND,
  PrajusRepository,
  formatDateBr,
  formatTimestampBr,
  isValidIsoDate,
  todayIso,
  todayPlusDaysIso
} from "./prajus-repo.js";

const $ = (sel, root = document) => root.querySelector(sel);
const appEl = $("#app");
const dlg = $("#dlg");
const toastEl = $("#toast");

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  }[c]));

let toastTimer = null;
function toast(msg) {
  toastEl.textContent = msg;
  toastEl.style.display = "block";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toastEl.style.display = "none";
  }, 3600);
}

const ROLE_LABELS = {
  admin: "Administrador",
  advogado: "Advogado",
  assistente: "Assistente",
  leitura: "Somente leitura"
};

// ---------- App State ----------
const state = {
  user: null,
  authTab: "login", // "login" | "signup"
  authBusy: false,
  route: "hoje", // "hoje" | "casos" | "casoDetail" | "clientes" | "equipe" | "auditoria" | "aparencia"
  selectedCaseId: null,
  drawerOpen: false,
  streamError: null,
  profileLoaded: false,
  profile: null,
  branding: { ...DEFAULT_BRAND },
  clients: [],
  cases: [],
  items: [],
  documents: [],
  teamMembers: [],
  invites: [],
  audit: [],
  notifications: [],
  timeline: [],
  casesQuery: "",
  casesShowArchived: false,
  casesStatusFilter: null,
  casesSort: "nextDeadline", // "nextDeadline" | "dateCreated" | "clientName"
  clientsQuery: "",
  clientsShowArchived: false,
  onboardingShown: false
};

let unsubWorkspace = null;
let unsubTimeline = null;
let timelineCaseId = null;

// ---------- Firebase Boot ----------
const firebaseApp = getApps().length ? getApps()[0] : initializeApp(getFirebaseWebConfig());
const auth = getAuth(firebaseApp);
const db = getFirestore(firebaseApp, FIRESTORE_DATABASE_ID);
const repo = new PrajusRepository(db);

function friendlyAuthError(err) {
  const code = err?.code || "";
  switch (code) {
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request":
      return "Login cancelado antes da conclusão.";
    case "auth/popup-blocked":
      return "O navegador bloqueou a janela de login. Permita pop-ups e tente novamente.";
    case "auth/operation-not-allowed":
      return "Este provedor de login ainda não está ativado no Console Firebase (Authentication -> Sign-in method).";
    case "auth/unauthorized-domain":
      return "Este domínio ainda não foi autorizado no Console Firebase (Authentication -> Settings -> Authorized domains).";
    case "auth/invalid-credential":
    case "auth/wrong-password":
    case "auth/user-not-found":
      return "E-mail ou senha inválidos.";
    case "auth/email-already-in-use":
      return "Este e-mail já está cadastrado. Use a aba Entrar ou faça login com Google/Apple.";
    case "auth/weak-password":
      return "A senha deve ter pelo menos 6 caracteres.";
    case "auth/account-exists-with-different-credential":
      return "Já existe uma conta com este e-mail usando outro método de login.";
    default:
      return err?.message || "Falha na autenticação.";
  }
}

function applyBrandColors(brand) {
  const root = document.documentElement;
  root.style.setProperty("--nv", brand?.azulEscuro || DEFAULT_BRAND.azulEscuro);
  root.style.setProperty("--bl", brand?.azul || DEFAULT_BRAND.azul);
  root.style.setProperty("--go", brand?.dourado || DEFAULT_BRAND.dourado);
}

function currentAuthorName() {
  return (
    state.profile?.nome ||
    state.user?.displayName ||
    state.user?.email ||
    "Advogado(a)"
  );
}

function getLogoSrc(slot) {
  const uid = state.user?.uid;
  const custom = uid ? getLocalLogos(uid)[slot] : null;
  if (custom) return custom;
  if (slot === "horizontal") return "/brand/horizontal.svg";
  if (slot === "vertical") return "/brand/vertical.svg";
  return "/brand/icone.svg";
}

// ---------- Modal Helpers ----------
let dialogSeq = 0;
let activeModalCleanup = null;
let activeCaseDialogId = null;

function runModalCleanup() {
  if (typeof activeModalCleanup === "function") {
    try { activeModalCleanup(); } catch {}
    activeModalCleanup = null;
  }
  activeCaseDialogId = null;
  dlg.classList.remove("dlg-wide");
}

dlg.addEventListener("close", runModalCleanup);

function openModal(title, bodyHtml, { onConfirm, confirmLabel = "Salvar", showCancel = true, dismissible = true, extraHtml = "", wide = false } = {}) {
  runModalCleanup();
  dlg.classList.toggle("dlg-wide", Boolean(wide));
  const seq = ++dialogSeq;
  dlg.dataset.seq = String(seq);
  dlg.innerHTML = `
    <h2>${esc(title)}</h2>
    <div>${bodyHtml}</div>
    <p class="form-error" id="dlg-err"></p>
    <div class="dialog-actions">
      ${extraHtml}
      ${showCancel ? `<button type="button" class="btn ghost" id="dlg-cancel">Cancelar</button>` : ""}
      ${onConfirm ? `<button type="button" class="btn" id="dlg-ok">${esc(confirmLabel)}</button>` : `<button type="button" class="btn" id="dlg-close">Fechar</button>`}
    </div>
  `;

  dlg.oncancel = (e) => {
    if (!dismissible) e.preventDefault();
  };

  if (showCancel) {
    $("#dlg-cancel", dlg)?.addEventListener("click", () => dlg.close());
  }
  $("#dlg-close", dlg)?.addEventListener("click", () => dlg.close());

  if (onConfirm) {
    const okBtn = $("#dlg-ok", dlg);
    okBtn?.addEventListener("click", async () => {
      const errEl = $("#dlg-err", dlg);
      if (errEl) errEl.textContent = "";
      okBtn.disabled = true;
      try {
        const keepOpen = await onConfirm(dlg);
        if (Number(dlg.dataset.seq) === seq && !keepOpen) {
          dlg.close();
        }
      } catch (e) {
        if (errEl) errEl.textContent = e?.message || "Erro ao salvar.";
      } finally {
        if (Number(dlg.dataset.seq) === seq && okBtn) okBtn.disabled = false;
      }
    });
  }

  if (!dlg.open) dlg.showModal();
}

function formatFileSize(bytes) {
  const n = Number(bytes || 0);
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(2)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${n} B`;
}

function micButtonMarkup(btnId, label = "Ditar nota") {
  return `
    <button type="button" class="mic-btn" id="${btnId}" aria-pressed="false" title="Gravar nota de voz (SpeechRecognition)">
      <span class="mic-dot" aria-hidden="true"></span>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"/>
      </svg>
      <span class="mic-label">${esc(label)}</span>
    </button>
  `;
}

function attachSpeechRecognition(btn, targetInput, statusEl, defaultLabel = "Ditar nota") {
  if (!btn || !targetInput) return () => {};
  const SpeechRecognitionCtor = window.SpeechRecognition || window.webkitSpeechRecognition;
  let recognition = null;
  let recording = false;
  let baseText = "";
  let finalizedText = "";

  const setStatus = (msg, isActive = false) => {
    if (!statusEl) return;
    statusEl.textContent = msg || "";
    statusEl.classList.toggle("active", Boolean(isActive));
  };

  const resetUi = () => {
    recording = false;
    btn.classList.remove("recording");
    btn.setAttribute("aria-pressed", "false");
    const lbl = btn.querySelector(".mic-label");
    if (lbl) lbl.textContent = defaultLabel;
  };

  const stop = () => {
    if (recognition && recording) {
      try { recognition.stop(); } catch {}
    }
    resetUi();
  };

  btn.addEventListener("click", () => {
    if (!SpeechRecognitionCtor) {
      const msg = "Reconhecimento de voz não disponível neste navegador. Use Chrome, Edge ou Safari.";
      setStatus(msg, false);
      toast(msg);
      return;
    }

    if (recording) {
      stop();
      setStatus("Gravação encerrada.");
      return;
    }

    try {
      recognition = new SpeechRecognitionCtor();
      recognition.lang = "pt-BR";
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.maxAlternatives = 1;

      baseText = targetInput.value.trimEnd();
      finalizedText = "";
      recording = true;
      btn.classList.add("recording");
      btn.setAttribute("aria-pressed", "true");
      const lbl = btn.querySelector(".mic-label");
      if (lbl) lbl.textContent = "Parar gravação";
      setStatus("Ouvindo... fale sua nota de voz.", true);

      recognition.onresult = (event) => {
        let interim = "";
        let newlyFinal = "";
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const transcript = event.results[i][0]?.transcript || "";
          if (event.results[i].isFinal) {
            newlyFinal += transcript + " ";
          } else {
            interim += transcript;
          }
        }
        if (newlyFinal) {
          finalizedText = (finalizedText + " " + newlyFinal).trim();
        }
        const spoken = [finalizedText, interim.trim()].filter(Boolean).join(" ").trim();
        const combined = [baseText, spoken].filter(Boolean).join(baseText && spoken ? "\n" : "");
        targetInput.value = combined;
      };

      recognition.onerror = (event) => {
        const errMap = {
          "not-allowed": "Permissão de microfone negada pelo navegador.",
          "service-not-allowed": "Serviço de microfone bloqueado.",
          "no-speech": "Nenhuma fala detectada.",
          "audio-capture": "Nenhum microfone encontrado.",
          network: "Erro de rede no reconhecimento de voz."
        };
        setStatus(errMap[event.error] || `Erro de voz: ${event.error || "desconhecido"}`);
        resetUi();
      };

      recognition.onend = () => {
        const hadSpeech = Boolean(finalizedText.trim());
        resetUi();
        if (hadSpeech) setStatus("Nota transcrita para o campo acima.");
        else if (statusEl?.classList.contains("active")) setStatus("");
      };

      recognition.start();
    } catch (err) {
      resetUi();
      setStatus(err?.message || "Não foi possível iniciar o microfone.");
    }
  });

  const prevCleanup = activeModalCleanup;
  activeModalCleanup = () => {
    if (prevCleanup) prevCleanup();
    if (recognition) {
      try { recognition.abort(); } catch {}
      recognition = null;
    }
    resetUi();
  };

  return stop;
}

// ---------- Auth State Lifecycle ----------
onAuthStateChanged(auth, (user) => {
  if (unsubWorkspace) {
    unsubWorkspace();
    unsubWorkspace = null;
  }
  if (unsubTimeline) {
    unsubTimeline();
    unsubTimeline = null;
    timelineCaseId = null;
  }

  state.user = user;
  state.authBusy = false;
  state.profileLoaded = false;
  state.profile = null;
  state.streamError = null;
  state.onboardingShown = false;
  state.selectedCaseId = null;

  if (!user) {
    applyBrandColors(DEFAULT_BRAND);
    render();
    return;
  }

  unsubWorkspace = repo.subscribeWorkspace(
    user.uid,
    (key, value) => {
      state[key] = value;
      if (key === "profile") {
        state.profileLoaded = true;
      }
      if (key === "branding") {
        applyBrandColors(value);
      }
      if (key === "cases" || key === "items" || key === "notifications") {
        repo
          .syncDeadlineNotifications(user.uid, state.cases, state.items, state.notifications)
          .catch(() => {});
      }
      render();
      maybePromptOnboarding();
    },
    (errMsg) => {
      state.streamError = errMsg;
      render();
    }
  );
  render();
});

function maybePromptOnboarding() {
  if (!state.user || !state.profileLoaded || state.profile !== null || state.onboardingShown || dlg.open) {
    return;
  }
  state.onboardingShown = true;
  openProfileModal(true);
}

// ---------- Navigation & Timeline Subscription ----------
function navigate(route, caseId = null) {
  state.route = route;
  state.drawerOpen = false;
  if (route === "casoDetail" && caseId) {
    state.selectedCaseId = caseId;
    ensureTimelineSub(caseId);
  } else {
    state.selectedCaseId = null;
    if (unsubTimeline) {
      unsubTimeline();
      unsubTimeline = null;
      timelineCaseId = null;
      state.timeline = [];
    }
  }
  render();
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function ensureTimelineSub(caseId) {
  if (!state.user || timelineCaseId === caseId) return;
  if (unsubTimeline) {
    unsubTimeline();
    unsubTimeline = null;
  }
  timelineCaseId = caseId;
  state.timeline = [];
  unsubTimeline = repo.subscribeCaseTimeline(
    state.user.uid,
    caseId,
    (rows) => {
      state.timeline = rows;
      if (state.route === "casoDetail" && state.selectedCaseId === caseId) {
        render();
      }
    },
    (msg) => {
      state.streamError = msg;
      render();
    }
  );
}

// ---------- Login View ----------
function renderLogin() {
  const isSignup = state.authTab === "signup";
  appEl.innerHTML = `
    <div class="login-wrap">
      <div class="login-card">
        <img class="login-logo" src="/brand/vertical.svg" alt="Prajus">
        <p class="login-sub">Gestão jurídica sincronizada em tempo real no navegador e no Android.</p>

        <div class="oauth-stack">
          <button type="button" class="social-btn" id="btn-google" ${state.authBusy ? "disabled" : ""}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path fill="#EA4335" d="M12 10.2v3.9h5.5c-.24 1.25-.97 2.3-2.06 3.01l3.33 2.58c1.94-1.79 3.06-4.42 3.06-7.55 0-.72-.06-1.41-.19-2.08H12z"/>
              <path fill="#34A853" d="M12 22c2.77 0 5.1-.92 6.8-2.49l-3.33-2.58c-.92.62-2.1.99-3.47.99-2.67 0-4.93-1.8-5.74-4.22H2.82v2.65C4.51 19.71 7.98 22 12 22z"/>
              <path fill="#4A90E2" d="M6.26 13.7A5.95 5.95 0 0 1 5.95 12c0-.59.11-1.16.31-1.7V7.65H2.82A9.97 9.97 0 0 0 2 12c0 1.61.39 3.13 1.08 4.35l3.18-2.65z"/>
              <path fill="#FBBC05" d="M12 6.08c1.51 0 2.86.52 3.93 1.54l2.95-2.95C17.09 3.01 14.77 2 12 2 7.98 2 4.51 4.29 2.82 7.65l3.44 2.65C7.07 7.88 9.33 6.08 12 6.08z"/>
            </svg>
            Continuar com Google
          </button>

          <button type="button" class="social-btn apple" id="btn-apple" ${state.authBusy ? "disabled" : ""}>
            <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M16.37 1.43c0 1.14-.5 2.27-1.18 3.08-.74.9-1.99 1.57-2.99 1.57-.12 0-.23-.02-.3-.03-.01-.06-.04-.22-.04-.39 0-1.15.57-2.27 1.21-2.98.8-.94 2.14-1.64 3.25-1.68.03.13.05.28.05.43zm4.56 15.71c-.03.07-.46 1.58-1.52 3.12-.94 1.34-1.94 2.71-3.43 2.71-1.52 0-1.9-.88-3.63-.88-1.7 0-2.3.91-3.67.91-1.38 0-2.33-1.26-3.43-2.8-1.29-1.82-2.32-4.63-2.32-7.28 0-4.28 2.8-6.55 5.55-6.55 1.45 0 2.68.95 3.6.95.87 0 2.22-1.01 3.9-1.01.61 0 2.89.06 4.37 2.19-.13.09-2.38 1.37-2.38 4.19 0 3.26 2.85 4.42 2.96 4.45z"/>
            </svg>
            Continuar com Apple
          </button>
        </div>

        <div class="or-divider">ou use seu e-mail</div>

        <div class="auth-tabs" role="tablist">
          <button type="button" class="auth-tab ${!isSignup ? "active" : ""}" id="tab-login">Entrar</button>
          <button type="button" class="auth-tab ${isSignup ? "active" : ""}" id="tab-signup">Criar conta</button>
        </div>

        <form id="email-auth-form">
          ${isSignup ? `
            <label for="auth-name">Seu nome</label>
            <input id="auth-name" type="text" autocomplete="name" required placeholder="Dra. Maria Silva">
            <label for="auth-org">Nome do escritório</label>
            <input id="auth-org" type="text" required placeholder="Silva Advocacia">
          ` : ""}
          <label for="auth-email">E-mail</label>
          <input id="auth-email" type="email" autocomplete="email" required placeholder="advogado@escritorio.com.br">
          <label for="auth-pass">Senha</label>
          <input id="auth-pass" type="password" autocomplete="${isSignup ? "new-password" : "current-password"}" required minlength="6" placeholder="••••••••">
          <p class="form-error" id="login-err"></p>
          <button type="submit" class="btn" style="width:100%;margin-top:10px" ${state.authBusy ? "disabled" : ""}>
            ${isSignup ? "Criar conta com e-mail" : "Entrar com e-mail"}
          </button>
        </form>

        <div class="login-links">
          <button type="button" class="link-btn" id="btn-forgot">Esqueci a senha</button>
          <button type="button" class="link-btn" id="btn-fb-setup">Configurar Web App ID</button>
        </div>
      </div>
    </div>
  `;

  $("#tab-login")?.addEventListener("click", () => {
    state.authTab = "login";
    renderLogin();
  });
  $("#tab-signup")?.addEventListener("click", () => {
    state.authTab = "signup";
    renderLogin();
  });

  $("#btn-google")?.addEventListener("click", async () => {
    await runSocialLogin(new GoogleAuthProvider());
  });

  $("#btn-apple")?.addEventListener("click", async () => {
    const provider = new OAuthProvider("apple.com");
    provider.addScope("email");
    provider.addScope("name");
    await runSocialLogin(provider);
  });

  $("#email-auth-form")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const errEl = $("#login-err");
    errEl.textContent = "";
    const email = $("#auth-email").value.trim();
    const pass = $("#auth-pass").value;
    state.authBusy = true;
    try {
      if (isSignup) {
        const nome = $("#auth-name").value.trim();
        const orgName = $("#auth-org").value.trim();
        if (!nome || !orgName) throw new Error("Informe seu nome e o nome do escritório.");
        const cred = await createUserWithEmailAndPassword(auth, email, pass);
        await updateProfile(cred.user, { displayName: nome }).catch(() => {});
        await repo.saveUserProfile(cred.user.uid, nome, email, orgName, "");
        toast("Conta criada com sucesso.");
      } else {
        await signInWithEmailAndPassword(auth, email, pass);
      }
    } catch (err) {
      state.authBusy = false;
      errEl.textContent = friendlyAuthError(err);
    }
  });

  $("#btn-forgot")?.addEventListener("click", () => {
    openModal(
      "Recuperar senha",
      `<label for="reset-email">E-mail da conta</label>
       <input id="reset-email" type="email" placeholder="advogado@escritorio.com.br">`,
      {
        confirmLabel: "Enviar link",
        onConfirm: async (modal) => {
          const email = $("#reset-email", modal).value.trim();
          if (!email) throw new Error("Informe seu e-mail.");
          await sendPasswordResetEmail(auth, email);
          toast("E-mail de redefinição enviado.");
        }
      }
    );
  });

  $("#btn-fb-setup")?.addEventListener("click", () => {
    const currentId = getStoredWebAppId();
    openModal(
      "Configuração Web do Firebase",
      `<p class="mu">Projeto conectado: <strong>gen-lang-client-0019377953</strong><br>
       Banco Firestore: <code>${esc(FIRESTORE_DATABASE_ID)}</code></p>
       <label for="fb-appid">Web App ID (opcional / obtido no Console Firebase)</label>
       <input id="fb-appid" type="text" value="${esc(currentId)}" placeholder="1:745649861358:web:...">
       <p class="mu" style="margin-top:8px">Lembre-se de ativar os provedores Google, Apple e E-mail/senha em Authentication no Console Firebase.</p>`,
      {
        confirmLabel: "Salvar e recarregar",
        onConfirm: async (modal) => {
          setStoredWebAppId($("#fb-appid", modal).value);
          location.reload();
        }
      }
    );
  });
}

async function runSocialLogin(provider) {
  const errEl = $("#login-err");
  if (errEl) errEl.textContent = "";
  state.authBusy = true;
  try {
    await signInWithPopup(auth, provider);
  } catch (err) {
    state.authBusy = false;
    if (errEl) errEl.textContent = friendlyAuthError(err);
    else toast(friendlyAuthError(err));
  }
}

// ---------- Derived Data Helpers ----------
function getPendingItemsByCase() {
  const map = new Map();
  const pending = state.items
    .filter((i) => !i.archivedAt && i.status !== "Concluída")
    .sort((a, b) => {
      const cmp = String(a.data || "").localeCompare(String(b.data || ""));
      if (cmp !== 0) return cmp;
      return (a.tipo === "Prazo" ? 0 : 1) - (b.tipo === "Prazo" ? 0 : 1);
    });
  for (const item of pending) {
    if (!map.has(item.caseId)) map.set(item.caseId, []);
    map.get(item.caseId).push(item);
  }
  return map;
}

function getClientNameMap() {
  return new Map(state.clients.map((c) => [c.id, c.nome]));
}

function computeDeadlineBadge(isoDate, today, plus3) {
  if (!isoDate) return null;
  const formatted = formatDateBr(isoDate);
  if (isoDate < today) return { kind: "overdue", label: "Vencido", formatted };
  if (isoDate === today) return { kind: "urgent", label: "Hoje", formatted };
  if (isoDate <= plus3) {
    const diff = Math.round((new Date(isoDate + "T00:00:00") - new Date(today + "T00:00:00")) / 86400000);
    return { kind: "urgent", label: diff >= 1 && diff <= 3 ? `Em ${diff}d` : "Em breve", formatted };
  }
  return { kind: "upcoming", label: "Prazo", formatted };
}

// ---------- Shell Render ----------
function render() {
  if (!state.user) {
    renderLogin();
    return;
  }

  const orgName = state.profile?.orgName || "Meu Escritório";
  const userName = currentAuthorName();
  const unreadCount = state.notifications.filter((n) => !n.lida).length;
  const unreviewedCount = state.items.filter(
    (i) => !i.archivedAt && i.tipo === "Prazo" && i.status === "Pendente" && !i.revisado
  ).length;

  const navItems = [
    { id: "hoje", label: "Hoje", icon: "📅", badge: unreviewedCount > 0 ? unreviewedCount : null },
    { id: "casos", label: "Casos", icon: "📁" },
    { id: "clientes", label: "Clientes", icon: "👥" },
    { id: "equipe", label: "Equipe", icon: "🏛️" },
    { id: "auditoria", label: "Auditoria", icon: "🧾" },
    { id: "aparencia", label: "Aparência", icon: "🎨" }
  ];

  const isRouteActive = (id) =>
    state.route === id || (id === "casos" && state.route === "casoDetail");

  appEl.innerHTML = `
    <div class="app-shell">
      <div class="drawer-backdrop ${state.drawerOpen ? "open" : ""}" id="drawer-backdrop"></div>
      <aside class="sidebar ${state.drawerOpen ? "open" : ""}" aria-label="Navegação principal">
        <div class="sidebar-brand">
          <img src="${esc(getLogoSrc("horizontal"))}" alt="Prajus">
        </div>
        <div class="sidebar-org">
          <div class="sidebar-org-name">${esc(orgName)}</div>
          <div class="sidebar-org-user">${esc(userName)}</div>
        </div>
        <div class="nav-list">
          ${navItems
            .map(
              (it) => `
            <button type="button" class="nav-item ${isRouteActive(it.id) ? "active" : ""}" data-nav="${it.id}">
              <span aria-hidden="true">${it.icon}</span>
              <span>${esc(it.label)}</span>
              ${it.badge ? `<span class="badge" title="Prazos aguardando revisão">${it.badge}</span>` : ""}
            </button>`
            )
            .join("")}
        </div>
        <div class="nav-spacer"></div>
        <div class="nav-divider"></div>
        <button type="button" class="nav-item" id="nav-profile">
          <span aria-hidden="true">🏢</span><span>Perfil do escritório</span>
        </button>
        <button type="button" class="nav-item" id="nav-logout">
          <span aria-hidden="true">🚪</span><span>Sair</span>
        </button>
      </aside>

      <div class="workspace-area">
        <header class="topbar">
          <div class="topbar-left">
            <button type="button" class="mobile-menu-btn" id="btn-mobile-menu" aria-label="Abrir menu">☰</button>
            <div class="sync-pill" title="Sincronizado em tempo real com Cloud Firestore">
              <span class="sync-dot" aria-hidden="true"></span>
              <span>Nuvem ativa</span>
            </div>
          </div>
          <div class="topbar-actions">
            <button type="button" class="icon-action-btn" id="btn-global-search" title="Busca global (Ctrl+K)">
              🔍 <span>Buscar</span>
            </button>
            <button type="button" class="icon-action-btn" id="btn-notifications">
              🔔 <span>Alertas</span>
              ${unreadCount > 0 ? `<span class="count-pill">${unreadCount}</span>` : ""}
            </button>
          </div>
        </header>

        <main class="page-main">
          ${
            state.streamError
              ? `<div class="error-banner">
                   <span>${esc(state.streamError)}</span>
                   <button type="button" class="btn sm ghost" id="dismiss-stream-err">Fechar</button>
                 </div>`
              : ""
          }
          <div id="view-root"></div>
        </main>
      </div>
    </div>
  `;

  $("#btn-mobile-menu")?.addEventListener("click", () => {
    state.drawerOpen = !state.drawerOpen;
    render();
  });
  $("#drawer-backdrop")?.addEventListener("click", () => {
    state.drawerOpen = false;
    render();
  });
  appEl.querySelectorAll("[data-nav]").forEach((btn) => {
    btn.addEventListener("click", () => navigate(btn.dataset.nav));
  });
  $("#nav-profile")?.addEventListener("click", () => openProfileModal(false));
  $("#nav-logout")?.addEventListener("click", async () => {
    await signOut(auth);
  });
  $("#btn-global-search")?.addEventListener("click", openGlobalSearchModal);
  $("#btn-notifications")?.addEventListener("click", openNotificationsModal);
  $("#dismiss-stream-err")?.addEventListener("click", () => {
    state.streamError = null;
    render();
  });

  const viewRoot = $("#view-root");
  if (state.route === "hoje") renderHoje(viewRoot);
  else if (state.route === "casos") renderCasos(viewRoot);
  else if (state.route === "casoDetail") renderCasoDetail(viewRoot);
  else if (state.route === "clientes") renderClientes(viewRoot);
  else if (state.route === "equipe") renderEquipe(viewRoot);
  else if (state.route === "auditoria") renderAuditoria(viewRoot);
  else if (state.route === "aparencia") renderAparencia(viewRoot);

  if (dlg.open && activeCaseDialogId) {
    refreshOpenCaseDetailDialog();
  }
}

// ---------- View: Hoje ----------
function renderHoje(root) {
  const today = todayIso();
  const plus10 = todayPlusDaysIso(10);
  const activeCases = state.cases.filter((c) => !c.archivedAt);
  const caseMap = new Map(activeCases.map((c) => [c.id, c]));
  const activeItems = state.items.filter((i) => !i.archivedAt && i.status !== "Concluída" && caseMap.has(i.caseId));

  const vencidas = activeItems.filter((i) => i.data < today);
  const hoje = activeItems.filter((i) => i.data === today);
  const prazos = activeItems.filter((i) => i.tipo === "Prazo" && i.data >= today && i.data <= plus10);
  const semAcao = activeCases.filter((c) => c.status === "Ativo" && !c.proxAcao);
  const aguardandoRev = activeItems.filter((i) => i.tipo === "Prazo" && !i.revisado).length;

  const renderItemRows = (list, emptyMsg) => {
    if (!list.length) return `<p class="mu">${esc(emptyMsg)}</p>`;
    return list
      .map((it) => {
        const caseObj = caseMap.get(it.caseId);
        return `
          <div class="row-item">
            <div class="grow">
              <div><strong>${esc(it.titulo)}</strong></div>
              <div class="mu">${esc(it.tipo)} · ${esc(formatDateBr(it.data))} · ${esc(caseObj?.titulo || "")} · Prio ${esc(it.prio)}</div>
              ${it.tipo === "Prazo" && !it.revisado ? `<span class="pill amber" style="margin-top:4px">Aguarda revisão humana</span>` : ""}
            </div>
            <div style="display:flex;gap:6px;flex-wrap:wrap">
              ${it.tipo === "Prazo" && !it.revisado ? `<button type="button" class="btn sm tonal" data-confirm-item="${esc(it.id)}">Confirmar prazo</button>` : ""}
              <button type="button" class="btn sm ghost" data-complete-item="${esc(it.id)}">Concluir</button>
              <button type="button" class="btn sm ghost" data-open-case="${esc(it.caseId)}">Abrir caso</button>
            </div>
          </div>`;
      })
      .join("");
  };

  root.innerHTML = `
    <div class="page-header">
      <div>
        <h1 class="accented">Hoje</h1>
        <p class="page-subtitle">Resumo diário de pendências, prazos e casos ativos</p>
      </div>
      ${aguardandoRev > 0 ? `<span class="pill amber">${aguardandoRev} prazo(s) aguardando confirmação humana</span>` : ""}
    </div>

    <div class="cols-2">
      <section class="box">
        <h2>Vencidas (${vencidas.length})</h2>
        ${renderItemRows(vencidas, "Nada vencido.")}
      </section>
      <section class="box">
        <h2>Para hoje (${hoje.length})</h2>
        ${renderItemRows(hoje, "Nada para hoje.")}
      </section>
    </div>

    <section class="box">
      <h2>Prazos nos próximos 10 dias (${prazos.length})</h2>
      ${renderItemRows(prazos, "Sem prazos próximos.")}
    </section>

    <section class="box">
      <h2>Casos sem próxima ação (${semAcao.length})</h2>
      ${
        !semAcao.length
          ? `<p class="mu">Todos os casos ativos têm próxima ação definida.</p>`
          : semAcao
              .map(
                (c) => `
              <div class="row-item">
                <div class="grow">
                  <strong>${esc(c.titulo)}</strong>
                  <div class="mu">${esc(c.area || "Geral")} ${c.numero ? "· " + esc(c.numero) : ""}</div>
                </div>
                <button type="button" class="btn sm ghost" data-open-case="${esc(c.id)}">Definir ação</button>
              </div>`
              )
              .join("")
      }
    </section>
  `;

  root.querySelectorAll("[data-open-case]").forEach((btn) =>
    btn.addEventListener("click", () => navigate("casoDetail", btn.dataset.openCase))
  );
  root.querySelectorAll("[data-complete-item]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      const item = state.items.find((x) => x.id === btn.dataset.completeItem);
      if (!item) return;
      await repo.updateItemStatus(state.user.uid, item, "Concluída", currentAuthorName());
      toast("Item concluído.");
    })
  );
  root.querySelectorAll("[data-confirm-item]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      const item = state.items.find((x) => x.id === btn.dataset.confirmItem);
      if (!item) return;
      await repo.confirmDeadline(state.user.uid, item, currentAuthorName());
      toast("Prazo confirmado por revisão humana.");
    })
  );
}

// ---------- View: Casos (Centralized Grid) ----------
function renderCasos(root) {
  const today = todayIso();
  const plus3 = todayPlusDaysIso(3);
  const plus10 = todayPlusDaysIso(10);
  const clientMap = getClientNameMap();
  const pendingByCase = getPendingItemsByCase();

  const activeAll = state.cases.filter((c) => !c.archivedAt);
  let overdueCases = 0;
  let upcomingCases = 0;
  let missingAction = 0;
  for (const c of activeAll) {
    const pend = pendingByCase.get(c.id) || [];
    if (pend.some((i) => i.data < today)) overdueCases++;
    if (pend.some((i) => i.data >= today && i.data <= plus10) || (c.proxData && c.proxData >= today && c.proxData <= plus10)) {
      upcomingCases++;
    }
    if (c.status === "Ativo" && !c.proxAcao) missingAction++;
  }

  let filtered = state.cases.filter((c) => (state.casesShowArchived ? Boolean(c.archivedAt) : !c.archivedAt));
  if (!state.casesShowArchived && state.casesStatusFilter) {
    if (state.casesStatusFilter === "Com prazo") {
      filtered = filtered.filter((c) => (pendingByCase.get(c.id) || []).length > 0 || Boolean(c.proxData));
    } else {
      filtered = filtered.filter((c) => c.status === state.casesStatusFilter);
    }
  }
  const norm = (v) =>
    String(v || "")
      .trim()
      .toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "");
  const q = state.casesQuery.trim();
  const qNorm = norm(q);
  const qDigits = q.replace(/\D/g, "");
  if (qNorm) {
    filtered = filtered.filter((c) => {
      const clName = norm(clientMap.get(c.clientId) || "");
      const numRaw = String(c.numero || "");
      const numDigits = numRaw.replace(/\D/g, "");
      return (
        norm(c.titulo).includes(qNorm) ||
        norm(numRaw).includes(qNorm) ||
        (qDigits.length > 0 && numDigits.includes(qDigits)) ||
        norm(c.area).includes(qNorm) ||
        norm(c.obs).includes(qNorm) ||
        clName.includes(qNorm)
      );
    });
  }
  if (state.casesSort === "dateCreated") {
    filtered.sort((a, b) => {
      const ta = (a.createdAt?.toMillis?.() || a.updatedAt?.toMillis?.() || 0);
      const tb = (b.createdAt?.toMillis?.() || b.updatedAt?.toMillis?.() || 0);
      return tb - ta || norm(a.titulo).localeCompare(norm(b.titulo), "pt-BR");
    });
  } else if (state.casesSort === "clientName") {
    filtered.sort((a, b) => {
      const ca = norm(clientMap.get(a.clientId) || "zzz");
      const cb = norm(clientMap.get(b.clientId) || "zzz");
      return ca.localeCompare(cb, "pt-BR") || norm(a.titulo).localeCompare(norm(b.titulo), "pt-BR");
    });
  } else {
    filtered.sort((a, b) => {
      const da = pendingByCase.get(a.id)?.[0]?.data || a.proxData || "9999-12-31";
      const db = pendingByCase.get(b.id)?.[0]?.data || b.proxData || "9999-12-31";
      return da.localeCompare(db) || norm(a.titulo).localeCompare(norm(b.titulo), "pt-BR");
    });
  }

  const statuses = ["Ativo", "Aguardando", "Encerrado", "Com prazo"];

  root.innerHTML = `
    <div class="page-header">
      <div>
        <h1 class="accented">Casos</h1>
        <p class="page-subtitle">Central de processos ativos, status e próximos prazos</p>
      </div>
      <button type="button" class="btn" id="btn-new-case">+ Novo caso</button>
    </div>

    ${
      !state.casesShowArchived
        ? `<div class="kpi-strip">
             <div class="kpi-tile">
               <div class="kpi-icon">📁</div>
               <div><div class="kpi-value">${activeAll.length}</div><div class="kpi-label">Casos ativos</div></div>
             </div>
             <button type="button" class="kpi-tile clickable ${state.casesStatusFilter === "Com prazo" ? "selected" : ""}" id="kpi-deadlines">
               <div class="kpi-icon ${overdueCases > 0 ? "danger" : "amber"}">⏰</div>
               <div>
                 <div class="kpi-value">${overdueCases > 0 ? `${upcomingCases} (${overdueCases} venc.)` : upcomingCases}</div>
                 <div class="kpi-label">Prazos (10d)</div>
               </div>
             </button>
             <div class="kpi-tile">
               <div class="kpi-icon ${missingAction > 0 ? "amber" : ""}">📋</div>
               <div><div class="kpi-value">${missingAction}</div><div class="kpi-label">Sem próxima ação</div></div>
             </div>
           </div>`
        : ""
    }

    <input id="cases-search" type="search" placeholder="Buscar por título, cliente, número ou área..." value="${esc(state.casesQuery)}">

    <div class="filter-bar">
      <select id="cases-sort" class="chip" aria-label="Ordenar casos" style="width:auto">
        <option value="nextDeadline" ${state.casesSort === "nextDeadline" ? "selected" : ""}>Ordenar: Próximo prazo</option>
        <option value="dateCreated" ${state.casesSort === "dateCreated" ? "selected" : ""}>Ordenar: Data de criação</option>
        <option value="clientName" ${state.casesSort === "clientName" ? "selected" : ""}>Ordenar: Nome do cliente</option>
      </select>
      <span style="width:1px;height:20px;background:var(--ln);margin:0 4px"></span>
      <button type="button" class="chip ${!state.casesShowArchived ? "active" : ""}" id="chip-active">Ativos</button>
      <button type="button" class="chip ${state.casesShowArchived ? "active" : ""}" id="chip-archived">Arquivados</button>
      ${
        !state.casesShowArchived
          ? `<span style="width:1px;height:20px;background:var(--ln);margin:0 4px"></span>
             <button type="button" class="chip ${state.casesStatusFilter === null ? "active" : ""}" data-status-filter="">Todos</button>
             ${statuses
               .map(
                 (st) =>
                   `<button type="button" class="chip ${state.casesStatusFilter === st ? "active" : ""}" data-status-filter="${esc(st)}">${esc(st)}</button>`
               )
               .join("")}`
          : ""
      }
    </div>

    ${
      !filtered.length
        ? `<div class="box"><p class="mu">${
            state.casesShowArchived
              ? "Nenhum caso arquivado."
              : q || state.casesStatusFilter
                ? "Nenhum caso encontrado para o filtro atual."
                : "Nenhum caso ativo. Crie o primeiro caso do escritório."
          }</p></div>`
        : `<div class="cases-grid">
            ${filtered
              .map((c) => {
                const pend = pendingByCase.get(c.id) || [];
                const nearest = pend[0] || null;
                const targetDate = nearest?.data || c.proxData || null;
                const badge = computeDeadlineBadge(targetDate, today, plus3);
                const cardClass = badge?.kind === "overdue" ? "overdue" : badge?.kind === "urgent" ? "urgent" : "";
                const barClass =
                  badge?.kind === "overdue"
                    ? "overdue"
                    : badge?.kind === "urgent"
                      ? "urgent"
                      : c.status === "Aguardando"
                        ? "waiting"
                        : c.status === "Encerrado"
                          ? "closed"
                          : "";
                const statusPillClass =
                  c.status === "Aguardando" ? "amber" : c.status === "Encerrado" ? "neutral" : "";

                const caseDocCount = state.documents.filter((d) => d.caseId === c.id && !d.archivedAt).length;

                return `
                  <article class="case-card ${cardClass}" data-open-case="${esc(c.id)}" tabindex="0">
                    <div class="case-accent-bar ${barClass}"></div>
                    <div class="case-card-body">
                      <div class="case-card-top">
                        <span class="case-area">${esc(c.area || "Geral")}</span>
                        <span class="pill ${statusPillClass}">${esc(c.status)}</span>
                      </div>
                      <div>
                        <div class="case-title">${esc(c.titulo)}</div>
                        <div class="case-meta">
                          <span>👤 ${esc(clientMap.get(c.clientId) || "—")}</span>
                          ${c.numero ? `<span>⚖️ ${esc(c.numero)}</span>` : ""}
                          ${caseDocCount > 0 ? `<span>📄 ${caseDocCount} documento(s)</span>` : ""}
                        </div>
                      </div>

                      <div class="deadline-panel ${badge ? badge.kind : ""}">
                        ${
                          nearest && badge
                            ? `<div class="deadline-head">
                                 <span style="display:flex;align-items:center;gap:6px">
                                   <span class="deadline-tag ${badge.kind}">${esc(badge.label)}</span>
                                   <strong>${esc(badge.formatted)}</strong>
                                 </span>
                                 ${pend.length > 1 ? `<span class="mu">+${pend.length - 1} pendente(s)</span>` : ""}
                               </div>
                               <div style="font-size:13px"><strong>${esc(nearest.tipo)}:</strong> ${esc(nearest.titulo)}</div>
                               ${nearest.tipo === "Prazo" && !nearest.revisado ? `<div style="font-size:12px;font-weight:700;color:var(--am)">Aguarda revisão humana</div>` : ""}`
                            : c.proxAcao
                              ? `<div style="font-size:12px;font-weight:700;color:var(--acx)">Próxima ação ${c.proxData ? "(" + esc(formatDateBr(c.proxData)) + ")" : ""}</div>
                                 <div style="font-size:13px">${esc(c.proxAcao)}</div>`
                              : `<div class="mu">Sem prazos pendentes</div>`
                        }
                      </div>

                      <div class="case-card-footer">
                        ${
                          state.casesShowArchived
                            ? `<button type="button" class="btn sm ghost" style="width:100%" data-restore-case="${esc(c.id)}">Restaurar caso</button>`
                            : `<span>${c.proxAcao && nearest ? "Próx.: " + esc(c.proxAcao.slice(0, 22)) : "Ver detalhes"}</span>
                               <div class="case-card-actions">
                                 <button type="button" class="btn sm tonal" data-dialog-case="${esc(c.id)}">Diálogo</button>
                                 <span>→</span>
                               </div>`
                        }
                      </div>
                    </div>
                  </article>`;
              })
              .join("")}
          </div>`
    }
  `;

  $("#btn-new-case")?.addEventListener("click", openNewCaseModal);
  $("#kpi-deadlines")?.addEventListener("click", () => {
    state.casesStatusFilter = state.casesStatusFilter === "Com prazo" ? null : "Com prazo";
    render();
  });
  const searchEl = $("#cases-search");
  searchEl?.addEventListener("input", (e) => {
    state.casesQuery = e.target.value;
    const pos = e.target.selectionStart;
    render();
    const nextSearch = $("#cases-search");
    if (nextSearch) {
      nextSearch.focus();
      nextSearch.setSelectionRange(pos, pos);
    }
  });
  $("#cases-sort")?.addEventListener("change", (e) => {
    state.casesSort = e.target.value;
    render();
  });
  $("#chip-active")?.addEventListener("click", () => {
    state.casesShowArchived = false;
    render();
  });
  $("#chip-archived")?.addEventListener("click", () => {
    state.casesShowArchived = true;
    render();
  });
  root.querySelectorAll("[data-status-filter]").forEach((btn) =>
    btn.addEventListener("click", () => {
      const val = btn.dataset.statusFilter || null;
      state.casesStatusFilter = state.casesStatusFilter === val ? null : val;
      render();
    })
  );
  root.querySelectorAll("[data-restore-case]").forEach((btn) =>
    btn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const c = state.cases.find((x) => x.id === btn.dataset.restoreCase);
      if (!c) return;
      await repo.archiveCase(state.user.uid, c.id, c.titulo, true, currentAuthorName());
      toast("Caso restaurado.");
    })
  );
  root.querySelectorAll("[data-dialog-case]").forEach((btn) =>
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      openCaseDetailDialog(btn.dataset.dialogCase);
    })
  );
  root.querySelectorAll("[data-open-case]").forEach((card) => {
    card.addEventListener("click", () => navigate("casoDetail", card.dataset.openCase));
    card.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        navigate("casoDetail", card.dataset.openCase);
      }
    });
  });
}

function openNewCaseModal() {
  const activeClients = state.clients.filter((c) => !c.archivedAt);
  if (!activeClients.length) {
    toast("Cadastre um cliente primeiro.");
    return;
  }
  openModal(
    "Novo caso",
    `<label for="nc-titulo">Título</label>
     <input id="nc-titulo" type="text" placeholder="Ex.: Ação de cobrança – Empresa X">
     <label for="nc-client">Cliente</label>
     <select id="nc-client">
       ${activeClients.map((cl) => `<option value="${esc(cl.id)}">${esc(cl.nome)}</option>`).join("")}
     </select>
     <label for="nc-num">Número do processo</label>
     <input id="nc-num" type="text" placeholder="0000000-00.2026.8.26.0000">
     <label for="nc-area">Área</label>
     <input id="nc-area" type="text" placeholder="Cível, Trabalhista, Família...">
     <label for="nc-obs">Observações</label>
     <textarea id="nc-obs" rows="3"></textarea>`,
    {
      onConfirm: async (modal) => {
        const id = await repo.createCase(
          state.user.uid,
          $("#nc-titulo", modal).value,
          $("#nc-client", modal).value,
          $("#nc-num", modal).value,
          $("#nc-area", modal).value,
          $("#nc-obs", modal).value,
          currentAuthorName()
        );
        toast("Caso criado.");
        navigate("casoDetail", id);
      }
    }
  );
}

// ---------- View: Caso Detail ----------
function renderCasoDetail(root) {
  const c = state.cases.find((x) => x.id === state.selectedCaseId);
  if (!c) {
    root.innerHTML = `<div class="box"><p class="mu">Carregando caso...</p><button type="button" class="btn ghost" id="back-cases">Voltar</button></div>`;
    $("#back-cases")?.addEventListener("click", () => navigate("casos"));
    return;
  }

  ensureTimelineSub(c.id);
  const clientName = getClientNameMap().get(c.clientId) || "—";
  const caseItems = state.items.filter((i) => i.caseId === c.id && !i.archivedAt);
  const caseDocs = state.documents.filter((d) => d.caseId === c.id && !d.archivedAt);

  root.innerHTML = `
    <div style="margin-bottom:10px">
      <button type="button" class="btn sm ghost" id="btn-back-casos">← Voltar para Casos</button>
    </div>

    <div class="page-header">
      <div>
        <h1 class="accented">${esc(c.titulo)}</h1>
        <p class="page-subtitle">Cliente: ${esc(clientName)} · ${esc(c.area || "Geral")} ${c.numero ? "· " + esc(c.numero) : ""}</p>
      </div>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <span class="pill ${c.status === "Aguardando" ? "amber" : c.status === "Encerrado" ? "neutral" : ""}">${esc(c.status)}</span>
        <button type="button" class="btn sm tonal" id="btn-case-dialog">📋 Diálogo do caso</button>
        <button type="button" class="btn sm tonal" id="btn-case-report">📄 Baixar relatório</button>
        <button type="button" class="btn sm tonal" id="btn-voice-memo">🎙️ Nota de voz</button>
        <button type="button" class="btn sm ghost" id="btn-next-action">Próxima ação</button>
        <button type="button" class="btn sm ghost" id="btn-edit-case">Editar</button>
        ${
          c.archivedAt
            ? `<button type="button" class="btn sm" id="btn-restore-case">Restaurar</button>`
            : `<button type="button" class="btn sm ghost" id="btn-archive-case">Arquivar</button>`
        }
      </div>
    </div>

    <section class="box">
      <div class="cols-2">
        <div>
          <div class="mu">Próxima ação</div>
          <div><strong>${esc(c.proxAcao || "Não definida")}</strong> ${c.proxData ? "· " + esc(formatDateBr(c.proxData)) : ""}</div>
        </div>
        <div>
          <div class="mu">Observações</div>
          <div>${esc(c.obs || "—")}</div>
        </div>
      </div>
    </section>

    <div class="cols-2">
      <section class="box">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
          <h2 style="margin:0">Prazos e tarefas (${caseItems.length})</h2>
          <button type="button" class="btn sm tonal" id="btn-new-item">+ Novo item</button>
        </div>
        ${
          !caseItems.length
            ? `<p class="mu">Nenhum prazo ou tarefa cadastrado.</p>`
            : caseItems
                .map(
                  (it) => `
                <div class="row-item">
                  <div class="grow">
                    <div><strong>${esc(it.titulo)}</strong></div>
                    <div class="mu">${esc(it.tipo)} · ${esc(formatDateBr(it.data))} · ${esc(it.status)} · Prio ${esc(it.prio)}</div>
                    ${it.tipo === "Prazo" && !it.revisado ? `<span class="pill amber" style="margin-top:4px">Aguarda revisão humana</span>` : ""}
                  </div>
                  <div style="display:flex;gap:6px;flex-wrap:wrap">
                    ${it.tipo === "Prazo" && !it.revisado && it.status === "Pendente" ? `<button type="button" class="btn sm tonal" data-confirm-it="${esc(it.id)}">Confirmar prazo</button>` : ""}
                    ${it.status === "Pendente" ? `<button type="button" class="btn sm ghost" data-complete-it="${esc(it.id)}">Concluir</button>` : ""}
                    <button type="button" class="btn sm ghost" data-edit-date="${esc(it.id)}">Alterar data</button>
                  </div>
                </div>`
                )
                .join("")
        }
      </section>

      <section class="box">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;gap:8px;flex-wrap:wrap">
          <h2 style="margin:0">Documentos (${caseDocs.length})</h2>
          <button type="button" class="btn sm tonal" id="btn-upload-doc">+ Enviar arquivo</button>
        </div>
        <div class="inline-upload-box">
          <div class="inline-upload-grid">
            <div>
              <label for="case-doc-type">Tipo</label>
              <input id="case-doc-type" type="text" list="doc-type-presets" placeholder="Petição, Contrato...">
            </div>
            <div>
              <label for="case-doc-file">Arquivo (máx. 25 MB)</label>
              <input id="case-doc-file" type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.odt,.png,.jpg,.jpeg,.txt">
            </div>
            <button type="button" class="btn sm" id="btn-inline-upload-doc">Enviar</button>
          </div>
          <datalist id="doc-type-presets">
            <option value="Petição"></option>
            <option value="Contrato"></option>
            <option value="Procuração"></option>
            <option value="Comprovante"></option>
            <option value="Sentença / Decisão"></option>
            <option value="Outro"></option>
          </datalist>
        </div>
        ${
          !caseDocs.length
            ? `<p class="mu">Nenhum documento anexado.</p>`
            : caseDocs.map((d) => renderDocumentRowMarkup(d)).join("")
        }
      </section>
    </div>

    <section class="box">
      <h2>Linha do tempo</h2>
      ${
        !state.timeline.length
          ? `<p class="mu">Sem eventos registrados.</p>`
          : `<div class="timeline">
              ${state.timeline
                .map(
                  (t) => `
                <div>
                  <div class="timeline-item-txt">${esc(t.txt)}</div>
                  <div class="timeline-item-meta">${esc(formatTimestampBr(t.createdAt, true))} ${t.autor ? "· " + esc(t.autor) : ""}</div>
                </div>`
                )
                .join("")}
            </div>`
      }
    </section>
  `;

  $("#btn-back-casos")?.addEventListener("click", () => navigate("casos"));
  $("#btn-case-dialog")?.addEventListener("click", () => openCaseDetailDialog(c.id));
  $("#btn-case-report")?.addEventListener("click", async () => {
    const btn = $("#btn-case-report");
    if (btn) btn.disabled = true;
    try {
      await downloadCaseReportPdf(c.id);
    } catch (err) {
      toast(err?.message || "Erro ao gerar relatório PDF.");
    } finally {
      if (btn) btn.disabled = false;
    }
  });

  $("#btn-inline-upload-doc")?.addEventListener("click", async () => {
    const fileInput = $("#case-doc-file");
    const typeInput = $("#case-doc-type");
    const btn = $("#btn-inline-upload-doc");
    const file = fileInput?.files?.[0];
    if (!file) {
      toast("Selecione um arquivo para enviar.");
      return;
    }
    btn.disabled = true;
    try {
      const ver = await repo.uploadDocument(
        state.user.uid,
        c.id,
        file,
        typeInput?.value,
        null,
        currentAuthorName()
      );
      if (fileInput) fileInput.value = "";
      if (typeInput) typeInput.value = "";
      toast(`Documento enviado (versão ${ver}).`);
    } catch (err) {
      toast(err?.message || "Erro ao enviar documento.");
    } finally {
      if (btn) btn.disabled = false;
    }
  });

  $("#btn-voice-memo")?.addEventListener("click", () => {
    openModal(
      "Nota de voz do caso",
      `<div class="field-label-row">
         <label for="vm-text">Transcrição / Memo</label>
         ${micButtonMarkup("btn-mic-vm", "Gravar áudio")}
       </div>
       <textarea id="vm-text" rows="4" placeholder="Clique em Gravar áudio para ditar sua nota ou digite aqui..."></textarea>
       <div class="mic-status" id="vm-mic-status"></div>
       <label for="vm-dest">Onde registrar?</label>
       <select id="vm-dest">
         <option value="timeline">Adicionar à Linha do tempo do caso</option>
         <option value="obs">Anexar às Observações do caso</option>
       </select>`,
      {
        confirmLabel: "Salvar nota",
        onConfirm: async (modal) => {
          const text = $("#vm-text", modal).value.trim();
          if (!text) throw new Error("Grave ou digite o texto da nota antes de salvar.");
          const dest = $("#vm-dest", modal).value;
          if (dest === "obs") {
            const combinedObs = [c.obs?.trim(), text].filter(Boolean).join("\n");
            await repo.updateCaseDetails(
              state.user.uid,
              c.id,
              c.titulo,
              c.numero || "",
              c.area || "",
              c.status,
              combinedObs,
              currentAuthorName()
            );
            toast("Nota de voz anexada às observações.");
          } else {
            await repo.appendTimeline(state.user.uid, c.id, `Nota de voz: ${text}`.slice(0, 500), currentAuthorName());
            await repo.appendAudit(state.user.uid, `Nota de voz registrada no caso '${c.titulo}'`, "cases", c.id, currentAuthorName());
            toast("Nota de voz registrada na linha do tempo.");
          }
        }
      }
    );
    attachSpeechRecognition($("#btn-mic-vm", dlg), $("#vm-text", dlg), $("#vm-mic-status", dlg), "Gravar áudio");
  });

  $("#btn-next-action")?.addEventListener("click", () => {
    openModal(
      "Próxima ação",
      `<div class="field-label-row">
         <label for="pa-txt">O que precisa ser feito?</label>
         ${micButtonMarkup("btn-mic-pa")}
       </div>
       <input id="pa-txt" type="text" value="${esc(c.proxAcao || "")}">
       <div class="mic-status" id="pa-mic-status"></div>
       <label for="pa-data">Data</label>
       <input id="pa-data" type="date" value="${esc(c.proxData || todayIso())}">`,
      {
        onConfirm: async (modal) => {
          await repo.updateCaseNextAction(
            state.user.uid,
            c.id,
            $("#pa-txt", modal).value,
            $("#pa-data", modal).value,
            currentAuthorName()
          );
          toast("Próxima ação atualizada.");
        }
      }
    );
    attachSpeechRecognition($("#btn-mic-pa", dlg), $("#pa-txt", dlg), $("#pa-mic-status", dlg));
  });

  $("#btn-edit-case")?.addEventListener("click", () => {
    openModal(
      "Editar caso",
      `<label for="ec-titulo">Título</label>
       <input id="ec-titulo" type="text" value="${esc(c.titulo)}">
       <label for="ec-num">Número</label>
       <input id="ec-num" type="text" value="${esc(c.numero || "")}">
       <label for="ec-area">Área</label>
       <input id="ec-area" type="text" value="${esc(c.area || "")}">
       <label for="ec-status">Status</label>
       <select id="ec-status">
         ${["Ativo", "Aguardando", "Encerrado"]
           .map((s) => `<option value="${s}" ${c.status === s ? "selected" : ""}>${s}</option>`)
           .join("")}
       </select>
       <div class="field-label-row">
         <label for="ec-obs">Observações / Nota de voz</label>
         ${micButtonMarkup("btn-mic-obs")}
       </div>
       <textarea id="ec-obs" rows="3">${esc(c.obs || "")}</textarea>
       <div class="mic-status" id="ec-mic-status"></div>`,
      {
        onConfirm: async (modal) => {
          await repo.updateCaseDetails(
            state.user.uid,
            c.id,
            $("#ec-titulo", modal).value,
            $("#ec-num", modal).value,
            $("#ec-area", modal).value,
            $("#ec-status", modal).value,
            $("#ec-obs", modal).value,
            currentAuthorName()
          );
          toast("Caso atualizado.");
        }
      }
    );
    attachSpeechRecognition($("#btn-mic-obs", dlg), $("#ec-obs", dlg), $("#ec-mic-status", dlg));
  });

  $("#btn-archive-case")?.addEventListener("click", () => {
    openModal("Arquivar caso?", `<p>Deseja arquivar <strong>${esc(c.titulo)}</strong>? Você poderá restaurá-lo depois.</p>`, {
      confirmLabel: "Arquivar",
      onConfirm: async () => {
        await repo.archiveCase(state.user.uid, c.id, c.titulo, false, currentAuthorName());
        toast("Caso arquivado.");
        navigate("casos");
      }
    });
  });

  $("#btn-restore-case")?.addEventListener("click", async () => {
    await repo.archiveCase(state.user.uid, c.id, c.titulo, true, currentAuthorName());
    toast("Caso restaurado.");
  });

  $("#btn-new-item")?.addEventListener("click", () => {
    openModal(
      "Novo item",
      `<label for="ni-tipo">Tipo</label>
       <select id="ni-tipo">
         <option value="Prazo">Prazo</option>
         <option value="Tarefa">Tarefa</option>
         <option value="Compromisso">Compromisso</option>
       </select>
       <label for="ni-titulo">Título</label>
       <input id="ni-titulo" type="text" placeholder="Ex.: Protocolar contestação">
       <label for="ni-data">Data</label>
       <input id="ni-data" type="date" value="${todayIso()}">
       <label for="ni-prio">Prioridade</label>
       <select id="ni-prio">
         <option value="Média">Média</option>
         <option value="Alta">Alta</option>
         <option value="Baixa">Baixa</option>
       </select>`,
      {
        onConfirm: async (modal) => {
          await repo.createItem(
            state.user.uid,
            c.id,
            $("#ni-tipo", modal).value,
            $("#ni-titulo", modal).value,
            $("#ni-data", modal).value,
            $("#ni-prio", modal).value,
            currentAuthorName()
          );
          toast("Item adicionado.");
        }
      }
    );
  });

  root.querySelectorAll("[data-confirm-it]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      const it = state.items.find((x) => x.id === btn.dataset.confirmIt);
      if (it) {
        await repo.confirmDeadline(state.user.uid, it, currentAuthorName());
        toast("Prazo confirmado por revisão humana.");
      }
    })
  );

  root.querySelectorAll("[data-complete-it]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      const it = state.items.find((x) => x.id === btn.dataset.completeIt);
      if (it) {
        await repo.updateItemStatus(state.user.uid, it, "Concluída", currentAuthorName());
        toast("Item concluído.");
      }
    })
  );

  root.querySelectorAll("[data-edit-date]").forEach((btn) =>
    btn.addEventListener("click", () => {
      const it = state.items.find((x) => x.id === btn.dataset.editDate);
      if (!it) return;
      openModal(
        "Alterar data",
        `<label for="ed-date">Nova data</label>
         <input id="ed-date" type="date" value="${esc(it.data)}">`,
        {
          onConfirm: async (modal) => {
            await repo.updateItemDate(state.user.uid, it, $("#ed-date", modal).value, currentAuthorName());
            toast("Data atualizada.");
          }
        }
      );
    })
  );

  $("#btn-upload-doc")?.addEventListener("click", () => openDocUploadModal(c.id, null));
  bindDocumentRowEvents(root);
}

function renderDocumentRowMarkup(d) {
  const docDate = formatTimestampBr(d.updatedAt || d.createdAt, true);
  return `
    <div class="doc-item-card">
      <div class="grow">
        <div><strong>${esc(d.nome)}</strong></div>
        <div class="doc-meta-line">
          <span class="pill neutral">${esc(d.tipo || "Outro")}</span>
          <span class="mu">v${esc(d.versao)} · ${esc(formatFileSize(d.size))} · ${esc(docDate)}</span>
        </div>
        <div class="mu" style="font-family:monospace;font-size:11px;margin-top:2px">SHA-256: ${esc(String(d.sha256 || "").slice(0, 16))}…</div>
      </div>
      <div style="display:flex;gap:6px;flex-wrap:wrap">
        <button type="button" class="btn sm ghost" data-dl-doc="${esc(d.id)}">Baixar</button>
        <button type="button" class="btn sm ghost" data-ver-doc="${esc(d.id)}">Versões</button>
        <button type="button" class="btn sm ghost" data-newver-doc="${esc(d.id)}">Nova versão</button>
        <button type="button" class="btn sm ghost" data-arch-doc="${esc(d.id)}">Arquivar</button>
      </div>
    </div>`;
}

function bindDocumentRowEvents(container, { returnToCaseId = null, onInlineNewVersion = null } = {}) {
  container.querySelectorAll("[data-newver-doc]").forEach((btn) =>
    btn.addEventListener("click", () => {
      const d = state.documents.find((x) => x.id === btn.dataset.newverDoc);
      if (!d) return;
      if (onInlineNewVersion) {
        onInlineNewVersion(d);
      } else {
        openDocUploadModal(d.caseId, d, returnToCaseId);
      }
    })
  );

  container.querySelectorAll("[data-dl-doc]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      const d = state.documents.find((x) => x.id === btn.dataset.dlDoc);
      if (!d) return;
      try {
        await repo.downloadDocumentVersion(state.user.uid, d, d.versao, currentAuthorName());
      } catch (e) {
        toast(e.message);
      }
    })
  );

  container.querySelectorAll("[data-ver-doc]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      const d = state.documents.find((x) => x.id === btn.dataset.verDoc);
      if (!d) return;
      const vers = await repo.getDocumentVersions(state.user.uid, d.id);
      openModal(
        `Versões – ${d.nome}`,
        vers
          .map(
            (v) => `
          <div class="row-item">
            <div class="grow">
              <div><strong>Versão ${esc(v.n)}</strong> (${esc(formatFileSize(v.size))})</div>
              <div class="mu">${esc(formatTimestampBr(v.createdAt, true))} · ${esc(v.porNome || "—")}</div>
              <div class="mu" style="font-family:monospace;font-size:11px">SHA-256: ${esc(String(v.sha256 || "").slice(0, 20))}…</div>
            </div>
            <button type="button" class="btn sm ghost" data-dl-ver="${esc(v.n)}">Baixar</button>
          </div>`
          )
          .join("") || `<p class="mu">Sem histórico de versões.</p>`,
        {
          showCancel: false,
          extraHtml: returnToCaseId
            ? `<button type="button" class="btn ghost" id="btn-back-to-case-dlg">← Voltar ao caso</button>`
            : ""
        }
      );
      if (returnToCaseId) {
        $("#btn-back-to-case-dlg", dlg)?.addEventListener("click", () => {
          openCaseDetailDialog(returnToCaseId);
        });
      }
      dlg.querySelectorAll("[data-dl-ver]").forEach((vBtn) =>
        vBtn.addEventListener("click", async () => {
          try {
            await repo.downloadDocumentVersion(state.user.uid, d, Number(vBtn.dataset.dlVer), currentAuthorName());
          } catch (e) {
            toast(e.message);
          }
        })
      );
    })
  );

  container.querySelectorAll("[data-arch-doc]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      await repo.archiveDocument(state.user.uid, btn.dataset.archDoc, currentAuthorName());
      toast("Documento arquivado.");
    })
  );
}

function renderCaseDialogDocsList(caseId) {
  const listEl = $("#dlg-docs-list", dlg);
  const countEl = $("#dlg-docs-count", dlg);
  if (!listEl) return;
  const caseDocs = state.documents.filter((d) => d.caseId === caseId && !d.archivedAt);
  if (countEl) countEl.textContent = String(caseDocs.length);
  listEl.innerHTML = !caseDocs.length
    ? `<p class="mu" style="margin:6px 0 0">Nenhum documento associado a este caso.</p>`
    : caseDocs.map((d) => renderDocumentRowMarkup(d)).join("");

  bindDocumentRowEvents(listEl, {
    returnToCaseId: caseId,
    onInlineNewVersion: (docObj) => {
      const verInput = $("#dlg-newver-file", dlg);
      if (!verInput) return;
      verInput.onchange = async () => {
        const file = verInput.files?.[0];
        verInput.value = "";
        if (!file) return;
        const statusEl = $("#dlg-doc-upload-status", dlg);
        if (statusEl) statusEl.textContent = `Enviando nova versão de "${docObj.nome}"...`;
        try {
          const ver = await repo.uploadDocument(
            state.user.uid,
            caseId,
            file,
            docObj.tipo,
            docObj,
            currentAuthorName()
          );
          if (statusEl) statusEl.textContent = `Versão ${ver} registrada no Firestore.`;
          toast(`Nova versão enviada (v${ver}).`);
          renderCaseDialogDocsList(caseId);
        } catch (err) {
          if (statusEl) statusEl.textContent = err?.message || "Erro ao enviar nova versão.";
          toast(err?.message || "Erro ao enviar nova versão.");
        }
      };
      verInput.click();
    }
  });
}

function refreshOpenCaseDetailDialog() {
  if (!dlg.open || !activeCaseDialogId) return;
  const c = state.cases.find((x) => x.id === activeCaseDialogId);
  if (!c) return;
  const obsDisplay = $("#dlg-case-obs-display", dlg);
  if (obsDisplay) obsDisplay.textContent = c.obs || "Sem observações.";
  renderCaseDialogDocsList(activeCaseDialogId);
}

function openCaseDetailDialog(caseId) {
  const c = state.cases.find((x) => x.id === caseId);
  if (!c) {
    toast("Caso não encontrado.");
    return;
  }
  const clientName = getClientNameMap().get(c.clientId) || "—";
  const pending = getPendingItemsByCase().get(c.id) || [];
  const nearest = pending[0] || null;
  const statusPillClass = c.status === "Aguardando" ? "amber" : c.status === "Encerrado" ? "neutral" : "";

  openModal(
    c.titulo,
    `<div class="dlg-header-meta">
       <span class="pill ${statusPillClass}">${esc(c.status)}</span>
       <span class="mu">👤 ${esc(clientName)}</span>
       ${c.area ? `<span class="mu">· ${esc(c.area)}</span>` : ""}
       ${c.numero ? `<span class="mu">· ⚖️ ${esc(c.numero)}</span>` : ""}
     </div>

     <div class="dlg-section" style="margin-top:0">
       <div style="display:grid;gap:6px">
         <div>
           <span class="mu">Próxima ação: </span>
           <strong>${esc(c.proxAcao || "Não definida")}</strong>
           ${c.proxData ? `<span class="mu"> (${esc(formatDateBr(c.proxData))})</span>` : ""}
         </div>
         ${
           nearest
             ? `<div><span class="mu">Próximo prazo/item: </span><strong>${esc(nearest.tipo)}: ${esc(nearest.titulo)}</strong> <span class="mu">(${esc(formatDateBr(nearest.data))})</span></div>`
             : ""
         }
         <div>
           <span class="mu">Observações: </span>
           <span id="dlg-case-obs-display">${esc(c.obs || "Sem observações.")}</span>
         </div>
       </div>
     </div>

     <div class="dlg-section">
       <div class="dlg-section-head">
         <h3>Nota de voz rápida</h3>
         ${micButtonMarkup("dlg-mic-memo", "Gravar áudio")}
       </div>
       <textarea id="dlg-vm-text" rows="2" placeholder="Use o microfone para ditar uma nota para este caso ou digite aqui..."></textarea>
       <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:6px;flex-wrap:wrap">
         <div class="mic-status" id="dlg-vm-status" style="margin:0;flex:1"></div>
         <button type="button" class="btn sm tonal" id="btn-dlg-save-memo">Salvar nota</button>
       </div>
     </div>

     <div class="dlg-section" id="dlg-docs-section">
       <div class="dlg-section-head">
         <h3>Documentos do caso (<span id="dlg-docs-count">0</span>)</h3>
         <span class="mu" style="font-size:12px">Metadados salvos no Firestore</span>
       </div>
       <div class="inline-upload-box">
         <div class="inline-upload-grid">
           <div>
             <label for="dlg-doc-type">Tipo</label>
             <input id="dlg-doc-type" type="text" list="dlg-doc-type-presets" placeholder="Petição, Contrato...">
           </div>
           <div>
             <label for="dlg-doc-file">Arquivo (máx. 25 MB)</label>
             <input id="dlg-doc-file" type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.odt,.png,.jpg,.jpeg,.txt">
           </div>
           <button type="button" class="btn sm" id="btn-dlg-upload-doc">Enviar</button>
         </div>
         <datalist id="dlg-doc-type-presets">
           <option value="Petição"></option>
           <option value="Contrato"></option>
           <option value="Procuração"></option>
           <option value="Comprovante"></option>
           <option value="Sentença / Decisão"></option>
           <option value="Outro"></option>
         </datalist>
         <input id="dlg-newver-file" type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.odt,.png,.jpg,.jpeg,.txt" style="display:none">
         <div class="mic-status" id="dlg-doc-upload-status"></div>
       </div>
       <div id="dlg-docs-list"></div>
     </div>`,
    {
      wide: true,
      showCancel: false,
      extraHtml: `
        <button type="button" class="btn tonal" id="btn-dlg-download-report">📄 Baixar relatório</button>
        <button type="button" class="btn ghost" id="btn-dlg-open-full">Abrir página completa</button>`
    }
  );

  activeCaseDialogId = caseId;
  attachSpeechRecognition($("#dlg-mic-memo", dlg), $("#dlg-vm-text", dlg), $("#dlg-vm-status", dlg), "Gravar áudio");
  renderCaseDialogDocsList(caseId);

  $("#btn-dlg-download-report", dlg)?.addEventListener("click", async () => {
    const btn = $("#btn-dlg-download-report", dlg);
    const errEl = $("#dlg-err", dlg);
    if (errEl) errEl.textContent = "";
    if (btn) btn.disabled = true;
    try {
      await downloadCaseReportPdf(caseId);
    } catch (err) {
      if (errEl) errEl.textContent = err?.message || "Erro ao gerar relatório PDF.";
      else toast(err?.message || "Erro ao gerar relatório PDF.");
    } finally {
      if (btn) btn.disabled = false;
    }
  });

  $("#btn-dlg-open-full", dlg)?.addEventListener("click", () => {
    dlg.close();
    navigate("casoDetail", caseId);
  });

  $("#btn-dlg-save-memo", dlg)?.addEventListener("click", async () => {
    const memoInput = $("#dlg-vm-text", dlg);
    const saveBtn = $("#btn-dlg-save-memo", dlg);
    const errEl = $("#dlg-err", dlg);
    const text = memoInput?.value.trim() || "";
    if (!text) {
      if (errEl) errEl.textContent = "Grave ou digite o texto da nota antes de salvar.";
      return;
    }
    if (errEl) errEl.textContent = "";
    saveBtn.disabled = true;
    try {
      const latest = state.cases.find((x) => x.id === caseId) || c;
      const combinedObs = [latest.obs?.trim(), text].filter(Boolean).join("\n");
      await repo.updateCaseDetails(
        state.user.uid,
        caseId,
        latest.titulo,
        latest.numero || "",
        latest.area || "",
        latest.status,
        combinedObs,
        currentAuthorName()
      );
      if (memoInput) memoInput.value = "";
      const statusEl = $("#dlg-vm-status", dlg);
      if (statusEl) statusEl.textContent = "Nota salva nas observações do caso.";
      toast("Nota de voz salva no caso.");
    } catch (err) {
      if (errEl) errEl.textContent = err?.message || "Erro ao salvar nota.";
    } finally {
      saveBtn.disabled = false;
    }
  });

  $("#btn-dlg-upload-doc", dlg)?.addEventListener("click", async () => {
    const fileEl = $("#dlg-doc-file", dlg);
    const typeEl = $("#dlg-doc-type", dlg);
    const btn = $("#btn-dlg-upload-doc", dlg);
    const statusEl = $("#dlg-doc-upload-status", dlg);
    const errEl = $("#dlg-err", dlg);
    const file = fileEl?.files?.[0];
    if (!file) {
      if (errEl) errEl.textContent = "Selecione um arquivo para enviar.";
      return;
    }
    if (errEl) errEl.textContent = "";
    btn.disabled = true;
    if (statusEl) statusEl.textContent = "Calculando SHA-256 e salvando metadados no Firestore...";
    try {
      const ver = await repo.uploadDocument(
        state.user.uid,
        caseId,
        file,
        typeEl?.value,
        null,
        currentAuthorName()
      );
      if (fileEl) fileEl.value = "";
      if (typeEl) typeEl.value = "";
      if (statusEl) statusEl.textContent = `Documento enviado e registrado no Firestore (v${ver}).`;
      toast(`Documento enviado (versão ${ver}).`);
      renderCaseDialogDocsList(caseId);
    } catch (err) {
      if (statusEl) statusEl.textContent = "";
      if (errEl) errEl.textContent = err?.message || "Erro ao enviar documento.";
    } finally {
      btn.disabled = false;
    }
  });
}

async function loadJsPdfCtor() {
  if (window.jspdf?.jsPDF) return window.jspdf.jsPDF;
  const mod = await import("https://esm.sh/jspdf@2.5.2");
  const ctor = mod.jsPDF || mod.default;
  if (!ctor) throw new Error("Biblioteca jsPDF indisponível.");
  return ctor;
}

function hexToRgbTuple(hex, fallback = [29, 47, 77]) {
  const clean = String(hex || "").trim().replace("#", "");
  if (!/^[0-9a-fA-F]{6}$/.test(clean)) return fallback;
  return [
    parseInt(clean.slice(0, 2), 16),
    parseInt(clean.slice(2, 4), 16),
    parseInt(clean.slice(4, 6), 16)
  ];
}

function pdfSafeText(val) {
  return String(val ?? "")
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/[\u201C\u201D]/g, '"')
    .replace(/\u2014/g, "--")
    .replace(/\u2013/g, "-")
    .replace(/\u2022/g, "*")
    .replace(/[^\x0A\x0D\x20-\xFF]/g, "")
    .trim();
}

async function downloadCaseReportPdf(caseId) {
  const c = state.cases.find((x) => x.id === caseId);
  if (!c) throw new Error("Caso não encontrado.");

  const jsPDF = await loadJsPdfCtor();
  const clientName = getClientNameMap().get(c.clientId) || "—";
  const caseDocs = state.documents.filter((d) => d.caseId === caseId && !d.archivedAt);
  const caseItems = state.items.filter((i) => i.caseId === caseId && !i.archivedAt);
  const timeline =
    timelineCaseId === caseId && state.timeline.length
      ? state.timeline
      : await repo.getCaseTimeline(state.user.uid, caseId);

  const doc = new jsPDF({ orientation: "portrait", unit: "mm", format: "a4" });
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const margin = 16;
  const contentWidth = pageWidth - margin * 2;

  const navy = hexToRgbTuple(state.branding?.azulEscuro, [29, 47, 77]);
  const blue = hexToRgbTuple(state.branding?.azul, [47, 92, 142]);
  const gold = hexToRgbTuple(state.branding?.dourado, [207, 167, 91]);
  const ink = [26, 34, 51];
  const muted = [93, 103, 120];
  const lineCol = [220, 225, 234];

  let y = 0;

  const ensureSpace = (neededMm) => {
    if (y + neededMm > pageHeight - 18) {
      doc.addPage();
      y = 18;
    }
  };

  const drawSectionHeading = (title) => {
    ensureSpace(14);
    doc.setFont("helvetica", "bold");
    doc.setFontSize(12);
    doc.setTextColor(...navy);
    doc.text(pdfSafeText(title), margin, y);
    y += 2;
    doc.setDrawColor(...gold);
    doc.setLineWidth(0.6);
    doc.line(margin, y, margin + 28, y);
    doc.setDrawColor(...lineCol);
    doc.setLineWidth(0.2);
    doc.line(margin + 28, y, pageWidth - margin, y);
    y += 6;
  };

  const drawKeyValue = (label, value) => {
    const safeVal = pdfSafeText(value || "—") || "—";
    const lines = doc.splitTextToSize(safeVal, contentWidth - 36);
    ensureSpace(lines.length * 5 + 3);
    doc.setFont("helvetica", "bold");
    doc.setFontSize(9.5);
    doc.setTextColor(...muted);
    doc.text(pdfSafeText(label), margin, y);
    doc.setFont("helvetica", "normal");
    doc.setTextColor(...ink);
    doc.text(lines, margin + 36, y);
    y += lines.length * 4.8 + 2.2;
  };

  // Header Banner
  doc.setFillColor(...navy);
  doc.rect(0, 0, pageWidth, 30, "F");
  doc.setFillColor(...gold);
  doc.rect(0, 30, pageWidth, 1.4, "F");

  doc.setFont("helvetica", "bold");
  doc.setFontSize(15);
  doc.setTextColor(255, 255, 255);
  doc.text(pdfSafeText(state.profile?.orgName || "Prajus - Gestão Jurídica"), margin, 13);

  doc.setFont("helvetica", "normal");
  doc.setFontSize(9.5);
  doc.setTextColor(225, 232, 244);
  doc.text(
    pdfSafeText(`Relatório do Caso  |  Gerado em ${new Date().toLocaleString("pt-BR")}  |  Por ${currentAuthorName()}`),
    margin,
    22
  );

  y = 42;

  // Case Title
  const titleLines = doc.splitTextToSize(pdfSafeText(c.titulo), contentWidth);
  doc.setFont("helvetica", "bold");
  doc.setFontSize(16);
  doc.setTextColor(...ink);
  doc.text(titleLines, margin, y);
  y += titleLines.length * 6.5 + 4;

  // Section 1: Case Information
  drawSectionHeading("Informações do Caso");
  drawKeyValue("Cliente:", clientName);
  drawKeyValue("Status:", c.status);
  drawKeyValue("Número:", c.numero || "Sem número");
  drawKeyValue("Área:", c.area || "Geral");
  drawKeyValue(
    "Próxima ação:",
    c.proxAcao ? `${c.proxAcao}${c.proxData ? " (" + formatDateBr(c.proxData) + ")" : ""}` : "Não definida"
  );
  drawKeyValue("Itens pendentes:", String(caseItems.filter((i) => i.status !== "Concluída").length));
  drawKeyValue("Observações:", c.obs || "Sem observações registradas.");
  y += 3;

  // Section 2: Documents List
  drawSectionHeading(`Documentos Associados (${caseDocs.length})`);
  if (!caseDocs.length) {
    ensureSpace(8);
    doc.setFont("helvetica", "italic");
    doc.setFontSize(9.5);
    doc.setTextColor(...muted);
    doc.text("Nenhum documento associado a este caso.", margin, y);
    y += 8;
  } else {
    caseDocs.forEach((d, idx) => {
      const title = `${idx + 1}. ${d.nome}`;
      const meta = `Tipo: ${d.tipo || "Outro"}  |  Versão: v${d.versao}  |  Tamanho: ${formatFileSize(d.size)}  |  Data: ${formatTimestampBr(d.updatedAt || d.createdAt, true)}`;
      const sha = d.sha256 ? `SHA-256: ${d.sha256}` : "";
      const titleWrapped = doc.splitTextToSize(pdfSafeText(title), contentWidth - 4);
      const metaWrapped = doc.splitTextToSize(pdfSafeText(meta), contentWidth - 4);
      const blockH = titleWrapped.length * 4.8 + metaWrapped.length * 4.4 + (sha ? 4.4 : 0) + 4;
      ensureSpace(blockH);

      doc.setFont("helvetica", "bold");
      doc.setFontSize(10);
      doc.setTextColor(...blue);
      doc.text(titleWrapped, margin + 2, y);
      y += titleWrapped.length * 4.8;

      doc.setFont("helvetica", "normal");
      doc.setFontSize(8.8);
      doc.setTextColor(...muted);
      doc.text(metaWrapped, margin + 2, y);
      y += metaWrapped.length * 4.4;

      if (sha) {
        doc.setFont("courier", "normal");
        doc.setFontSize(7.8);
        doc.text(pdfSafeText(sha), margin + 2, y);
        y += 4.2;
      }
      y += 2.5;
    });
  }
  y += 3;

  // Section 3: Timeline
  drawSectionHeading(`Linha do Tempo (${timeline.length})`);
  if (!timeline.length) {
    ensureSpace(8);
    doc.setFont("helvetica", "italic");
    doc.setFontSize(9.5);
    doc.setTextColor(...muted);
    doc.text("Sem eventos registrados na linha do tempo.", margin, y);
    y += 8;
  } else {
    timeline.forEach((t) => {
      const eventLines = doc.splitTextToSize(pdfSafeText(t.txt), contentWidth - 8);
      const metaLine = `${formatTimestampBr(t.createdAt, true)}${t.autor ? "  |  " + t.autor : ""}`;
      const needed = eventLines.length * 4.8 + 7;
      ensureSpace(needed);

      doc.setDrawColor(...gold);
      doc.setLineWidth(0.9);
      doc.line(margin + 1.5, y - 3, margin + 1.5, y + eventLines.length * 4.8 + 1);

      doc.setFont("helvetica", "normal");
      doc.setFontSize(9.5);
      doc.setTextColor(...ink);
      doc.text(eventLines, margin + 5, y);
      y += eventLines.length * 4.8;

      doc.setFont("helvetica", "normal");
      doc.setFontSize(8.3);
      doc.setTextColor(...muted);
      doc.text(pdfSafeText(metaLine), margin + 5, y);
      y += 6;
    });
  }

  // Page numbers
  const totalPages = doc.getNumberOfPages();
  for (let i = 1; i <= totalPages; i++) {
    doc.setPage(i);
    doc.setFont("helvetica", "normal");
    doc.setFontSize(8);
    doc.setTextColor(...muted);
    doc.text(
      pdfSafeText(`Prajus - Relatório confidencial  |  Página ${i} de ${totalPages}`),
      margin,
      pageHeight - 8
    );
  }

  const slug = pdfSafeText(c.titulo)
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 40) || "caso";
  doc.save(`relatorio-${slug}-${todayIso()}.pdf`);

  repo.appendAudit(state.user.uid, `Relatório PDF gerado para '${c.titulo}'`, "cases", c.id, currentAuthorName()).catch(() => {});
  toast("Relatório PDF baixado.");
}

function openDocUploadModal(caseId, existingDoc, returnToCaseId = null) {
  openModal(
    existingDoc ? `Nova versão – ${existingDoc.nome}` : "Enviar documento",
    `${!existingDoc ? `<label for="doc-tipo">Tipo</label><input id="doc-tipo" type="text" placeholder="Petição, Contrato, Procuração...">` : ""}
     <label for="doc-file">Arquivo (máx. 25 MB)</label>
     <input id="doc-file" type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.odt,.png,.jpg,.jpeg,.txt">`,
    {
      confirmLabel: "Enviar",
      extraHtml: returnToCaseId
        ? `<button type="button" class="btn ghost" id="btn-back-to-case-upload">← Voltar ao caso</button>`
        : "",
      onConfirm: async (modal) => {
        const file = $("#doc-file", modal).files?.[0];
        const tipo = existingDoc ? existingDoc.tipo : $("#doc-tipo", modal)?.value;
        const ver = await repo.uploadDocument(state.user.uid, caseId, file, tipo, existingDoc, currentAuthorName());
        toast(`Arquivo enviado (versão ${ver}).`);
        if (returnToCaseId) {
          setTimeout(() => openCaseDetailDialog(returnToCaseId), 0);
        }
      }
    }
  );
  if (returnToCaseId) {
    $("#btn-back-to-case-upload", dlg)?.addEventListener("click", () => {
      openCaseDetailDialog(returnToCaseId);
    });
  }
}

// ---------- View: Clientes ----------
function renderClientes(root) {
  let list = state.clients.filter((c) => (state.clientsShowArchived ? Boolean(c.archivedAt) : !c.archivedAt));
  const q = state.clientsQuery.trim().toLowerCase();
  if (q) {
    list = list.filter(
      (c) =>
        (c.nome || "").toLowerCase().includes(q) ||
        (c.doc || "").toLowerCase().includes(q) ||
        (c.email || "").toLowerCase().includes(q)
    );
  }

  root.innerHTML = `
    <div class="page-header">
      <div>
        <h1 class="accented">Clientes</h1>
        <p class="page-subtitle">Cadastro e histórico de clientes do escritório</p>
      </div>
      <button type="button" class="btn" id="btn-new-client">+ Novo cliente</button>
    </div>

    <input id="clients-search" type="search" placeholder="Buscar por nome, CPF/CNPJ ou e-mail..." value="${esc(state.clientsQuery)}">

    <div class="filter-bar">
      <button type="button" class="chip ${!state.clientsShowArchived ? "active" : ""}" id="cl-active">Ativos</button>
      <button type="button" class="chip ${state.clientsShowArchived ? "active" : ""}" id="cl-archived">Arquivados</button>
    </div>

    <section class="box">
      ${
        !list.length
          ? `<p class="mu">${state.clientsShowArchived ? "Nenhum cliente arquivado." : "Nenhum cliente cadastrado."}</p>`
          : list
              .map(
                (cl) => `
              <div class="row-item">
                <div class="grow">
                  <div><strong>${esc(cl.nome)}</strong></div>
                  <div class="mu">${[cl.doc, cl.email, cl.tel].filter(Boolean).map(esc).join(" · ") || "Sem detalhes adicionais"}</div>
                </div>
                <div style="display:flex;gap:6px;flex-wrap:wrap">
                  <button type="button" class="btn sm ghost" data-edit-cl="${esc(cl.id)}">Editar</button>
                  <button type="button" class="btn sm ghost" data-arch-cl="${esc(cl.id)}">${state.clientsShowArchived ? "Restaurar" : "Arquivar"}</button>
                </div>
              </div>`
              )
              .join("")
      }
    </section>
  `;

  const searchEl = $("#clients-search");
  searchEl?.addEventListener("input", (e) => {
    state.clientsQuery = e.target.value;
    const pos = e.target.selectionStart;
    render();
    const next = $("#clients-search");
    if (next) {
      next.focus();
      next.setSelectionRange(pos, pos);
    }
  });
  $("#cl-active")?.addEventListener("click", () => {
    state.clientsShowArchived = false;
    render();
  });
  $("#cl-archived")?.addEventListener("click", () => {
    state.clientsShowArchived = true;
    render();
  });
  $("#btn-new-client")?.addEventListener("click", () => openClientModal(null));
  root.querySelectorAll("[data-edit-cl]").forEach((btn) =>
    btn.addEventListener("click", () => {
      const cl = state.clients.find((x) => x.id === btn.dataset.editCl);
      if (cl) openClientModal(cl);
    })
  );
  root.querySelectorAll("[data-arch-cl]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      try {
        await repo.archiveClient(state.user.uid, btn.dataset.archCl, state.clientsShowArchived, currentAuthorName());
        toast(state.clientsShowArchived ? "Cliente restaurado." : "Cliente arquivado.");
      } catch (e) {
        toast(e.message);
      }
    })
  );
}

function openClientModal(client) {
  openModal(
    client ? "Editar cliente" : "Novo cliente",
    `<label for="cl-nome">Nome completo / Razão social</label>
     <input id="cl-nome" type="text" value="${esc(client?.nome || "")}">
     <label for="cl-doc">CPF / CNPJ</label>
     <input id="cl-doc" type="text" value="${esc(client?.doc || "")}">
     <label for="cl-email">E-mail</label>
     <input id="cl-email" type="email" value="${esc(client?.email || "")}">
     <label for="cl-tel">Telefone</label>
     <input id="cl-tel" type="text" value="${esc(client?.tel || "")}">`,
    {
      onConfirm: async (modal) => {
        const nome = $("#cl-nome", modal).value;
        const docVal = $("#cl-doc", modal).value;
        const email = $("#cl-email", modal).value;
        const tel = $("#cl-tel", modal).value;
        if (client) {
          await repo.updateClient(state.user.uid, client.id, nome, docVal, email, tel, currentAuthorName());
          toast("Cliente atualizado.");
        } else {
          await repo.createClient(state.user.uid, nome, docVal, email, tel, currentAuthorName());
          toast("Cliente cadastrado.");
        }
      }
    }
  );
}

// ---------- View: Equipe ----------
function renderEquipe(root) {
  root.innerHTML = `
    <div class="page-header">
      <div>
        <h1 class="accented">Equipe</h1>
        <p class="page-subtitle">Membros do escritório, permissões e convites</p>
      </div>
      <button type="button" class="btn" id="btn-new-invite">+ Convidar</button>
    </div>

    <div class="cols-2">
      <section class="box">
        <h2>Membros (${state.teamMembers.length})</h2>
        ${
          !state.teamMembers.length
            ? `<p class="mu">Nenhum membro registrado.</p>`
            : state.teamMembers
                .map(
                  (m) => `
                <div class="row-item">
                  <div class="grow">
                    <div><strong>${esc(m.nome)}</strong></div>
                    <div class="mu">${esc(m.email)}</div>
                  </div>
                  <select data-member-role="${esc(m.id)}" style="width:auto">
                    ${Object.entries(ROLE_LABELS)
                      .map(([k, lbl]) => `<option value="${k}" ${m.role === k ? "selected" : ""}>${esc(lbl)}</option>`)
                      .join("")}
                  </select>
                  <select data-member-status="${esc(m.id)}" style="width:auto">
                    <option value="ativo" ${m.status === "ativo" ? "selected" : ""}>Ativo</option>
                    <option value="inativo" ${m.status === "inativo" ? "selected" : ""}>Inativo</option>
                  </select>
                </div>`
                )
                .join("")
        }
      </section>

      <section class="box">
        <h2>Convites (${state.invites.length})</h2>
        ${
          !state.invites.length
            ? `<p class="mu">Nenhum convite enviado.</p>`
            : state.invites
                .map(
                  (inv) => `
                <div class="row-item">
                  <div class="grow">
                    <div><strong>${esc(inv.nome || inv.email)}</strong></div>
                    <div class="mu">${esc(inv.email)} · ${esc(ROLE_LABELS[inv.role] || inv.role)} · Validade ${esc(formatDateBr(inv.expiresAt))}</div>
                  </div>
                  <span class="pill ${inv.status === "pendente" ? "amber" : inv.status === "revogado" ? "danger" : ""}">${esc(inv.status)}</span>
                  ${
                    inv.status === "pendente"
                      ? `<button type="button" class="btn sm ghost" data-resend-inv="${esc(inv.id)}">Reenviar</button>
                         <button type="button" class="btn sm ghost" data-revoke-inv="${esc(inv.id)}">Revogar</button>`
                      : ""
                  }
                </div>`
                )
                .join("")
        }
      </section>
    </div>
  `;

  $("#btn-new-invite")?.addEventListener("click", () => {
    openModal(
      "Convidar colega",
      `<label for="inv-nome">Nome (opcional)</label>
       <input id="inv-nome" type="text">
       <label for="inv-email">E-mail</label>
       <input id="inv-email" type="email">
       <label for="inv-role">Perfil</label>
       <select id="inv-role">
         ${Object.entries(ROLE_LABELS)
           .map(([k, lbl]) => `<option value="${k}" ${k === "advogado" ? "selected" : ""}>${esc(lbl)}</option>`)
           .join("")}
       </select>`,
      {
        confirmLabel: "Registrar convite",
        onConfirm: async (modal) => {
          await repo.createInvite(
            state.user.uid,
            $("#inv-nome", modal).value,
            $("#inv-email", modal).value,
            $("#inv-role", modal).value,
            currentAuthorName()
          );
          toast("Convite registrado na nuvem.");
        }
      }
    );
  });

  const handleMemberChange = async (memberId) => {
    const roleEl = root.querySelector(`[data-member-role="${memberId}"]`);
    const statusEl = root.querySelector(`[data-member-status="${memberId}"]`);
    await repo.updateMember(state.user.uid, memberId, roleEl.value, statusEl.value, currentAuthorName());
    toast("Membro atualizado.");
  };

  root.querySelectorAll("[data-member-role]").forEach((sel) =>
    sel.addEventListener("change", () => handleMemberChange(sel.dataset.memberRole))
  );
  root.querySelectorAll("[data-member-status]").forEach((sel) =>
    sel.addEventListener("change", () => handleMemberChange(sel.dataset.memberStatus))
  );
  root.querySelectorAll("[data-resend-inv]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      await repo.resendInvite(state.user.uid, btn.dataset.resendInv, currentAuthorName());
      toast("Validade renovada por 7 dias.");
    })
  );
  root.querySelectorAll("[data-revoke-inv]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      await repo.revokeInvite(state.user.uid, btn.dataset.revokeInv, currentAuthorName());
      toast("Convite revogado.");
    })
  );
}

// ---------- View: Auditoria ----------
function renderAuditoria(root) {
  root.innerHTML = `
    <div class="page-header">
      <div>
        <h1 class="accented">Auditoria e backup</h1>
        <p class="page-subtitle">Histórico imutável de ações e exportação/importação JSON</p>
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button type="button" class="btn ghost" id="btn-export-json">Exportar JSON</button>
        <label class="btn" style="margin:0;cursor:pointer">
          Importar JSON
          <input type="file" id="input-import-json" accept=".json,application/json" style="display:none">
        </label>
      </div>
    </div>

    <section class="box">
      ${
        !state.audit.length
          ? `<p class="mu">Nenhum registro de auditoria ainda.</p>`
          : state.audit
              .map(
                (a) => `
              <div class="row-item">
                <div class="grow">
                  <div><strong>${esc(a.acao)}</strong></div>
                  <div class="mu">${esc(formatTimestampBr(a.createdAt, true))} ${a.usuario ? "· " + esc(a.usuario) : ""} ${a.entity ? "· " + esc(a.entity) : ""}</div>
                </div>
              </div>`
              )
              .join("")
      }
    </section>
  `;

  $("#btn-export-json")?.addEventListener("click", async () => {
    const jsonText = await repo.exportJson(
      state.user.uid,
      state.clients,
      state.cases,
      state.items,
      state.documents,
      currentAuthorName()
    );
    const blob = new Blob([jsonText], { type: "application/json;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `prajus-backup-${todayIso()}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
    toast("Backup JSON exportado.");
  });

  $("#input-import-json")?.addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const raw = await file.text();
      const res = await repo.importJson(state.user.uid, raw, currentAuthorName());
      toast(`Importado: ${res.clientsCount} clientes, ${res.casesCount} casos, ${res.itemsCount} itens.`);
    } catch (err) {
      toast(err.message);
    }
    e.target.value = "";
  });
}

// ---------- View: Aparência ----------
function renderAparencia(root) {
  const b = state.branding || DEFAULT_BRAND;
  root.innerHTML = `
    <div class="page-header">
      <div>
        <h1 class="accented">Aparência</h1>
        <p class="page-subtitle">Identidade visual sincronizada com o aplicativo via Firestore</p>
      </div>
      <button type="button" class="btn ghost" id="btn-reset-brand">Restaurar padrão</button>
    </div>

    <div class="cols-2">
      <section class="box">
        <h2>Paleta de cores</h2>
        <div class="swatch-row">
          <input type="color" id="col-nv" value="${esc(b.azulEscuro)}">
          <div class="grow">
            <strong>Azul escuro (navegação)</strong>
            <div class="mu">${esc(b.azulEscuro)}</div>
          </div>
        </div>
        <div class="swatch-row">
          <input type="color" id="col-bl" value="${esc(b.azul)}">
          <div class="grow">
            <strong>Azul principal (botões e links)</strong>
            <div class="mu">${esc(b.azul)}</div>
          </div>
        </div>
        <div class="swatch-row">
          <input type="color" id="col-go" value="${esc(b.dourado)}">
          <div class="grow">
            <strong>Dourado (destaques e linha do tempo)</strong>
            <div class="mu">${esc(b.dourado)}</div>
          </div>
        </div>
        <div style="margin-top:12px">
          <button type="button" class="btn" id="btn-save-colors">Salvar paleta na nuvem</button>
        </div>
      </section>

      <section class="box">
        <h2>Logotipos (armazenados neste navegador)</h2>
        ${["horizontal", "vertical", "icone"]
          .map(
            (slot) => `
          <div class="row-item">
            <img class="logo-preview" src="${esc(getLogoSrc(slot))}" alt="Logo ${slot}">
            <div class="grow">
              <strong style="text-transform:capitalize">${slot === "icone" ? "Ícone" : slot}</strong>
              <div class="mu">PNG, JPG ou SVG até 600 KB</div>
            </div>
            <label class="btn sm ghost" style="margin:0;cursor:pointer">
              Alterar
              <input type="file" data-logo-upload="${slot}" accept="image/*" style="display:none">
            </label>
            <button type="button" class="btn sm ghost" data-logo-reset="${slot}">Padrão</button>
          </div>`
          )
          .join("")}
      </section>
    </div>
  `;

  $("#btn-save-colors")?.addEventListener("click", async () => {
    try {
      await repo.saveBrandColors(
        state.user.uid,
        $("#col-nv").value,
        $("#col-bl").value,
        $("#col-go").value,
        currentAuthorName()
      );
      toast("Cores sincronizadas na nuvem.");
    } catch (e) {
      toast(e.message);
    }
  });

  $("#btn-reset-brand")?.addEventListener("click", async () => {
    clearAllLocalLogos(state.user.uid);
    await repo.resetBrandColors(state.user.uid, currentAuthorName());
    toast("Identidade visual padrão restaurada.");
    render();
  });

  root.querySelectorAll("[data-logo-upload]").forEach((inp) =>
    inp.addEventListener("change", (e) => {
      const file = e.target.files?.[0];
      if (!file) return;
      if (file.size > MAX_LOGO_BYTES) {
        toast("A imagem excede 600 KB.");
        return;
      }
      const reader = new FileReader();
      reader.onload = () => {
        try {
          setLocalLogo(state.user.uid, inp.dataset.logoUpload, String(reader.result));
          toast("Logotipo atualizado neste navegador.");
          render();
        } catch (err) {
          toast(err.message);
        }
      };
      reader.readAsDataURL(file);
    })
  );

  root.querySelectorAll("[data-logo-reset]").forEach((btn) =>
    btn.addEventListener("click", () => {
      setLocalLogo(state.user.uid, btn.dataset.logoReset, null);
      toast("Logotipo restaurado ao padrão.");
      render();
    })
  );
}

// ---------- Global Modals ----------
function openProfileModal(onboarding) {
  openModal(
    onboarding ? "Configurar escritório" : "Perfil do escritório",
    `<label for="prof-nome">Seu nome</label>
     <input id="prof-nome" type="text" value="${esc(state.profile?.nome || state.user?.displayName || "")}">
     <label for="prof-org">Nome do escritório</label>
     <input id="prof-org" type="text" value="${esc(state.profile?.orgName || "Meu Escritório")}">
     <label for="prof-oab">OAB (opcional)</label>
     <input id="prof-oab" type="text" value="${esc(state.profile?.oab || "")}" placeholder="OAB/SP 123.456">`,
    {
      showCancel: !onboarding,
      dismissible: !onboarding,
      onConfirm: async (modal) => {
        await repo.saveUserProfile(
          state.user.uid,
          $("#prof-nome", modal).value,
          state.user?.email || "",
          $("#prof-org", modal).value,
          $("#prof-oab", modal).value
        );
        toast("Perfil do escritório salvo.");
      }
    }
  );
}

function openNotificationsModal() {
  openModal(
    "Alertas de prazo",
    !state.notifications.length
      ? `<p class="mu">Nenhum alerta de prazo nos próximos dias.</p>`
      : state.notifications
          .map(
            (n) => `
          <div class="row-item">
            <div class="grow">
              <div style="${n.lida ? "" : "font-weight:700"}">${esc(n.txt)}</div>
              <div class="mu">${esc(formatTimestampBr(n.createdAt, true))}</div>
            </div>
            ${!n.lida ? `<button type="button" class="btn sm ghost" data-mark-notif="${esc(n.id)}">Marcar lida</button>` : ""}
          </div>`
          )
          .join(""),
    { showCancel: false }
  );

  dlg.querySelectorAll("[data-mark-notif]").forEach((btn) =>
    btn.addEventListener("click", async () => {
      await repo.markNotificationRead(state.user.uid, btn.dataset.markNotif);
      btn.remove();
    })
  );
}

function openGlobalSearchModal() {
  openModal(
    "Busca global",
    `<input id="gs-q" type="search" placeholder="Buscar casos, clientes, itens ou documentos...">
     <div id="gs-results" style="margin-top:10px"></div>`,
    { showCancel: false }
  );

  const qInput = $("#gs-q", dlg);
  const resEl = $("#gs-results", dlg);
  qInput?.focus();

  qInput?.addEventListener("input", () => {
    const clean = qInput.value.trim().toLowerCase();
    if (clean.length < 2) {
      resEl.innerHTML = `<p class="mu">Digite ao menos 2 caracteres.</p>`;
      return;
    }

    const activeCases = state.cases.filter((c) => !c.archivedAt);
    const caseSet = new Set(activeCases.map((c) => c.id));

    const casos = activeCases.filter(
      (c) =>
        (c.titulo || "").toLowerCase().includes(clean) ||
        (c.numero || "").toLowerCase().includes(clean) ||
        (c.area || "").toLowerCase().includes(clean)
    );
    const clientes = state.clients.filter(
      (cl) =>
        !cl.archivedAt &&
        ((cl.nome || "").toLowerCase().includes(clean) ||
          (cl.doc || "").toLowerCase().includes(clean) ||
          (cl.email || "").toLowerCase().includes(clean))
    );
    const itens = state.items.filter(
      (i) =>
        !i.archivedAt &&
        caseSet.has(i.caseId) &&
        ((i.titulo || "").toLowerCase().includes(clean) || (i.tipo || "").toLowerCase().includes(clean))
    );
    const docs = state.documents.filter(
      (d) =>
        !d.archivedAt &&
        caseSet.has(d.caseId) &&
        ((d.nome || "").toLowerCase().includes(clean) || (d.tipo || "").toLowerCase().includes(clean))
    );

    if (!casos.length && !clientes.length && !itens.length && !docs.length) {
      resEl.innerHTML = `<p class="mu">Nenhum resultado encontrado.</p>`;
      return;
    }

    resEl.innerHTML = `
      ${
        casos.length
          ? `<div class="search-group-title">Casos</div>` +
            casos
              .map(
                (c) => `<button type="button" class="search-result-btn" data-gs-case="${esc(c.id)}">
                          <strong>${esc(c.titulo)}</strong><div class="mu">${esc(c.numero || c.status)}</div>
                        </button>`
              )
              .join("")
          : ""
      }
      ${
        clientes.length
          ? `<div class="search-group-title">Clientes</div>` +
            clientes
              .map(
                (cl) => `<button type="button" class="search-result-btn" data-gs-clients="1">
                           <strong>${esc(cl.nome)}</strong><div class="mu">${esc(cl.email || cl.doc || "Cliente")}</div>
                         </button>`
              )
              .join("")
          : ""
      }
      ${
        itens.length
          ? `<div class="search-group-title">Itens</div>` +
            itens
              .map(
                (i) => `<button type="button" class="search-result-btn" data-gs-case="${esc(i.caseId)}">
                          <strong>${esc(i.titulo)}</strong><div class="mu">${esc(i.tipo)} · ${esc(formatDateBr(i.data))}</div>
                        </button>`
              )
              .join("")
          : ""
      }
      ${
        docs.length
          ? `<div class="search-group-title">Documentos</div>` +
            docs
              .map(
                (d) => `<button type="button" class="search-result-btn" data-gs-case="${esc(d.caseId)}">
                          <strong>${esc(d.nome)}</strong><div class="mu">${esc(d.tipo || "Documento")}</div>
                        </button>`
              )
              .join("")
          : ""
      }
    `;

    resEl.querySelectorAll("[data-gs-case]").forEach((btn) =>
      btn.addEventListener("click", () => {
        dlg.close();
        navigate("casoDetail", btn.dataset.gsCase);
      })
    );
    resEl.querySelectorAll("[data-gs-clients]").forEach((btn) =>
      btn.addEventListener("click", () => {
        dlg.close();
        navigate("clientes");
      })
    );
  });
}

// Keyboard shortcut Ctrl/Cmd + K for global search
window.addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k" && state.user) {
    e.preventDefault();
    openGlobalSearchModal();
  }
});
