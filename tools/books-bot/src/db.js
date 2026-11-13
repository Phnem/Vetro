// Слой работы с D1 (vetro-books): customers, applications, orders, api_keys, tiers, usage_daily, bot_sessions.

const SESSIONS_DDL =
  "CREATE TABLE IF NOT EXISTS bot_sessions (" +
  "telegram_id INTEGER PRIMARY KEY, " +
  "lang TEXT NOT NULL DEFAULT 'en', " +
  "step TEXT, " +
  "draft TEXT, " +
  "updated_at INTEGER NOT NULL)";

const initializedDbs = new WeakSet();

export async function ensureSessionsTable(db) {
  if (initializedDbs.has(db)) return;
  await db.prepare(SESSIONS_DDL).run();
  initializedDbs.add(db);
}

export async function upsertCustomer(db, from, now = Date.now()) {
  await db
    .prepare(
      "INSERT INTO customers (telegram_id, username, first_name, language, created_at, last_seen_at) " +
        "VALUES (?1, ?2, ?3, ?4, ?5, ?5) " +
        "ON CONFLICT(telegram_id) DO UPDATE SET " +
        "username = excluded.username, " +
        "first_name = excluded.first_name, " +
        "language = COALESCE(customers.language, excluded.language), " +
        "last_seen_at = excluded.last_seen_at",
    )
    .bind(from.id, from.username ?? null, from.first_name ?? null, from.language_code ?? null, now)
    .run();
  return db.prepare("SELECT * FROM customers WHERE telegram_id = ?1").bind(from.id).first();
}

export async function getCustomerByTelegramId(db, telegramId) {
  return db.prepare("SELECT * FROM customers WHERE telegram_id = ?1").bind(telegramId).first();
}

export async function getCustomerById(db, id) {
  return db.prepare("SELECT * FROM customers WHERE id = ?1").bind(id).first();
}

export async function setCustomerLanguage(db, telegramId, lang) {
  await db.prepare("UPDATE customers SET language = ?1 WHERE telegram_id = ?2").bind(lang, telegramId).run();
}

export async function getSession(db, telegramId) {
  await ensureSessionsTable(db);
  const row = await db.prepare("SELECT * FROM bot_sessions WHERE telegram_id = ?1").bind(telegramId).first();
  if (!row) return null;
  let draft = {};
  if (row.draft) {
    try {
      draft = JSON.parse(row.draft);
    } catch {
      draft = {};
    }
  }
  return { telegram_id: row.telegram_id, lang: row.lang, step: row.step, draft, updated_at: row.updated_at };
}

export async function saveSession(db, telegramId, { lang, step = null, draft = null }, now = Date.now()) {
  await ensureSessionsTable(db);
  const draftJson = draft ? JSON.stringify(draft) : null;
  await db
    .prepare(
      "INSERT INTO bot_sessions (telegram_id, lang, step, draft, updated_at) VALUES (?1, ?2, ?3, ?4, ?5) " +
        "ON CONFLICT(telegram_id) DO UPDATE SET lang = excluded.lang, step = excluded.step, draft = excluded.draft, updated_at = excluded.updated_at",
    )
    .bind(telegramId, lang, step, draftJson, now)
    .run();
}

export async function clearWizardSession(db, telegramId, lang, now = Date.now()) {
  return saveSession(db, telegramId, { lang, step: null, draft: null }, now);
}

export async function getSoldTiers(db) {
  const res = await db.prepare("SELECT * FROM tiers WHERE sold = 1 ORDER BY sort").all();
  return res?.results ?? [];
}

export async function getTier(db, name) {
  return db.prepare("SELECT * FROM tiers WHERE name = ?1").bind(name).first();
}

export async function countActiveKeys(db, customerId, now = Date.now()) {
  const row = await db
    .prepare(
      "SELECT COUNT(*) AS cnt FROM api_keys WHERE customer_id = ?1 AND status = 'active' AND (expires_at IS NULL OR expires_at > ?2)",
    )
    .bind(customerId, now)
    .first();
  return Number(row?.cnt ?? 0);
}

