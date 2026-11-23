// JSON API панели. Всё, что меняет базу, пишется в admin_log; открытые ключи нигде не сохраняются и в журнал не попадают.
import { newApiKey } from "../../books-api/src/access.js";

const DAY = 86_400_000;
const ORIGIN_RE = /^(\*\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\d{1,5})?$/i;
const SHA_RE = /^[0-9a-f]{64}$/;
const PKG_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const KEY_ID_RE = /^k_[0-9a-f]{12}$/;
const INSTALL_ID_RE = /^[0-9a-f]{32}$/;

class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
  }
}
const bad = (code) => new HttpError(400, code);
const missing = () => new HttpError(404, "not_found");

const all = async (db, sql, ...args) => (await db.prepare(sql).bind(...args).all()).results ?? [];
const one = (db, sql, ...args) => db.prepare(sql).bind(...args).first();
const run = (db, sql, ...args) => db.prepare(sql).bind(...args).run();

const day = (ms) => new Date(ms).toISOString().slice(0, 10);
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

async function log(env, actor, action, target, detail, now) {
  await run(env.DB, "INSERT INTO admin_log (at, actor, action, target, detail) VALUES (?, ?, ?, ?, ?)", now, actor, action, target ?? null, detail ? JSON.stringify(detail) : null);
}

function cleanOrigins(v) {
  if (v == null || (Array.isArray(v) && v.length === 0)) return null;
  if (!Array.isArray(v) || v.length > 20) throw bad("origins");
  const out = v.map((s) => String(s).trim().toLowerCase()).filter(Boolean);
  if (out.some((s) => !ORIGIN_RE.test(s))) throw bad("origins");
  return out.length ? JSON.stringify(out) : null;
}

function cleanText(v, max, required = false) {
  if (v == null || v === "") {
    if (required) throw bad("text");
    return null;
  }
  const s = String(v).trim();
  if (!s || s.length > max) throw bad("text");
  return s;
}

function cleanDays(v) {
  if (v == null || v === "" || v === 0) return null; // бессрочно
  const n = Number(v);
  if (!Number.isInteger(n) || n < 1 || n > 3650) throw bad("days");
  return n;
}

async function requireTier(env, name) {
  if (!(await one(env.DB, "SELECT name FROM tiers WHERE name = ?", String(name ?? "")))) throw bad("tier");
  return name;
}

async function requireKey(env, id) {
  if (!KEY_ID_RE.test(id)) throw bad("id");
  const k = await one(env.DB, "SELECT id, status, customer_id FROM api_keys WHERE id = ?", id);
  if (!k) throw missing();
  return k;
}

const publicKey = "SELECT k.id, k.key_prefix, k.tier, k.status, k.label, k.owner, k.customer_id, k.application_id, k.allowed_origins, k.created_at, k.expires_at, k.revoked_at, " +
  "c.username, c.telegram_id, a.app_name FROM api_keys k LEFT JOIN customers c ON c.id = k.customer_id LEFT JOIN applications a ON a.id = k.application_id";

// --- обзор ---------------------------------------------------------------------------------------------------------

