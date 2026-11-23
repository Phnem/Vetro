// GET /v1/billing/order/{id}: статус заказа и одноразовый показ ключа. Больше ничего: ни e-mail, ни проекта, ни тарифа.
// Идентификатор заказа непредсказуем (128 бит) и служит ключом доступа, поэтому формат фиксирован, а ?order= сайт убирает из адреса.
import { ApiError, json, limited } from "./http.js";
import { revealKey } from "./issue.js";

export const ID_RE = /^[\w-]{6,64}$/;

export async function handleOrder(request, env, id) {
  if (!ID_RE.test(id)) throw new ApiError(404, "not_found");
  if (await limited(env.LIMITER, "ord", request)) throw new ApiError(429, "rate_limited");

  const order = await env.DB.prepare("SELECT status, key_id, kind FROM web_orders WHERE checkout_ref = ?1").bind(id).first();
  if (!order) throw new ApiError(404, "not_found");

  if (order.status === "pending") return json(request, { status: "pending" });
  if (order.status !== "paid") return json(request, { status: "failed" }); // failed | refunded | expired
  // Оплачен, но ключ ещё не выдан (между «деньги пришли» и «ключ записан»): для страницы это всё ещё ожидание.
  if (!order.key_id) return json(request, { status: "pending" });

  // Продление: нового ключа нет, страница просто узнаёт, что срок продлён (поле renewed, остальное как у обычного paid).
  if (order.kind === "renew") return json(request, { status: "paid", renewed: true });

  // Только при первом чтении: ключ достаётся из REVEAL и сразу удаляется.
  let key = null;
  try {
    key = (await env.REVEAL?.get(revealKey(id))) ?? null;
    if (key) await env.REVEAL.delete(revealKey(id));
  } catch {
    key = null;
  }
  return json(request, key ? { status: "paid", key } : { status: "paid" });
}
