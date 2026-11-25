const DB_NAME = "prajus_web_storage";
const DB_VERSION = 1;
const FILE_STORE = "document_files";

function openDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(FILE_STORE)) {
        db.createObjectStore(FILE_STORE);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function saveDocumentBlob(uid, docId, version, fileName, mimeType, buffer) {
  const key = `${uid}:${docId}:v${version}`;
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(FILE_STORE, "readwrite");
    tx.objectStore(FILE_STORE).put({ fileName, mimeType, buffer }, key);
    tx.oncomplete = () => resolve(key);
    tx.onerror = () => reject(tx.error);
  });
}

export async function loadDocumentBlob(uid, docId, version) {
  const key = `${uid}:${docId}:v${version}`;
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(FILE_STORE, "readonly");
    const req = tx.objectStore(FILE_STORE).get(key);
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  });
}

export async function sha256Hex(arrayBuffer) {
  const digest = await crypto.subtle.digest("SHA-256", arrayBuffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

const logoKey = (uid, slot) => `prajus_logo_${uid}_${slot}`;

export function getLocalLogos(uid) {
  if (!uid) return { horizontal: null, vertical: null, icone: null };
  try {
    return {
      horizontal: localStorage.getItem(logoKey(uid, "horizontal")) || null,
      vertical: localStorage.getItem(logoKey(uid, "vertical")) || null,
      icone: localStorage.getItem(logoKey(uid, "icone")) || null
    };
  } catch {
    return { horizontal: null, vertical: null, icone: null };
  }
}

export function setLocalLogo(uid, slot, dataUrl) {
  if (!uid) return;
  try {
    if (dataUrl) localStorage.setItem(logoKey(uid, slot), dataUrl);
    else localStorage.removeItem(logoKey(uid, slot));
  } catch {
    throw new Error("Espaço insuficiente no navegador para salvar o logotipo.");
  }
}

export function clearAllLocalLogos(uid) {
  ["horizontal", "vertical", "icone"].forEach((slot) => setLocalLogo(uid, slot, null));
}
