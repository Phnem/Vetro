// Обработка событий оплаты: общая функция для вебхука и страницы mock. Порядок как у бота (tools/books-bot/src/bot.js, handleSuccessfulPayment):
//  (1) условия приняты и подпись есть, иначе ключ не выдаётся; (2) атомарно pending → paid; (3) ключ → api_keys → web_orders.key_id;
//  (4) открытый ключ только в REVEAL (TTL 600 с) и в письме; (5) подтверждение только по вебхуку провайдера, не по возврату браузера;
//  (6) возврат/спор → ключ revoked, заказ refunded.
import { isTest } from "./http.js";
import { newApiKey } from "./keys.js";
import { composeKeyEmail, composeRenewalEmail, sendKeyEmail } from "./mail.js";

const DAY = 86_400_000;
export const REVEAL_TTL = 600;
export const PENDING_TTL_MS = DAY; // pending старше суток становятся expired

const one = (db, sql, ...args) => db.prepare(sql).bind(...args).first();
const run = (db, sql, ...args) => db.prepare(sql).bind(...args).run();
const changes = (res) => Number(res?.meta?.changes ?? 0);

export const revealKey = (checkoutRef) => `reveal:${checkoutRef}`;

/**
 * Применить событие провайдера. Результат: { result } — issued | duplicate | rejected | unknown_order | refunded | ignored.
 * deps: { now, waitUntil(promise), fetchImpl, sleep } — всё необязательно.
 */
export async function processEvent(env, providerName, event, deps = {}) {
  if (!event || typeof event.checkoutRef !== "string") return { result: "unknown_order" };
  if (event.type === "paid") return fulfilPaid(env, providerName, event, deps);
  if (event.type === "refunded") return refundOrder(env, providerName, event, deps);
  if (event.type === "failed") return failPending(env, providerName, event);
  return { result: "ignored" };
}

async function reject(db, order, event, note) {
  // provider_ref сохраняем, чтобы владелец нашёл платёж у провайдера и вернул деньги; если он уже занят, пишем без него.
  try {
    await run(db, "UPDATE web_orders SET status = 'failed', note = ?1, provider_ref = COALESCE(provider_ref, ?2) WHERE id = ?3 AND status IN ('pending', 'expired')", note, event.providerRef ?? null, order.id);
  } catch {
    await run(db, "UPDATE web_orders SET status = 'failed', note = ?1 WHERE id = ?2 AND status IN ('pending', 'expired')", note, order.id);
  }
  return { result: "rejected", note };
}

async function fulfilPaid(env, providerName, event, deps) {
  const db = env.DB;
  const now = deps.now ?? Date.now();
  if (typeof event.providerRef !== "string" || !event.providerRef) return { result: "ignored" };

  const order = await one(db, "SELECT * FROM web_orders WHERE checkout_ref = ?1", event.checkoutRef);
  if (!order || order.provider !== providerName) return { result: "unknown_order" };

  // Повтор доставки. Если прошлая попытка оплатила заказ, но упала до выдачи ключа — доводим выдачу (тот же providerRef).
  if (order.status === "paid") return resume(env, order, event, deps, now);
  if (order.status !== "pending" && order.status !== "expired") return { result: "duplicate" };

  // Сверка с заказом: сумма и валюта хранятся на сервере и берутся из tiers при создании заказа, от клиента не приходят.
  if (event.amountMinor !== order.amount_minor) return reject(db, order, event, "mismatch_amount");
  if (event.currency !== order.currency) return reject(db, order, event, "mismatch_currency");
  if (event.tier !== order.tier) return reject(db, order, event, "mismatch_tier");
  if (order.kind !== "new" && order.kind !== "renew") return reject(db, order, event, "unsupported_kind");
  if (order.kind === "renew") {
    // Ключ, который продлевается, мог быть отозван (нарушение, бан) уже после checkout: тогда продлевать нечего.
    const target = await one(db, "SELECT status FROM api_keys WHERE id = ?1", order.renew_key_id);
    if (!target || target.status !== "active") return reject(db, order, event, "renew_target_revoked");
  }

  const customer = await one(db, "SELECT * FROM web_customers WHERE id = ?1", order.customer_id);
  if (!customer || customer.banned === 1) return reject(db, order, event, "banned");
  const contract = await one(db, "SELECT * FROM contract_signatures WHERE order_id = ?1", order.id);
  if (!contract || !contract.signature_svg || !contract.signer || !contract.terms_hash) return reject(db, order, event, "no_contract");
  const tier = await one(db, "SELECT * FROM tiers WHERE name = ?1", order.tier);
  if (!tier) return reject(db, order, event, "tier_gone");

  // Атомарно: ровно одна доставка получает changes = 1. pending и expired: деньги пришли — поздний платёж тоже оплата.
  let claim;
  try {
    claim = await run(
      db,
      "UPDATE web_orders SET status = 'paid', provider_ref = ?1, paid_at = ?2 WHERE checkout_ref = ?3 AND status IN ('pending', 'expired') AND provider_ref IS NULL",
      event.providerRef,
      now,
      event.checkoutRef,
    );
  } catch {
    // UNIQUE(provider_ref): тот же платёж провайдера уже привязан к другому заказу. Ключ не выдаём, владелец разбирается руками.
    return reject(db, order, event, "duplicate_payment_ref");
  }
  if (changes(claim) !== 1) {
    const fresh = await one(db, "SELECT * FROM web_orders WHERE id = ?1", order.id);
    return fresh && fresh.status === "paid" ? resume(env, fresh, event, deps, now) : { result: "duplicate" };
  }

  const paid = { ...order, status: "paid", provider_ref: event.providerRef, paid_at: now };
  return issue(env, paid, tier, customer, contract, deps, now);
}

