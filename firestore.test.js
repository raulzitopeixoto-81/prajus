const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any user-scoped collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/clients`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/cases`).get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/clients`).doc("c1").set({
      userId: ALICE_UID,
      nome: "Cliente Fake",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Authenticated owner: valid Kotlin payloads for profile, clients, cases, items, documents, timeline, branding", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  // User profile
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}`).set({
      userId: ALICE_UID,
      nome: "Dra. Helena",
      email: "helena@prajus.com.br",
      orgName: "Silva Advocacia",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Client
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/clients/cli_1`).set({
      userId: ALICE_UID,
      nome: "Marina Souza",
      email: "marina@exemplo.com",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Case referencing existing client
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/cases/case_1`).set({
      userId: ALICE_UID,
      clientId: "cli_1",
      titulo: "Ação de Indenização",
      status: "Ativo",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Case referencing missing client fails
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/cases/case_orphan`).set({
      userId: ALICE_UID,
      clientId: "missing_cli",
      titulo: "Orphan Case",
      status: "Ativo",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Partial update on case (proxAcao, proxData)
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/cases/case_1`).update({
      proxAcao: "Protocolar réplica",
      proxData: "2026-12-01",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / Shadow update
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/cases/case_1`).update({
      isVerified: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Item (Prazo)
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/items/item_1`).set({
      userId: ALICE_UID,
      caseId: "case_1",
      tipo: "Prazo",
      titulo: "Réplica",
      data: "2026-12-01",
      status: "Pendente",
      prio: "Alta",
      revisado: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Confirm deadline partial update
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/items/item_1`).update({
      revisado: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/items/item_1`).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Document & Version
  const fakeSha = "a".repeat(64);
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/documents/doc_1`).set({
      userId: ALICE_UID,
      caseId: "case_1",
      nome: "Contrato.pdf",
      tipo: "Contrato",
      versao: 1,
      sha256: fakeSha,
      size: 1024,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/documents/doc_1/versions/v1`).set({
      userId: ALICE_UID,
      docId: "doc_1",
      n: 1,
      sha256: fakeSha,
      size: 1024,
      porNome: "Dra. Helena",
      createdAt: ts,
    })
  );

  // Document update (new version)
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/documents/doc_1`).update({
      versao: 2,
      sha256: "b".repeat(64),
      size: 2048,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Timeline (append-only)
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/timeline/tl_1`).set({
      userId: ALICE_UID,
      caseId: "case_1",
      txt: "Caso criado",
      autor: "Dra. Helena",
      createdAt: ts,
    })
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/timeline/tl_1`).update({ txt: "Edited" })
  );

  // Notification create & mark read
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/notifications/notif_1`).set({
      userId: ALICE_UID,
      txt: "Prazo próximo",
      notifKey: "prazo:item_1:2026-12-01",
      lida: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/notifications/notif_1`).update({
      lida: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Team member & invite
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/team_members/tm_1`).set({
      userId: ALICE_UID,
      nome: "Dra. Helena",
      email: "helena@prajus.com.br",
      role: "admin",
      status: "ativo",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/team_members/tm_1`).update({
      role: "advogado",
      status: "ativo",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/invites/inv_1`).set({
      userId: ALICE_UID,
      nome: "Carlos",
      email: "carlos@prajus.com.br",
      role: "advogado",
      status: "pendente",
      expiresAt: "2026-12-05T12:00:00Z",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/invites/inv_1`).update({
      status: "revogado",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Audit (append-only)
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/audit/aud_1`).set({
      userId: ALICE_UID,
      acao: "Criou caso",
      entity: "cases",
      entityRef: "case_1",
      usuario: "Dra. Helena",
      createdAt: ts,
    })
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/audit/aud_1`).update({ acao: "Tampered" })
  );

  // Branding
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/branding/config`).set({
      userId: ALICE_UID,
      azulEscuro: "#1D2F4D",
      azul: "#2F5C8E",
      dourado: "#CFA75B",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/branding/config`).update({
      azul: "not-a-hex",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // List queries succeed on owner's collections (even empty)
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/cases`).get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/clients`).get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/items`).get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/notifications`).get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/team_members`).get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/invites`).get());
});

test("Cross-user isolation: Bob cannot read or write Alice's workspace", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    const ts = new Date(Date.now() - 5000);
    await adminDb.doc(`users/${ALICE_UID}/clients/cli_a`).set({
      userId: ALICE_UID,
      nome: "Cliente Alice",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.doc(`users/${ALICE_UID}/clients/cli_a`).get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/clients`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/clients/cli_a`).update({
      nome: "Hacked",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/clients/cli_b`).set({
      userId: BOB_UID,
      nome: "Spoof",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Future timestamp tampering and @DocumentId 'id' injection are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const future = new Date(Date.now() + 60000);
  const past = new Date(Date.now() - 5000);

  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/clients/cli_future`).set({
      userId: ALICE_UID,
      nome: "Future Client",
      createdAt: future,
      updatedAt: future,
    })
  );

  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/clients/cli_with_id`).set({
      id: "cli_with_id",
      userId: ALICE_UID,
      nome: "With ID",
      createdAt: past,
      updatedAt: past,
    })
  );
});