export async function listCustomerKeys(db, customerId) {
  const res = await db
    .prepare(
      "SELECT k.*, t.title AS tier_title, t.per_day AS tier_per_day, t.per_minute AS tier_per_minute, t.per_second AS tier_per_second " +
        "FROM api_keys k LEFT JOIN tiers t ON t.name = k.tier " +
        "WHERE k.customer_id = ?1 " +
        "ORDER BY CASE WHEN k.status = 'active' THEN 0 ELSE 1 END, k.created_at DESC",
    )
    .bind(customerId)
    .all();
  return res?.results ?? [];
}

export async function getCustomerKey(db, keyId, customerId) {
  return db
    .prepare(
      "SELECT k.*, t.title AS tier_title, t.sort AS tier_sort, t.period_days AS tier_period_days " +
        "FROM api_keys k LEFT JOIN tiers t ON t.name = k.tier " +
        "WHERE k.id = ?1 AND k.customer_id = ?2",
    )
    .bind(keyId, customerId)
    .first();
}

export async function createApplication(
  db,
  { customerId, appName, description, kind, origins, commercial, expectedPerDay, tier, status = "new", termsAcceptedAt },
  now = Date.now(),
) {
  const res = await db
    .prepare(
      "INSERT INTO applications " +
        "(customer_id, app_name, description, kind, origins, commercial, expected_per_day, tier, status, terms_accepted_at, created_at, updated_at) " +
        "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?11)",
    )
    .bind(
      customerId,
      appName,
      description,
      kind,
      origins ?? null,
      commercial ? 1 : 0,
      expectedPerDay ?? null,
      tier,
      status,
      termsAcceptedAt ?? now,
      now,
    )
    .run();
  const id = res?.meta?.last_row_id;
  if (id != null) {
    return db.prepare("SELECT * FROM applications WHERE id = ?1").bind(id).first();
  }
  return db
    .prepare("SELECT * FROM applications WHERE customer_id = ?1 ORDER BY id DESC LIMIT 1")
    .bind(customerId)
    .first();
}

export async function setApplicationStatus(db, applicationId, status, now = Date.now()) {
  await db
    .prepare("UPDATE applications SET status = ?1, updated_at = ?2 WHERE id = ?3")
    .bind(status, now, applicationId)
    .run();
}

export async function getApplication(db, applicationId) {
  return db.prepare("SELECT * FROM applications WHERE id = ?1").bind(applicationId).first();
}

export async function createOrder(
  db,
  { applicationId, customerId, tier, kind = "new", stars, currency = "XTR", invoicePayload, keyId = null },
  now = Date.now(),
) {
  const res = await db
    .prepare(
      "INSERT INTO orders (application_id, customer_id, tier, kind, stars, currency, invoice_payload, status, key_id, created_at) " +
        "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'pending', ?8, ?9)",
    )
    .bind(applicationId, customerId, tier, kind, stars, currency, invoicePayload, keyId, now)
    .run();
  const id = res?.meta?.last_row_id;
  if (id != null) {
    return db.prepare("SELECT * FROM orders WHERE id = ?1").bind(id).first();
  }
  return db.prepare("SELECT * FROM orders WHERE invoice_payload = ?1").bind(invoicePayload).first();
}

export async function getOrderByPayload(db, invoicePayload) {
  return db.prepare("SELECT * FROM orders WHERE invoice_payload = ?1").bind(invoicePayload).first();
}

export async function getOrderByChargeId(db, chargeId) {
  return db.prepare("SELECT * FROM orders WHERE charge_id = ?1").bind(chargeId).first();
}

export async function getOrderByIdOrCharge(db, idOrCharge) {
  const asNum = /^\d+$/.test(String(idOrCharge)) ? Number(idOrCharge) : null;
  if (asNum !== null) {
    const byId = await db.prepare("SELECT * FROM orders WHERE id = ?1").bind(asNum).first();
    if (byId) return byId;
  }
  return getOrderByChargeId(db, String(idOrCharge));
}

export async function claimPaidOrder(db, invoicePayload, chargeId, now = Date.now()) {
  const res = await db
    .prepare(
      "UPDATE orders SET charge_id = ?1, status = 'paid', paid_at = ?2 " +
        "WHERE invoice_payload = ?3 AND status = 'pending' AND charge_id IS NULL",
    )
    .bind(chargeId, now, invoicePayload)
    .run();
  return Number(res?.meta?.changes ?? 0) === 1;
}

export async function setOrderKeyId(db, orderId, keyId) {
  await db.prepare("UPDATE orders SET key_id = ?1 WHERE id = ?2").bind(keyId, orderId).run();
}

