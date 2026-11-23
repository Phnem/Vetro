// Переходник Polar.sh (method card): ежемесячная подписка Recurring Monthly на тарифы Basic / Pro / Ultra.
// Polar выступает Merchant of Record. Мы создаём checkout-сессию (POST /v1/checkouts/) и принимаем вебхуки (Standard Webhooks).
//
// Настройка (всё, кроме трёх ID товаров, секреты через `wrangler secret put`):
//   POLAR_ACCESS_TOKEN    Organization Access Token (права checkouts:write, subscriptions:write)
//   POLAR_WEBHOOK_SECRET  секрет endpoint'а вебхуков из панели Polar (whsec_…)
//   POLAR_PRODUCT_BASIC / POLAR_PRODUCT_PRO / POLAR_PRODUCT_ULTRA   ID подписочных товаров (не секрет, обычные переменные)
//   POLAR_SERVER          "sandbox" (sandbox-api.polar.sh) | "production" (по умолчанию)
// Вебхук: https://billing.vetrostudio.bid/v1/billing/webhook/polar, события order.paid, order.refunded,
//   subscription.canceled, subscription.uncanceled, subscription.revoked.
//
// Соответствие событий Polar событиям движка (src/issue.js):
//   order.paid, billing_reason purchase | subscription_create  → paid   (первая оплата, ключ выдаётся)
//   order.paid, billing_reason subscription_cycle             → renewed (продление, +1 месяц к тому же ключу)
//   subscription.canceled | uncanceled | revoked              → canceled | uncanceled | revoked
//   order.refunded                                            → refunded (по id заказа Polar; заказ сайта не указывается:
//                                                               метаданные заказа-продления те же, что у первого)
// Всё остальное подтверждается и игнорируется. Скидочные коды в checkout выключены, сумма сверяется по net_amount (без налога).
import { RETURN_BASE } from "../http.js";
import { hmacSha256Base64, timingSafeEqualStr } from "../signing.js";

const BASES = { production: "https://api.polar.sh", sandbox: "https://sandbox-api.polar.sh" };
const TOLERANCE_S = 300; // как в Standard Webhooks: подпись старше пяти минут не принимается (защита от повторной подачи)

const te = new TextEncoder();
const base = (env) => BASES[env.POLAR_SERVER === "sandbox" ? "sandbox" : "production"];
const productFor = (env, tier) => env[`POLAR_PRODUCT_${String(tier).toUpperCase()}`];