async function resume(env, order, event, deps, now) {
  if (order.key_id || order.provider_ref !== event.providerRef) return { result: "duplicate" };
  const [customer, contract, tier] = await Promise.all([
    one(env.DB, "SELECT * FROM web_customers WHERE id = ?1", order.customer_id),
    one(env.DB, "SELECT * FROM contract_signatures WHERE order_id = ?1", order.id),
    one(env.DB, "SELECT * FROM tiers WHERE name = ?1", order.tier),
  ]);
  if (!customer || !contract || !tier) return { result: "duplicate" };
  return issue(env, order, tier, customer, contract, deps, now);
}

/** Продление: expires_at = max(now, expires_at) + период, считается в SQL (два продления подряд не затирают друг друга). Новый ключ не создаётся. */
async function renew(env, order, tier, customer, contract, deps, now) {
  const db = env.DB;
  const period = isTest(env) ? DAY : tier.period_days * DAY;
  const [ext, link] = await db.batch([
    db
      .prepare(
        "UPDATE api_keys SET expires_at = MAX(?1, COALESCE(expires_at, ?1)) + ?2 WHERE id = ?3 AND status = 'active' " +
          "AND EXISTS (SELECT 1 FROM web_orders WHERE id = ?4 AND status = 'paid' AND key_id IS NULL AND renew_key_id = ?3)",
      )
      .bind(now, period, order.renew_key_id, order.id),
    db
      .prepare(
        "UPDATE web_orders SET key_id = renew_key_id WHERE id = ?1 AND status = 'paid' AND key_id IS NULL " +
          "AND EXISTS (SELECT 1 FROM api_keys WHERE id = web_orders.renew_key_id AND status = 'active')",
      )
      .bind(order.id),
  ]);
  if (changes(ext) !== 1 || changes(link) !== 1) return { result: "duplicate" };
  const key = await one(db, "SELECT key_prefix, expires_at FROM api_keys WHERE id = ?1", order.renew_key_id);
  const task = deliverRenewal(env, order, tier, customer, contract, key, deps);
  if (deps.waitUntil) deps.waitUntil(task);
  else await task;
  return { result: "renewed", keyId: order.renew_key_id };
}

async function deliverRenewal(env, order, tier, customer, contract, key, deps) {
  try {
    const mail = composeRenewalEmail({ lang: contract.lang ?? customer.language ?? "en", signer: contract.signer, plan: tier.title ?? tier.name, expiresAt: key.expires_at, prefix: key.key_prefix });
    const status = await sendKeyEmail(env, { to: customer.email, ...mail }, { fetchImpl: deps.fetchImpl, sleep: deps.sleep });
    if (status === "failed") await run(env.DB, "UPDATE web_orders SET email_failed = 1 WHERE id = ?1", order.id);
  } catch {
    await run(env.DB, "UPDATE web_orders SET email_failed = 1 WHERE id = ?1", order.id).catch(() => {});
  }
}