export async function markOrderFailed(db, orderId) {
  await db.prepare("UPDATE orders SET status = 'failed' WHERE id = ?1 AND status = 'pending'").bind(orderId).run();
}

export async function markOrderRefunded(db, orderId, now = Date.now()) {
  const order = await db.prepare("SELECT * FROM orders WHERE id = ?1").bind(orderId).first();
  if (!order) return null;
  await db.prepare("UPDATE orders SET status = 'refunded' WHERE id = ?1").bind(orderId).run();
  if (order.key_id) {
    await db
      .prepare("UPDATE api_keys SET status = 'revoked', revoked_at = ?1 WHERE id = ?2 AND status != 'revoked'")
      .bind(now, order.key_id)
      .run();
  }
  if (order.application_id) {
    await setApplicationStatus(db, order.application_id, "refunded", now);
  }
  return order;
}

export async function insertApiKey(
  db,
  { id, hash, prefix, tier, label, owner, customerId, applicationId, allowedOrigins, createdAt, expiresAt },
) {
  await db
    .prepare(
      "INSERT INTO api_keys " +
        "(id, key_hash, key_prefix, tier, status, label, owner, customer_id, application_id, allowed_origins, created_at, expires_at) " +
        "VALUES (?1, ?2, ?3, ?4, 'active', ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
    )
    .bind(
      id,
      hash,
      prefix,
      tier,
      label ?? null,
      owner,
      customerId,
      applicationId ?? null,
      allowedOrigins ?? null,
      createdAt,
      expiresAt ?? null,
    )
    .run();
}

export async function revokeApiKey(db, keyId, customerId, now = Date.now()) {
  const res = await db
    .prepare(
      "UPDATE api_keys SET status = 'revoked', revoked_at = ?1 " +
        "WHERE id = ?2 AND customer_id = ?3 AND status = 'active'",
    )
    .bind(now, keyId, customerId)
    .run();
  return Number(res?.meta?.changes ?? 0) === 1;
}

export async function updateApiKeyExpiry(db, keyId, customerId, expiresAt) {
  await db
    .prepare("UPDATE api_keys SET expires_at = ?1 WHERE id = ?2 AND customer_id = ?3 AND status = 'active'")
    .bind(expiresAt, keyId, customerId)
    .run();
}

export async function upgradeApiKey(db, keyId, customerId, tier, expiresAt) {
  await db
    .prepare("UPDATE api_keys SET tier = ?1, expires_at = ?2 WHERE id = ?3 AND customer_id = ?4 AND status = 'active'")
    .bind(tier, expiresAt, keyId, customerId)
    .run();
}

export async function getUsageForKeys(db, keyIds, monthStartUtc) {
  if (!keyIds.length) return [];
  const placeholders = keyIds.map((_, i) => `?${i + 2}`).join(", ");
  const res = await db
    .prepare(
      `SELECT key_id, day, route, requests FROM usage_daily ` +
        `WHERE day >= ?1 AND key_id IN (${placeholders}) ORDER BY day DESC`,
    )
    .bind(monthStartUtc, ...keyIds)
    .all();
  return res?.results ?? [];
}

export async function getPendingOutbox(db, limit = 20) {
  const res = await db
    .prepare("SELECT id, chat_id, text, attempts FROM bot_outbox WHERE sent_at IS NULL ORDER BY id ASC LIMIT ?1")
    .bind(limit)
    .all();
  return res?.results ?? [];
}

export async function markOutboxSent(db, id, now = Date.now()) {
  await db.prepare("UPDATE bot_outbox SET sent_at = ?1, error = NULL WHERE id = ?2").bind(now, id).run();
}

export async function markOutboxFailed(db, id, errorMsg, { permanent = false, now = Date.now() } = {}) {
  const truncated = String(errorMsg ?? "unknown error").slice(0, 500);
  await db
    .prepare(
      "UPDATE bot_outbox SET " +
        "attempts = attempts + 1, " +
        "error = ?1, " +
        "sent_at = CASE WHEN ?2 = 1 OR attempts + 1 >= 5 THEN ?3 ELSE NULL END " +
        "WHERE id = ?4",
    )
    .bind(truncated, permanent ? 1 : 0, now, id)
    .run();
}