async function overview(env, now) {
  const today = day(now);
  const since = day(now - 13 * DAY);
  const [keys, installs, usage, paid, apps, pending, top, recentInstalls, paidWeb] = await Promise.all([
    all(env.DB, "SELECT tier, status, COUNT(*) AS n FROM api_keys GROUP BY tier, status"),
    all(env.DB, "SELECT status, COUNT(*) AS n FROM installs GROUP BY status"),
    all(env.DB, "SELECT day, route, SUM(requests) AS n FROM usage_daily WHERE day >= ? GROUP BY day, route ORDER BY day", since),
    one(env.DB, "SELECT COUNT(*) AS orders, COALESCE(SUM(stars), 0) AS stars FROM orders WHERE status = 'paid' AND paid_at >= ?", now - 30 * DAY),
    all(env.DB, "SELECT status, COUNT(*) AS n FROM applications GROUP BY status"),
    one(env.DB, "SELECT COUNT(*) AS n FROM bot_outbox WHERE sent_at IS NULL"),
    all(env.DB, "SELECT u.key_id, k.label, k.tier, SUM(u.requests) AS n FROM usage_daily u LEFT JOIN api_keys k ON k.id = u.key_id WHERE u.day = ? GROUP BY u.key_id ORDER BY n DESC LIMIT 8", today),
    one(env.DB, "SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?", now - DAY),
    // Доход сайта в центах США; тестовые заказы (provider = mock) не считаются. Таблиц сайта может ещё не быть в базе.
    withoutWebTables(() => one(env.DB, "SELECT COUNT(*) AS orders, COALESCE(SUM(amount_minor), 0) AS usd_minor FROM web_orders WHERE status = 'paid' AND provider != 'mock' AND paid_at >= ?", now - 30 * DAY), { orders: 0, usd_minor: 0 }),
  ]);
  return { today, keys, installs, installs_24h: recentInstalls?.n ?? 0, usage, paid_30d: paid, paid_web_30d: paidWeb, applications: apps, outbox_pending: pending?.n ?? 0, top_keys_today: top };
}

/** Панель может быть выложена раньше, чем в базу применена schema/billing.sql: тогда «заказов сайта нет», а не 500. */
async function withoutWebTables(fn, fallback) {
  try {
    return await fn();
  } catch (e) {
    if (/no such table/i.test(String(e?.message))) return fallback;
    throw e;
  }
}

// --- ключи ---------------------------------------------------------------------------------------------------------

async function createKey(env, actor, body, now) {
  const tier = await requireTier(env, body.tier);
  const label = cleanText(body.label, 80, true);
  const owner = cleanText(body.owner, 80) ?? "partner:" + label;
  const origins = cleanOrigins(body.origins);
  const days = cleanDays(body.days);
  const key = await newApiKey(tier);
  await run(env.DB,
    "INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, label, owner, allowed_origins, created_at, expires_at) VALUES (?, ?, ?, ?, 'active', ?, ?, ?, ?, ?)",
    key.id, key.hash, key.prefix, tier, label, owner, origins, now, days ? now + days * DAY : null);
  await log(env, actor, "key.create", key.id, { tier, label, owner, origins, days }, now);
  return { id: key.id, key: key.plain, note: "Ключ показан один раз: в базе остаётся только его хэш." };
}

async function patchKey(env, actor, id, body, now) {
  await requireKey(env, id);
  const sets = [];
  const args = [];
  const changed = {};
  if ("tier" in body) { sets.push("tier = ?"); args.push(await requireTier(env, body.tier)); changed.tier = body.tier; }
  if ("label" in body) { sets.push("label = ?"); args.push(cleanText(body.label, 80)); changed.label = body.label; }
  if ("origins" in body) { sets.push("allowed_origins = ?"); args.push(cleanOrigins(body.origins)); changed.origins = body.origins; }
  if ("days" in body) { const d = cleanDays(body.days); sets.push("expires_at = ?"); args.push(d ? now + d * DAY : null); changed.days = d; }
  if (!sets.length) throw bad("nothing");
  await run(env.DB, `UPDATE api_keys SET ${sets.join(", ")} WHERE id = ?`, ...args, id);
  await log(env, actor, "key.update", id, changed, now);
  return { ok: true };
}

async function setKeyStatus(env, actor, id, status, now) {
  await requireKey(env, id);
  await run(env.DB, "UPDATE api_keys SET status = ?, revoked_at = ? WHERE id = ?", status, status === "revoked" ? now : null, id);
  await log(env, actor, status === "revoked" ? "key.revoke" : "key.restore", id, null, now);
  return { ok: true };
}

// --- заявки и покупатели -------------------------------------------------------------------------------------------

