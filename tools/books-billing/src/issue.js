// Обработка событий оплаты: общая функция для вебхука и страницы mock. Порядок как у бота (tools/books-bot/src/bot.js, handleSuccessfulPayment):
//  (1) условия приняты и подпись есть, иначе ключ не выдаётся; (2) атомарно pending → paid; (3) ключ → api_keys → web_orders.key_id;
//  (4) открытый ключ только в REVEAL (TTL 600 с) и в письме; (5) подтверждение только по вебхуку провайдера, не по возврату браузера;
//  (6) возврат/спор → ключ revoked, заказ refunded.
// Модели: пакет lava.top / USDT (one_time, days дней, без автопродления) и подписка Polar (subscription, +1 месяц за каждое подтверждённое продление).
// Доступ везде один: ключ работает, пока status = active и expires_at > now. Лимиты посуточные и от срока не зависят.
import { isTest } from "./http.js";
import { newApiKey } from "./keys.js";
import { composeKeyEmail, composeRenewalEmail, fmtLimits, limitsShort, sendKeyEmail } from "./mail.js";
import { providerByName } from "./providers/index.js";

const DAY = 86_400_000;
export const REVEAL_TTL = 600;
export const PENDING_TTL_MS = DAY; // pending старше суток становятся expired
/** Запас после конца оплаченного периода подписки: продление приходит вебхуком не в ту же секунду, ключ не должен гаснуть на это время. */
export const SUB_GRACE_MS = DAY;

const one = (db, sql, ...args) => db.prepare(sql).bind(...args).first();
const run = (db, sql, ...args) => db.prepare(sql).bind(...args).run();
const changes = (res) => Number(res?.meta?.changes ?? 0);

export const revealKey = (checkoutRef) => `reveal:${checkoutRef}`;

/**
 * Применить событие провайдера: paid | renewed | canceled | revoked | refunded | failed.
 * Результат { result }: issued | renewed | duplicate | rejected | unknown_order | refunded | ignored.
 * deps: { now, waitUntil(promise), fetchImpl, sleep } — всё необязательно.
 */
export async function processEvent(env, providerName, event, deps = {}) {
  if (!event) return { result: "unknown_order" };
  const hasRef = typeof event.checkoutRef === "string";
  switch (event.type) {
    case "paid":
      return hasRef ? fulfilPaid(env, providerName, event, deps) : { result: "unknown_order" };
    case "renewed":
      return renewSubscription(env, providerName, event, deps);
    case "canceled":
    case "revoked":
      return endSubscription(env, providerName, event, deps);
    case "refunded":
      return refundOrder(env, providerName, event, deps);
    case "failed":
      return hasRef ? failPending(env, providerName, event) : { result: "unknown_order" };
    default:
      return { result: "ignored" };
  }
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

  // Сверка с заказом: сумма и валюта посчитаны сервером при создании заказа и от клиента не приходят.
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
  return issue(env, paid, tier, customer, contract, event, deps, now);
}

async function resume(env, order, event, deps, now) {
  if (order.key_id || order.provider_ref !== event.providerRef) return { result: "duplicate" };
  const [customer, contract, tier] = await Promise.all([
    one(env.DB, "SELECT * FROM web_customers WHERE id = ?1", order.customer_id),
    one(env.DB, "SELECT * FROM contract_signatures WHERE order_id = ?1", order.id),
    one(env.DB, "SELECT * FROM tiers WHERE name = ?1", order.tier),
  ]);
  if (!customer || !contract || !tier) return { result: "duplicate" };
  return issue(env, order, tier, customer, contract, event, deps, now);
}

/** Подписка у провайдера привязывается к заказу-источнику; дальше её события находят ключ через web_orders.key_id. Идемпотентно. */
async function ensureSubscription(db, order, event, now) {
  if (order.billing !== "subscription" || typeof event.subscriptionId !== "string" || !event.subscriptionId) return;
  await run(db, "INSERT OR IGNORE INTO web_subscriptions (sub_ref, provider, order_id, status, created_at) VALUES (?1, ?2, ?3, 'active', ?4)", event.subscriptionId, order.provider, order.id, now);
}

