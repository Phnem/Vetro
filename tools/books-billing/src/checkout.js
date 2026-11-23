// POST /v1/billing/checkout: валидация → rate limit → метод настроен → блок покупателя / max_keys → запись заказа → провайдер → ответ.
// Всё, что можно проверить без записи, проверяется до первой записи в D1: 503 method_unavailable не оставляет в базе ничего.
import { ApiError, json, limited, readBody, returnUrl } from "./http.js";
import { newOrderRef } from "./keys.js";
import { providerForMethod } from "./providers/index.js";
import { validateCheckout } from "./validate.js";

export const MAX_BODY = 300 * 1024;
export const ORDERS_PER_EMAIL_HOUR = 5;
const HOUR = 3_600_000;

const one = (db, sql, ...args) => db.prepare(sql).bind(...args).first();
const run = (db, sql, ...args) => db.prepare(sql).bind(...args).run();

/** Активные неистёкшие ключи покупателя: по owner `web:<id>` и по ключам, выданным его заказами (ключи тестового режима имеют owner web:test). */
export async function countActiveKeys(db, customerId, now) {
  const row = await one(
    db,
    "SELECT COUNT(*) AS n FROM api_keys WHERE status = 'active' AND (expires_at IS NULL OR expires_at > ?1) " +
      "AND (owner = ?2 OR id IN (SELECT key_id FROM web_orders WHERE customer_id = ?3 AND key_id IS NOT NULL))",
    now,
    `web:${customerId}`,
    customerId,
  );
  return Number(row?.n ?? 0);
}

export async function handleCheckout(request, env, deps = {}) {
  const now = deps.now ?? Date.now();
  const raw = await readBody(request, MAX_BODY);
  let body;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new ApiError(400, "bad_json");
  }
  const v = validateCheckout(body);
  const db = env.DB;

  // Цена и «продаётся» берутся из tiers, не от клиента.
  const tier = await one(db, "SELECT * FROM tiers WHERE name = ?1", v.plan);
  if (!tier || Number(tier.sold) !== 1 || !(Number(tier.price_usd_minor) > 0)) throw new ApiError(400, "bad_field", { field: "plan" });

  if (await limited(env.LIMITER_CHECKOUT ?? env.LIMITER, "co", request)) throw new ApiError(429, "rate_limited");

  const provider = providerForMethod(env, v.method);
  if (!provider) throw new ApiError(503, "method_unavailable");

  const existing = await one(db, "SELECT * FROM web_customers WHERE email = ?1", v.email);
  if (existing) {
    if (existing.banned === 1) throw new ApiError(403, "forbidden");
    const recent = await one(db, "SELECT COUNT(*) AS n FROM web_orders WHERE customer_id = ?1 AND created_at > ?2", existing.id, now - HOUR);
    if (Number(recent?.n ?? 0) >= ORDERS_PER_EMAIL_HOUR) throw new ApiError(429, "rate_limited");
    if ((await countActiveKeys(db, existing.id, now)) >= Number(tier.max_keys)) throw new ApiError(409, "key_limit");
  }

  // С этой точки пишем в D1.
  await run(db, "INSERT OR IGNORE INTO web_customers (email, language, banned, created_at) VALUES (?1, ?2, 0, ?3)", v.email, v.lang, now);
  const customer = await one(db, "SELECT * FROM web_customers WHERE email = ?1", v.email);
  if (customer.banned === 1) throw new ApiError(403, "forbidden");
  if (existing && existing.language !== v.lang) await run(db, "UPDATE web_customers SET language = ?1 WHERE id = ?2", v.lang, customer.id);

  const ref = newOrderRef();
  const origins = v.project.domains.length ? JSON.stringify(v.project.domains) : null;
  await db.batch([
    db
      .prepare(
        "INSERT INTO web_orders (checkout_ref, customer_id, kind, tier, provider, method, amount_minor, currency, status, " +
          "project_name, project_type, project_description, origins, created_at) " +
          "VALUES (?1, ?2, 'new', ?3, ?4, ?5, ?6, 'USD', 'pending', ?7, ?8, ?9, ?10, ?11)",
      )
      .bind(ref, customer.id, tier.name, provider.name, v.method, Number(tier.price_usd_minor), v.project.name, v.project.type, v.project.description, origins, now),
    db
      .prepare(
        "INSERT INTO contract_signatures (order_id, signer, terms_hash, signature_svg, client_signed_at, accepted_at, lang) " +
          "VALUES ((SELECT id FROM web_orders WHERE checkout_ref = ?1), ?2, ?3, ?4, ?5, ?6, ?7)",
      )
      .bind(ref, v.contract.signer, v.contract.termsVersion, v.contract.signatureSvg, v.contract.signedAt, now, v.lang),
  ]);
  const order = await one(db, "SELECT * FROM web_orders WHERE checkout_ref = ?1", ref);

  let url;
  try {
    ({ url } = await provider.createCheckout(env, { order, tier, customer, returnUrl: returnUrl(ref) }));
    if (typeof url !== "string" || !url.startsWith("https://")) throw new Error("bad_url");
  } catch {
    await run(db, "UPDATE web_orders SET status = 'failed', note = 'provider_error' WHERE id = ?1 AND status = 'pending'", order.id);
    throw new ApiError(502, "provider_error");
  }
  return json(request, { orderId: ref, url });
}