async function applications(env) {
  return all(env.DB,
    "SELECT a.id, a.app_name, a.description, a.kind, a.origins, a.commercial, a.expected_per_day, a.tier, a.status, a.terms_accepted_at, a.note, a.created_at, a.updated_at, " +
      "c.id AS customer_id, c.telegram_id, c.username, c.first_name, c.banned, " +
      "(SELECT COUNT(*) FROM api_keys k WHERE k.application_id = a.id AND k.status = 'active') AS active_keys, " +
      "(SELECT COALESCE(SUM(stars), 0) FROM orders o WHERE o.application_id = a.id AND o.status = 'paid') AS paid_stars " +
      "FROM applications a JOIN customers c ON c.id = a.customer_id ORDER BY a.created_at DESC LIMIT 500");
}

async function queue(env, customerId, text, now) {
  const c = await one(env.DB, "SELECT telegram_id FROM customers WHERE id = ?", customerId);
  if (!c) throw missing();
  await run(env.DB, "INSERT INTO bot_outbox (chat_id, text, created_at) VALUES (?, ?, ?)", c.telegram_id, text, now);
}

async function patchApplication(env, actor, id, body, now) {
  const app = await one(env.DB, "SELECT id FROM applications WHERE id = ?", id);
  if (!app) throw missing();
  await run(env.DB, "UPDATE applications SET note = ?, updated_at = ? WHERE id = ?", cleanText(body.note, 1000), now, id);
  await log(env, actor, "application.note", String(id), null, now);
  return { ok: true };
}

async function rejectApplication(env, actor, id, body, now) {
  const app = await one(env.DB, "SELECT id, customer_id, app_name FROM applications WHERE id = ?", id);
  if (!app) throw missing();
  const reason = cleanText(body.reason, 300, true);
  await run(env.DB, "UPDATE applications SET status = 'rejected', note = ?, updated_at = ? WHERE id = ?", reason, now, id);
  await run(env.DB, "UPDATE api_keys SET status = 'revoked', revoked_at = ? WHERE application_id = ? AND status = 'active'", now, id);
  await queue(env, app.customer_id,
    `❌ <b>${esc(app.app_name)}</b>: заявка отклонена. Причина: ${esc(reason)}\n❌ <b>${esc(app.app_name)}</b>: application rejected. Reason: ${esc(reason)}`, now);
  await log(env, actor, "application.reject", String(id), { reason }, now);
  return { ok: true, note: "Ключи заявки отозваны, покупатель уведомлён. Возврат Stars делается в боте командой /refund." };
}

async function setBan(env, actor, id, banned, now) {
  const c = await one(env.DB, "SELECT id FROM customers WHERE id = ?", id);
  if (!c) throw missing();
  await run(env.DB, "UPDATE customers SET banned = ? WHERE id = ?", banned ? 1 : 0, id);
  if (banned) await run(env.DB, "UPDATE api_keys SET status = 'revoked', revoked_at = ? WHERE customer_id = ? AND status = 'active'", now, id);
  await log(env, actor, banned ? "customer.ban" : "customer.unban", String(id), null, now);
  return { ok: true };
}

// --- заказы с сайта (tools/books-billing) --------------------------------------------------------------------------

function webOrders(env) {
  return withoutWebTables(() => all(env.DB,
    "SELECT o.id, o.checkout_ref, o.tier, o.kind, o.policies_accepted_at, o.provider, o.method, o.amount_minor, o.currency, o.status, o.note, o.email_failed, o.key_id, " +
      "o.project_name, o.project_type, o.project_description, o.origins, o.created_at, o.paid_at, " +
      "c.id AS customer_id, c.email, c.banned, " +
      "s.signer, s.terms_hash, s.refund_hash, s.privacy_hash, s.accepted_at, s.client_signed_at, (s.signature_svg IS NOT NULL) AS has_signature, k.key_prefix, k.status AS key_status, " +
      "(SELECT COALESCE(SUM(u.requests), 0) FROM usage_daily u WHERE u.key_id = o.key_id AND u.route != '_denied') AS requests_total " +
      "FROM web_orders o JOIN web_customers c ON c.id = o.customer_id LEFT JOIN contract_signatures s ON s.order_id = o.id LEFT JOIN api_keys k ON k.id = o.key_id " +
      "ORDER BY o.created_at DESC LIMIT 300"), []);
}

