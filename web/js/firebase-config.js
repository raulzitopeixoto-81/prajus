export const FIRESTORE_DATABASE_ID = "ai-studio-android-prajus-5302c165-bb41-4c18-b6a6-4316843afc42";

const STORAGE_APP_ID_KEY = "prajus_web_firebase_app_id";

export function getStoredWebAppId() {
  try {
    return localStorage.getItem(STORAGE_APP_ID_KEY)?.trim() || "";
  } catch {
    return "";
  }
}

export function setStoredWebAppId(appId) {
  try {
    const clean = (appId || "").trim();
    if (clean) localStorage.setItem(STORAGE_APP_ID_KEY, clean);
    else localStorage.removeItem(STORAGE_APP_ID_KEY);
  } catch {
    // ignore storage restriction
  }
}

export function getFirebaseWebConfig() {
  // Opcional: cole aqui o appId obtido em Configuracoes do Projeto -> Seus aplicativos -> Web no Console Firebase
  const configuredAppId = "";
  const appId = getStoredWebAppId() || configuredAppId;

  const base = {
    apiKey: "AIzaSyDJhTViJM6YDjS78z1YvdosekqdHpbMbFg",
    authDomain: "gen-lang-client-0019377953.firebaseapp.com",
    projectId: "gen-lang-client-0019377953",
    storageBucket: "gen-lang-client-0019377953.firebasestorage.app",
    messagingSenderId: "745649861358"
  };

  if (appId) {
    base.appId = appId;
  }
  return base;
}