function b64decode(s) {
  const bin = atob(s);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

/** Секреты до 8.09.2026 подписывали UTF-8 байтами всей строки whsec_…, новые — base64-декодированной частью после whsec_ (SDK Polar пробует оба). */
function candidateKeys(secret) {
  const keys = [te.encode(secret)];
  if (secret.startsWith("whsec_")) {
    try {
      keys.push(b64decode(secret.slice(6)));
    } catch {
      // не base64: остаётся первая схема
    }
  }
  return keys;
}

async function verifySignature(request, rawBody, env) {
  const id = request.headers.get("webhook-id");
  const ts = request.headers.get("webhook-timestamp");
  const sigHeader = request.headers.get("webhook-signature");
  if (!id || !ts || !sigHeader || !env.POLAR_WEBHOOK_SECRET) return false;
  const t = Number(ts);
  if (!Number.isFinite(t) || Math.abs(Date.now() / 1000 - t) > TOLERANCE_S) return false;
  const given = sigHeader
    .split(" ")
    .map((p) => p.split(","))
    .filter((p) => p[0] === "v1" && p[1])
    .map((p) => p[1]);
  const message = `${id}.${ts}.${rawBody}`;
  for (const key of candidateKeys(env.POLAR_WEBHOOK_SECRET)) {
    const expected = await hmacSha256Base64(key, message);
    if (given.some((g) => timingSafeEqualStr(g, expected))) return true;
  }
  return false;
}

const orderRef = (data) => data.metadata?.order_ref ?? data.subscription?.metadata?.order_ref;
const money = (data) => (Number.isInteger(data.net_amount) ? data.net_amount : data.subtotal_amount);
const periodEnd = (data) => {
  const t = Date.parse(data.subscription?.current_period_end ?? "");
  return Number.isFinite(t) ? t : undefined;
};

/** Событие Polar → событие движка. Неизвестные типы дают { type: "ignore" } (их надо подтвердить, чтобы Polar не повторял). */
export function mapEvent(body) {
  const data = body?.data ?? {};
  switch (body?.type) {
    case "order.paid": {
      const reason = data.billing_reason;
      const common = { providerRef: data.id, amountMinor: money(data), currency: String(data.currency ?? "").toUpperCase(), periodEnd: periodEnd(data) };
      if (reason === "purchase" || reason === "subscription_create") {
        return { ...common, type: "paid", checkoutRef: orderRef(data), tier: data.metadata?.tier ?? data.subscription?.metadata?.tier, subscriptionId: data.subscription_id ?? undefined };
      }
      if (reason === "subscription_cycle") return { ...common, type: "renewed", subRef: data.subscription_id };
      return { type: "ignore" };
    }
    case "order.refunded":
      return { type: "refunded", providerRef: data.id };
    case "subscription.canceled":
      return { type: "canceled", subRef: data.id };
    case "subscription.uncanceled":
      return { type: "uncanceled", subRef: data.id };
    case "subscription.revoked":
      return { type: "revoked", subRef: data.id };
    default:
      return { type: "ignore" };
  }
}

export function makePolar(fetchImpl = (...args) => globalThis.fetch(...args)) {
  return {
    name: "polar",

    configured(env) {
      return Boolean(env.POLAR_ACCESS_TOKEN && env.POLAR_WEBHOOK_SECRET && env.POLAR_PRODUCT_BASIC && env.POLAR_PRODUCT_PRO && env.POLAR_PRODUCT_ULTRA);
    },

    /** Checkout-сессия на подписочный товар тарифа. Заказ сайта передаётся в metadata и возвращается в каждом событии. */
    async createCheckout(env, { order, customer, returnUrl }) {
      const product = productFor(env, order.tier);
      if (!product) throw new Error("polar_no_product");
      const res = await fetchImpl(`${base(env)}/v1/checkouts/`, {
        method: "POST",
        headers: { authorization: `Bearer ${env.POLAR_ACCESS_TOKEN}`, "content-type": "application/json" },
        body: JSON.stringify({
          products: [product],
          success_url: returnUrl,
          return_url: RETURN_BASE,
          customer_email: customer.email,
          allow_discount_codes: false,
          metadata: { order_ref: order.checkout_ref, tier: order.tier },
        }),
      });
      if (!res.ok) throw new Error("polar_http_" + res.status);
      const data = await res.json();
      if (typeof data?.url !== "string") throw new Error("polar_no_url");
      return { url: data.url };
    },

    /** Подпись проверяется по сырому телу до разбора. null = подпись неверна. */
    async parseWebhook(request, rawBody, env) {
      if (!(await verifySignature(request, rawBody, env))) return null;
      return mapEvent(JSON.parse(rawBody));
    },

    /**
     * Возврат = немедленный отзыв подписки (DELETE /v1/subscriptions/{id}), иначе в следующем месяце спишут снова.
     * 403 («уже отозвана») и 404 не ошибка; 409 (подписка обновляется) и 422 — ошибка, владелец отменит руками (note refunded_cancel_failed).
     */
    async cancelSubscription(env, subRef) {
      const res = await fetchImpl(`${base(env)}/v1/subscriptions/${encodeURIComponent(subRef)}`, {
        method: "DELETE",
        headers: { authorization: `Bearer ${env.POLAR_ACCESS_TOKEN}` },
      });
      if (!res.ok && res.status !== 403 && res.status !== 404) throw new Error("polar_http_" + res.status);
    },
  };
}

export const polar = makePolar();
