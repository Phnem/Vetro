// Тестовая оплата (BILLING_MODE=test): страница «Оплатить (тест)» и её обработчик. В боевом режиме эти пути отвечают 404.
// Кнопка вызывает ту же функцию выдачи ключа, что и вебхук, и возвращает на сайт. Деньги не участвуют, ключи тестового режима
// помечены (owner web:test, label «TEST …», срок сутки) и отзываются после проверки.
// У заказа-подписки (method card) после оплаты на странице есть кнопки «Продление» и «Отмена подписки»: те же события, что пришлёт Polar.
import { ApiError, html, isTest, readBody, redirect, returnUrl } from "./http.js";
import { processEvent } from "./issue.js";
import { ID_RE } from "./order.js";

const rand = () => [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, "0")).join("");

const PAGE = (order) => {
  const ref = order.checkout_ref;
  const form = (action, label) =>
    `<form method="post" action="/v1/billing/test/pay"><input type="hidden" name="order" value="${ref}"><input type="hidden" name="action" value="${action}"><button type="submit">${label}</button></form>`;
  const sub = order.billing === "subscription";
  let body;
  if (order.status === "pending") body = form("pay", "Pay (test)");
  else if (order.status === "paid" && sub) body = `<p>Paid. Subscription events, as Polar would send them:</p>${form("renew", "Renewal (test)")}${form("cancel", "Cancel subscription (test)")}<p><a class="btn" href="${returnUrl(ref)}">Back to the site</a></p>`;
  else body = `<p>This order is already processed.</p><a class="btn" href="${returnUrl(ref)}">Back to the site</a>`;
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Vetro API — test payment</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:28rem;margin:12vh auto;padding:0 1rem;color:#111}
h1{font-size:1.25rem}.tag{display:inline-block;background:#fde68a;border-radius:.4rem;padding:.1rem .5rem;font-size:.8rem}
form{margin:.5rem 0}button,a.btn{display:inline-block;font:inherit;padding:.7rem 1.2rem;border:0;border-radius:.6rem;background:#1e3a8a;color:#fff;cursor:pointer;text-decoration:none}</style></head>
<body><p class="tag">TEST MODE · no money is taken</p><h1>Vetro API: ${order.tier}, $${(order.amount_minor / 100).toFixed(2)}${sub ? " / month" : ` / ${order.days} days`}</h1>
${body}
</body></html>`;
};

async function loadMockOrder(env, ref) {
  if (!isTest(env)) throw new ApiError(404, "not_found");
  if (typeof ref !== "string" || !ID_RE.test(ref)) throw new ApiError(404, "not_found");
  const order = await env.DB.prepare("SELECT * FROM web_orders WHERE checkout_ref = ?1").bind(ref).first();
  if (!order || order.provider !== "mock") throw new ApiError(404, "not_found");
  return order;
}

export async function handleTestPayPage(request, env) {
  const ref = new URL(request.url).searchParams.get("order");
  return html(request, PAGE(await loadMockOrder(env, ref)));
}

export async function handleTestPay(request, env, deps = {}) {
  const form = new URLSearchParams(await readBody(request, 2048));
  const order = await loadMockOrder(env, form.get("order"));
  const action = form.get("action") ?? "pay";
  const now = deps.now ?? Date.now();
  const sub = order.billing === "subscription";
  const subRef = "sub_" + order.checkout_ref.slice(4, 20);
  const base = { checkoutRef: order.checkout_ref, amountMinor: order.amount_minor, currency: order.currency, tier: order.tier };
  if (action === "pay") {
    await processEvent(env, "mock", { ...base, type: "paid", providerRef: "mock_" + rand(), ...(sub ? { subscriptionId: subRef, subRef, periodEnd: now + 30 * 86_400_000 } : {}) }, deps);
  } else if (sub && action === "renew") {
    await processEvent(env, "mock", { ...base, type: "renewed", providerRef: "mock_ren_" + rand(), subRef, periodEnd: now + 30 * 86_400_000 }, deps);
  } else if (sub && action === "cancel") {
    await processEvent(env, "mock", { type: "canceled", subRef }, deps);
  }
  return redirect(returnUrl(order.checkout_ref));
}