async function issue(env, order, tier, customer, contract, deps, now) {
  if (order.kind === "renew") return renew(env, order, tier, customer, contract, deps, now);
  const db = env.DB;
  const test = isTest(env);
  const key = await newApiKey(tier.name);
  const paidAt = order.paid_at ?? now;
  const expiresAt = test ? now + DAY : paidAt + tier.period_days * DAY; // ключи тестового режима живут сутки
  const label = ((test ? "TEST " : "") + order.project_name).slice(0, 80);
  const owner = test ? "web:test" : `web:${order.customer_id}`;
  const origins = order.project_type === "site" ? order.origins : null;

  // Вставка условная: второй параллельный выдающий увидит key_id и не вставит ничего. Обе записи — одна транзакция D1.
  const [ins, link] = await db.batch([
    db
      .prepare(
        "INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, label, owner, customer_id, application_id, allowed_origins, created_at, expires_at) " +
          "SELECT ?1, ?2, ?3, ?4, 'active', ?5, ?6, NULL, NULL, ?7, ?8, ?9 " +
          "WHERE EXISTS (SELECT 1 FROM web_orders WHERE id = ?10 AND status = 'paid' AND key_id IS NULL)",
      )
      .bind(key.id, key.hash, key.prefix, tier.name, label, owner, origins, now, expiresAt, order.id),
    db.prepare("UPDATE web_orders SET key_id = ?1 WHERE id = ?2 AND status = 'paid' AND key_id IS NULL").bind(key.id, order.id),
  ]);
  if (changes(ins) !== 1 || changes(link) !== 1) return { result: "duplicate" };

  // Открытый ключ: только на 10 минут для страницы успеха и в письмо. В D1, логах, admin_log и очередях его нет.
  try {
    await env.REVEAL?.put(revealKey(order.checkout_ref), key.plain, { expirationTtl: REVEAL_TTL });
  } catch {
    // KV недоступен: ключ всё равно уйдёт письмом, страница покажет «придёт на e-mail».
  }

  const task = deliver(env, order, tier, customer, contract, key.plain, expiresAt, deps);
  if (deps.waitUntil) deps.waitUntil(task);
  else await task;
  return { result: "issued", keyId: key.id };
}

async function deliver(env, order, tier, customer, contract, plain, expiresAt, deps) {
  try {
    const mail = composeKeyEmail({
      lang: contract.lang ?? customer.language ?? "en",
      signer: contract.signer,
      plan: tier.title ?? tier.name,
      expiresAt,
      key: plain,
      domains: order.project_type === "site" && order.origins ? JSON.parse(order.origins) : [],
    });
    const status = await sendKeyEmail(env, { to: customer.email, ...mail }, { fetchImpl: deps.fetchImpl, sleep: deps.sleep });
    if (status === "failed") await run(env.DB, "UPDATE web_orders SET email_failed = 1 WHERE id = ?1", order.id);
  } catch {
    await run(env.DB, "UPDATE web_orders SET email_failed = 1 WHERE id = ?1", order.id).catch(() => {});
  }
}

async function refundOrder(env, providerName, event, deps) {
  const db = env.DB;
  const now = deps.now ?? Date.now();
  const order = await one(db, "SELECT * FROM web_orders WHERE checkout_ref = ?1", event.checkoutRef);
  if (!order || order.provider !== providerName) return { result: "unknown_order" };
  if (order.status !== "paid") return { result: order.status === "refunded" ? "duplicate" : "ignored" };
  // Как markOrderRefunded у бота: заказ → refunded, ключ → revoked. Пометка для владельца — в note.
  await run(db, "UPDATE web_orders SET status = 'refunded', note = ?1 WHERE id = ?2 AND status = 'paid'", order.kind === "renew" ? "refunded_renewal" : "refunded", order.id);
  if (order.key_id && order.kind === "renew") {
    // Возврат продления забирает только оплаченные им дни; если срок после этого вышел, ключ отзывается.
    const tier = await one(db, "SELECT period_days FROM tiers WHERE name = ?1", order.tier);
    const period = isTest(env) ? DAY : (tier?.period_days ?? 30) * DAY;
    await run(db, "UPDATE api_keys SET expires_at = expires_at - ?1 WHERE id = ?2 AND expires_at IS NOT NULL", period, order.key_id);
    await run(db, "UPDATE api_keys SET status = 'revoked', revoked_at = ?1 WHERE id = ?2 AND status = 'active' AND expires_at <= ?1", now, order.key_id);
  } else if (order.key_id) {
    await run(db, "UPDATE api_keys SET status = 'revoked', revoked_at = ?1 WHERE id = ?2 AND status != 'revoked'", now, order.key_id);
  }
  try {
    await env.REVEAL?.delete(revealKey(order.checkout_ref));
  } catch {
    // ключ и так истечёт через TTL
  }
  return { result: "refunded" };
}

async function failPending(env, providerName, event) {
  const order = await one(env.DB, "SELECT id, provider FROM web_orders WHERE checkout_ref = ?1", event.checkoutRef);
  if (!order || order.provider !== providerName) return { result: "unknown_order" };
  await run(env.DB, "UPDATE web_orders SET status = 'failed', note = 'provider_failed' WHERE id = ?1 AND status = 'pending'", order.id);
  return { result: "ignored" };
}

/** cron: заказы pending старше суток → expired. Возвращает число закрытых заказов. */
export async function expirePending(env, now = Date.now()) {
  const res = await run(env.DB, "UPDATE web_orders SET status = 'expired' WHERE status = 'pending' AND created_at < ?1", now - PENDING_TTL_MS);
  return changes(res);
}