/** Конец срока нового ключа. Тестовый режим: сутки. Подписка с известным концом периода: он плюс запас. Иначе days дней от оплаты. */
function endForNewKey(env, order, event, paidAt) {
  if (isTest(env)) return paidAt + DAY;
  if (order.billing === "subscription" && Number.isFinite(event.periodEnd)) return event.periodEnd + SUB_GRACE_MS;
  return paidAt + order.days * DAY;
}

async function issue(env, order, tier, customer, contract, event, deps, now) {
  await ensureSubscription(env.DB, order, event, now);
  if (order.kind === "renew") return renew(env, order, tier, customer, contract, event, deps, now);
  const db = env.DB;
  const test = isTest(env);
  const key = await newApiKey(tier.name);
  const paidAt = order.paid_at ?? now;
  const expiresAt = endForNewKey(env, order, event, paidAt);
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

/**
 * SQL-выражение нового срока ключа и его параметр (?2).
 *  - подписка с известным концом периода: max(текущий срок, конец периода + запас): продление не укорачивает;
 *  - иначе: max(now, текущий срок) + период. Считается в SQL, поэтому два продления подряд не затирают друг друга.
 */
function extendExpr(env, billing, event, days) {
  if (!isTest(env) && billing === "subscription" && Number.isFinite(event.periodEnd)) {
    return { sql: "MAX(COALESCE(expires_at, 0), ?2)", period: event.periodEnd + SUB_GRACE_MS };
  }
  return { sql: "MAX(?1, COALESCE(expires_at, ?1)) + ?2", period: isTest(env) ? DAY : days * DAY };
}

/** Повторная покупка: тот же ключ, срок суммируется. Новый ключ не создаётся. */
async function renew(env, order, tier, customer, contract, event, deps, now) {
  const db = env.DB;
  const { sql, period } = extendExpr(env, order.billing, event, order.days);
  const [ext, link] = await db.batch([
    db
      .prepare(
        `UPDATE api_keys SET expires_at = ${sql} WHERE id = ?3 AND status = 'active' ` +
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
  await notifyRenewal(env, order, tier, customer, contract, order.renew_key_id, deps);
  return { result: "renewed", keyId: order.renew_key_id };
}

async function notifyRenewal(env, order, tier, customer, contract, keyId, deps) {
  const key = await one(env.DB, "SELECT key_prefix, expires_at FROM api_keys WHERE id = ?1", keyId);
  const task = deliverRenewal(env, order, tier, customer, contract, key, deps);
  if (deps.waitUntil) deps.waitUntil(task);
  else await task;
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

async function deliver(env, order, tier, customer, contract, plain, expiresAt, deps) {
  try {
    const lang = contract.lang ?? customer.language ?? "en";
    const mail = composeKeyEmail({
      lang,
      signer: contract.signer,
      plan: tier.title ?? tier.name,
      expiresAt,
      key: plain,
      domains: order.project_type === "site" && order.origins ? JSON.parse(order.origins) : [],
      limits: fmtLimits(tier, lang),
      limitsShort: limitsShort(tier, lang),
    });
    const status = await sendKeyEmail(env, { to: customer.email, ...mail }, { fetchImpl: deps.fetchImpl, sleep: deps.sleep });
    if (status === "failed") await run(env.DB, "UPDATE web_orders SET email_failed = 1 WHERE id = ?1", order.id);
  } catch {
    await run(env.DB, "UPDATE web_orders SET email_failed = 1 WHERE id = ?1", order.id).catch(() => {});
  }
}

// --- подписка: продление, отмена, отзыв -----------------------------------------------------------------------------

const SUB_JOIN =
  "SELECT s.id, s.sub_ref, s.provider, s.status, o.id AS order_id, o.key_id, o.amount_minor, o.currency, o.billing, o.days, o.tier " +
  "FROM web_subscriptions s JOIN web_orders o ON o.id = s.order_id ";

/** Каждое подтверждённое продление = ещё один месяц тому же ключу. Повтор доставки (тот же providerRef) ничего не продлевает. */
async function renewSubscription(env, providerName, event, deps) {
  const db = env.DB;
  const now = deps.now ?? Date.now();
  if (typeof event.subRef !== "string" || typeof event.providerRef !== "string" || !event.providerRef) return { result: "ignored" };
  const sub = await one(db, SUB_JOIN + "WHERE s.sub_ref = ?1", event.subRef);
  if (!sub || sub.provider !== providerName || !sub.key_id) return { result: "unknown_order" };
  if (sub.status === "revoked") return { result: "ignored" };
  if (event.amountMinor !== sub.amount_minor || event.currency !== sub.currency) {
    await run(db, "UPDATE web_subscriptions SET note = 'renewal_mismatch' WHERE id = ?1", sub.id);
    return { result: "rejected", note: "renewal_mismatch" };
  }
  const { sql, period } = extendExpr(env, "subscription", event, 30);
  let ext;
  try {
    [, ext] = await db.batch([
      db.prepare("INSERT INTO web_renewals (subscription_id, provider_ref, amount_minor, status, paid_at) VALUES (?1, ?2, ?3, 'paid', ?4)").bind(sub.id, event.providerRef, sub.amount_minor, now),
      db.prepare(`UPDATE api_keys SET expires_at = ${sql} WHERE id = ?3 AND status = 'active'`).bind(now, period, sub.key_id),
    ]);
  } catch (e) {
    if (/UNIQUE|constraint/i.test(String(e?.message))) return { result: "duplicate" };
    throw e;
  }
  if (changes(ext) !== 1) {
    await run(db, "UPDATE web_subscriptions SET note = 'key_revoked' WHERE id = ?1", sub.id);
    return { result: "rejected", note: "key_revoked" };
  }
  const [order, customer, contract, tier] = await Promise.all([
    one(db, "SELECT * FROM web_orders WHERE id = ?1", sub.order_id),
    one(db, "SELECT c.* FROM web_customers c JOIN web_orders o ON o.customer_id = c.id WHERE o.id = ?1", sub.order_id),
    one(db, "SELECT * FROM contract_signatures WHERE order_id = ?1", sub.order_id),
    one(db, "SELECT * FROM tiers WHERE name = ?1", sub.tier),
  ]);
  if (order && customer && contract && tier) await notifyRenewal(env, order, tier, customer, contract, sub.key_id, deps);
  return { result: "renewed", keyId: sub.key_id };
}

/**
 * canceled: продлений больше не будет, ключ работает до конца оплаченного периода (срок не трогаем).
 * revoked: подписка закончена у провайдера; срок ключа тоже не укорачиваем (мог быть продлён другой подпиской или пакетом),
 * доступ закончится сам по expires_at. Немедленный отзыв доступа — это возврат (refunded).
 */
async function endSubscription(env, providerName, event, deps) {
  const now = deps.now ?? Date.now();
  if (typeof event.subRef !== "string") return { result: "ignored" };
  const sub = await one(env.DB, SUB_JOIN + "WHERE s.sub_ref = ?1", event.subRef);
  if (!sub || sub.provider !== providerName) return { result: "unknown_order" };
  const status = event.type === "revoked" ? "revoked" : "canceled";
  await run(env.DB, "UPDATE web_subscriptions SET status = ?1, canceled_at = COALESCE(canceled_at, ?2) WHERE id = ?3 AND status != 'revoked'", status, now, sub.id);
  return { result: "ignored" };
}

/** Отмена подписки у провайдера при возврате. Не получилось — пометка на заказе, чтобы владелец отменил руками. */
async function cancelAtProvider(env, providerName, sub, orderId, now) {
  await run(env.DB, "UPDATE web_subscriptions SET status = 'canceled', canceled_at = COALESCE(canceled_at, ?1) WHERE id = ?2 AND status = 'active'", now, sub.id);
  try {
    await providerByName(env, providerName)?.cancelSubscription?.(env, sub.sub_ref);
  } catch {
    await run(env.DB, "UPDATE web_orders SET note = 'refunded_cancel_failed' WHERE id = ?1", orderId);
  }
}

// --- возвраты -------------------------------------------------------------------------------------------------------

async function refundOrder(env, providerName, event, deps) {
  const db = env.DB;
  const now = deps.now ?? Date.now();
  // Возврат находим по заказу сайта, а если события о заказе нет — по платежу провайдера: платёж заказа или платёж продления подписки.
  let order = typeof event.checkoutRef === "string" ? await one(db, "SELECT * FROM web_orders WHERE checkout_ref = ?1", event.checkoutRef) : null;
  if (!order && event.providerRef) order = await one(db, "SELECT * FROM web_orders WHERE provider_ref = ?1", event.providerRef);
  if (order) return refundInitial(env, providerName, order, now);
  const ren = event.providerRef
    ? await one(db, "SELECT r.id, r.status, s.id AS sub_id, s.sub_ref, s.provider, o.key_id, o.id AS order_id FROM web_renewals r JOIN web_subscriptions s ON s.id = r.subscription_id JOIN web_orders o ON o.id = s.order_id WHERE r.provider_ref = ?1", event.providerRef)
    : null;
  if (!ren || ren.provider !== providerName) return { result: "unknown_order" };
  if (ren.status === "refunded") return { result: "duplicate" };
  await run(db, "UPDATE web_renewals SET status = 'refunded' WHERE id = ?1", ren.id);
  await takeBackPeriod(env, ren.key_id, 30, now);
  await cancelAtProvider(env, providerName, { id: ren.sub_id, sub_ref: ren.sub_ref }, ren.order_id, now);
  return { result: "refunded" };
}

/** Возврат продления отнимает только оплаченный им период; если срока после этого не осталось, ключ отзывается. */
async function takeBackPeriod(env, keyId, days, now) {
  if (!keyId) return;
  const period = (isTest(env) ? 1 : days) * DAY;
  await run(env.DB, "UPDATE api_keys SET expires_at = expires_at - ?1 WHERE id = ?2 AND expires_at IS NOT NULL", period, keyId);
  await run(env.DB, "UPDATE api_keys SET status = 'revoked', revoked_at = ?1 WHERE id = ?2 AND status = 'active' AND expires_at <= ?1", now, keyId);
}

async function refundInitial(env, providerName, order, now) {
  const db = env.DB;
  if (order.provider !== providerName) return { result: "unknown_order" };
  if (order.status !== "paid") return { result: order.status === "refunded" ? "duplicate" : "ignored" };
  // Как markOrderRefunded у бота: заказ → refunded, ключ → revoked. Пометка для владельца — в note.
  await run(db, "UPDATE web_orders SET status = 'refunded', note = ?1 WHERE id = ?2 AND status = 'paid'", order.kind === "renew" ? "refunded_renewal" : "refunded", order.id);
  if (order.key_id && order.kind === "renew") await takeBackPeriod(env, order.key_id, order.days, now);
  else if (order.key_id) await run(db, "UPDATE api_keys SET status = 'revoked', revoked_at = ?1 WHERE id = ?2 AND status != 'revoked'", now, order.key_id);
  // Подписку Polar при возврате отменяем, иначе завтра спишут снова.
  const sub = await one(db, "SELECT id, sub_ref FROM web_subscriptions WHERE order_id = ?1", order.id);
  if (sub) await cancelAtProvider(env, providerName, sub, order.id, now);
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