function toBase64(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

/** Подпись отдаётся только как data:-адрес картинки: <img> не исполняет скрипты, в отличие от inline-SVG в окне владельца. */
async function webSignature(env, orderId) {
  const s = await withoutWebTables(() => one(env.DB, "SELECT signature_svg FROM contract_signatures WHERE order_id = ?", orderId), null);
  if (!s) throw missing();
  return { src: "data:image/svg+xml;base64," + toBase64(s.signature_svg) };
}

/**
 * Ручной возврат: деньги возвращает провайдер (Polar / lava / шлюз) или владелец (USDT); здесь только состояние. Заказ → refunded, ключ отозван.
 * У заказа-продления ключ не трогаем (он оплачен и более ранним периодом): срок поправить вручную в «Ключах».
 */
async function refundWebOrder(env, actor, id, now) {
  const o = await withoutWebTables(() => one(env.DB, "SELECT id, kind, key_id, status FROM web_orders WHERE id = ?", id), null);
  if (!o) throw missing();
  if (o.status !== "paid") throw bad("status");
  const renewal = o.kind === "renew";
  await run(env.DB, "UPDATE web_orders SET status = 'refunded', note = ? WHERE id = ?", renewal ? "refunded_renewal_manual" : "refunded_manual", id);
  if (o.key_id && !renewal) await run(env.DB, "UPDATE api_keys SET status = 'revoked', revoked_at = ? WHERE id = ? AND status != 'revoked'", now, o.key_id);
  await log(env, actor, "web_order.refund", String(id), { renewal }, now);
  return { ok: true, note: renewal ? "Заказ отмечен возвращённым. Срок ключа поправьте вручную (минус 30 дней)." : "Заказ отмечен возвращённым, ключ отозван. Деньги возвращаются у провайдера." };
}

async function setWebBan(env, actor, id, banned, now) {
  const c = await withoutWebTables(() => one(env.DB, "SELECT id FROM web_customers WHERE id = ?", id), null);
  if (!c) throw missing();
  await run(env.DB, "UPDATE web_customers SET banned = ? WHERE id = ?", banned ? 1 : 0, id);
  // Как setBan для Telegram: бан отзывает активные ключи покупателя (owner web:<id> и ключи, выданные его заказами, в том числе тестовые).
  if (banned) {
    await run(env.DB, "UPDATE api_keys SET status = 'revoked', revoked_at = ? WHERE status = 'active' AND (owner = ? OR id IN (SELECT key_id FROM web_orders WHERE customer_id = ? AND key_id IS NOT NULL))", now, `web:${id}`, id);
  }
  await log(env, actor, banned ? "web_customer.ban" : "web_customer.unban", String(id), null, now);
  return { ok: true };
}

async function message(env, actor, body, now) {
  const text = cleanText(body.text, 1000, true);
  await queue(env, Number(body.customer_id), esc(text), now);
  await log(env, actor, "customer.message", String(body.customer_id), null, now);
  return { ok: true, note: "Сообщение в очереди: бот отправит его в течение минуты." };
}

// --- установки приложения и подписи --------------------------------------------------------------------------------

async function setInstallStatus(env, actor, id, status, now) {
  if (!INSTALL_ID_RE.test(id)) throw bad("id");
  if (!(await one(env.DB, "SELECT id FROM installs WHERE id = ?", id))) throw missing();
  await run(env.DB, "UPDATE installs SET status = ? WHERE id = ?", status, id);
  await log(env, actor, status === "revoked" ? "install.revoke" : "install.restore", id, null, now);
  return { ok: true };
}

async function addSignature(env, actor, body, now) {
  const pkg = String(body.package ?? "");
  const sha = String(body.sha256 ?? "").replace(/[:\s]/g, "").toLowerCase();
  if (!PKG_RE.test(pkg) || !SHA_RE.test(sha)) throw bad("signature");
  const label = cleanText(body.label, 40);
  await run(env.DB, "INSERT OR REPLACE INTO app_signatures (package, sha256, label, enabled) VALUES (?, ?, ?, 1)", pkg, sha, label);
  await log(env, actor, "signature.add", pkg, { sha256: sha, label }, now);
  return { ok: true };
}

async function toggleSignature(env, actor, body, now) {
  const pkg = String(body.package ?? "");
  const sha = String(body.sha256 ?? "").toLowerCase();
  if (!PKG_RE.test(pkg) || !SHA_RE.test(sha)) throw bad("signature");
  const enabled = body.enabled ? 1 : 0;
  await run(env.DB, "UPDATE app_signatures SET enabled = ? WHERE package = ? AND sha256 = ?", enabled, pkg, sha);
  await log(env, actor, enabled ? "signature.enable" : "signature.disable", pkg, { sha256: sha }, now);
  return { ok: true };
}

// --- тарифы --------------------------------------------------------------------------------------------------------

// price_usd_minor: центы США за period_days для продажи на сайте (tools/books-billing); 0 = не продавать.
const TIER_INTS = { per_second: 0, per_minute: 0, per_day: 0, max_limit: 1, max_ids: 1, max_keys: 0, price_stars: 0, price_usd_minor: 0, period_days: 1, sort: 0 };
const TIER_FLAGS = ["allow_covers", "allow_index", "sold"];

async function patchTier(env, actor, name, body, now) {
  await requireTier(env, name);
  const sets = [];
  const args = [];
  for (const [f, min] of Object.entries(TIER_INTS)) {
    if (!(f in body)) continue;
    const n = Number(body[f]);
    if (!Number.isInteger(n) || n < min || n > 100_000_000) throw bad(f);
    sets.push(`${f} = ?`);
    args.push(n);
  }
  for (const f of TIER_FLAGS) {
    if (!(f in body)) continue;
    sets.push(`${f} = ?`);
    args.push(body[f] ? 1 : 0);
  }
  if ("title" in body) { sets.push("title = ?"); args.push(cleanText(body.title, 40, true)); }
  if (!sets.length) throw bad("nothing");
  await run(env.DB, `UPDATE tiers SET ${sets.join(", ")} WHERE name = ?`, ...args, name);
  await log(env, actor, "tier.update", name, body, now);
  return { ok: true, note: "API подхватит новые лимиты в течение минуты (кэш тарифа 60 с)." };
}

// --- маршрутизация -------------------------------------------------------------------------------------------------

/** Возвращает [status, data]. Исключения HttpError превращаются в ответ с кодом, остальные — в 500 без подробностей. */
export async function handleApi(request, env, actor, now = Date.now()) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "");
  const m = request.method;
  const body = async () => {
    try {
      const b = await request.json();
      if (!b || typeof b !== "object" || Array.isArray(b)) throw 0;
      return b;
    } catch {
      throw bad("json");
    }
  };
  try {
    let seg;
    if (m === "GET") {
      if (path === "/api/overview") return [200, await overview(env, now)];
      if (path === "/api/keys") return [200, { keys: await all(env.DB, `${publicKey} ORDER BY k.created_at DESC LIMIT 500`) }];
      if ((seg = path.match(/^\/api\/keys\/(k_[0-9a-f]{12})\/usage$/))) {
        return [200, { usage: await all(env.DB, "SELECT day, route, requests FROM usage_daily WHERE key_id = ? AND day >= ? ORDER BY day DESC, route", seg[1], day(now - 29 * DAY)) }];
      }
      if (path === "/api/applications") return [200, { applications: await applications(env) }];
      if (path === "/api/customers") return [200, { customers: await all(env.DB, "SELECT id, telegram_id, username, first_name, language, banned, created_at, last_seen_at FROM customers ORDER BY created_at DESC LIMIT 500") }];
      if (path === "/api/installs") return [200, { installs: await all(env.DB, "SELECT id, package, signature, security_level, boot_state, os_patch, first_seen, last_seen, tokens_issued, status FROM installs ORDER BY last_seen DESC LIMIT 500") }];
      if (path === "/api/signatures") return [200, { signatures: await all(env.DB, "SELECT package, sha256, label, enabled FROM app_signatures ORDER BY package, label") }];
      if (path === "/api/tiers") return [200, { tiers: await all(env.DB, "SELECT * FROM tiers ORDER BY sort, name") }];
      if (path === "/api/orders") return [200, { orders: await all(env.DB, "SELECT o.id, o.application_id, a.app_name, o.tier, o.kind, o.stars, o.status, o.key_id, o.created_at, o.paid_at FROM orders o JOIN applications a ON a.id = o.application_id ORDER BY o.created_at DESC LIMIT 300") }];
      if (path === "/api/web-orders") return [200, { orders: await webOrders(env) }];
      if ((seg = path.match(/^\/api\/web-orders\/(\d+)\/signature$/))) return [200, await webSignature(env, Number(seg[1]))];
      if (path === "/api/log") return [200, { log: await all(env.DB, "SELECT id, at, actor, action, target, detail FROM admin_log ORDER BY id DESC LIMIT 200") }];
      return [404, { error: "not_found" }];
    }
    if (m === "POST" || m === "PATCH" || m === "PUT") {
      if (path === "/api/keys") return [200, await createKey(env, actor, await body(), now)];
      if ((seg = path.match(/^\/api\/keys\/([^/]+)$/)) && m === "PATCH") return [200, await patchKey(env, actor, seg[1], await body(), now)];
      if ((seg = path.match(/^\/api\/keys\/([^/]+)\/(revoke|restore)$/))) return [200, await setKeyStatus(env, actor, seg[1], seg[2] === "revoke" ? "revoked" : "active", now)];
      if ((seg = path.match(/^\/api\/applications\/(\d+)$/)) && m === "PATCH") return [200, await patchApplication(env, actor, Number(seg[1]), await body(), now)];
      if ((seg = path.match(/^\/api\/applications\/(\d+)\/reject$/))) return [200, await rejectApplication(env, actor, Number(seg[1]), await body(), now)];
      if ((seg = path.match(/^\/api\/customers\/(\d+)\/(ban|unban)$/))) return [200, await setBan(env, actor, Number(seg[1]), seg[2] === "ban", now)];
      if ((seg = path.match(/^\/api\/web-orders\/(\d+)\/refund$/))) return [200, await refundWebOrder(env, actor, Number(seg[1]), now)];
      if ((seg = path.match(/^\/api\/web-customers\/(\d+)\/(ban|unban)$/))) return [200, await setWebBan(env, actor, Number(seg[1]), seg[2] === "ban", now)];
      if (path === "/api/message") return [200, await message(env, actor, await body(), now)];
      if ((seg = path.match(/^\/api\/installs\/([^/]+)\/(revoke|restore)$/))) return [200, await setInstallStatus(env, actor, seg[1], seg[2] === "revoke" ? "revoked" : "active", now)];
      if (path === "/api/signatures" && m === "POST") return [200, await addSignature(env, actor, await body(), now)];
      if (path === "/api/signatures/toggle") return [200, await toggleSignature(env, actor, await body(), now)];
      if ((seg = path.match(/^\/api\/tiers\/([a-z]+)$/)) && m === "PATCH") return [200, await patchTier(env, actor, seg[1], await body(), now)];
      return [404, { error: "not_found" }];
    }
    return [405, { error: "method_not_allowed" }];
  } catch (e) {
    if (e instanceof HttpError) return [e.status, { error: e.message }];
    console.error("admin api failure", e?.message);
    return [500, { error: "internal" }];
  }
}
