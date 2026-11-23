// Cloudflare Worker vetro-books-billing (billing.vetrostudio.bid): приём заказов с сайта, оплата, выдача ключа Vetro API.
// Платёжные системы подключаются переходниками (src/providers); пока есть только mock для тестового режима (BILLING_MODE=test).
import { handleCheckout } from "./checkout.js";
import { ApiError, json, isTest, preflight, readBody } from "./http.js";
import { expirePending, processEvent } from "./issue.js";
import { handleOrder } from "./order.js";
import { providerByName } from "./providers/index.js";
import { handleTestPay, handleTestPayPage } from "./testpay.js";

const WEBHOOK_LIMIT = 256 * 1024;

async function handleWebhook(request, env, name, deps) {
  const provider = providerByName(env, name);
  if (!provider) throw new ApiError(404, "not_found");
  // Сырое тело: подпись проверяется до разбора.
  const raw = await readBody(request, WEBHOOK_LIMIT);
  let event;
  try {
    event = await provider.parseWebhook(request, raw, env);
  } catch {
    throw new ApiError(400, "bad_request");
  }
  if (!event) throw new ApiError(401, "unauthorized");
  const out = await processEvent(env, provider.name, event, deps);
  return json(request, { ok: true, result: out.result });
}

export async function route(request, env, deps) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "");
  const m = request.method;
  let seg;

  if (m === "OPTIONS") return preflight(request);
  if (path === "/v1/billing/health") return m === "GET" ? json(request, { ok: true, mode: isTest(env) ? "test" : "live" }) : notAllowed(request);
  if (path === "/v1/billing/checkout") return m === "POST" ? handleCheckout(request, env, deps) : notAllowed(request);
  if ((seg = path.match(/^\/v1\/billing\/order\/([^/]+)$/))) return m === "GET" ? handleOrder(request, env, seg[1]) : notAllowed(request);
  if ((seg = path.match(/^\/v1\/billing\/webhook\/([a-z]+)$/))) return m === "POST" ? handleWebhook(request, env, seg[1], deps) : notAllowed(request);
  if (path === "/v1/billing/test/pay") {
    if (!isTest(env)) throw new ApiError(404, "not_found");
    if (m === "GET") return handleTestPayPage(request, env);
    if (m === "POST") return handleTestPay(request, env, deps);
    return notAllowed(request);
  }
  throw new ApiError(404, "not_found");
}

const notAllowed = (request) => json(request, { error: "method_not_allowed" }, 405);

export default {
  async fetch(request, env, ctx) {
    if (!env.DB) return json(request, { error: "misconfigured" }, 500);
    const deps = { waitUntil: ctx?.waitUntil ? (p) => ctx.waitUntil(p) : undefined };
    try {
      return await route(request, env, deps);
    } catch (e) {
      if (e instanceof ApiError) return json(request, { error: e.code, ...e.extra }, e.status);
      // Тела запросов, ключи и адреса в логи не пишем (логи выключены): наружу только код.
      return json(request, { error: "internal" }, 500);
    }
  },

  async scheduled(_controller, env) {
    if (!env.DB) return;
    await expirePending(env);
  },
};
