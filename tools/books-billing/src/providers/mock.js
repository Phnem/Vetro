// Переходник `mock`: тестовый режим без денег (BILLING_MODE=test). Сайт проходит форму → «оплату» → возврат → ключ.
// Работает ТОЛЬКО в тестовом режиме (реестр отдаёт его лишь тогда). Вебхук mock подписан HMAC-SHA256 от тела секретом MOCK_WEBHOOK_SECRET.
import { BILLING_BASE } from "../http.js";
import { hmacSha256Hex, timingSafeEqualStr } from "../signing.js";

export const mock = {
  name: "mock",

  configured(env) {
    return env.BILLING_MODE === "test";
  },

  /** Возвращает страницу «Оплатить (тест)» этого же Worker. */
  async createCheckout(_env, { order }) {
    return { url: `${BILLING_BASE}/v1/billing/test/pay?order=${order.checkout_ref}` };
  },

  /**
   * Разбор вебхука: сначала подпись по сырому телу, потом JSON. Возвращает событие или null (подпись неверна/не настроена).
   * Тело: { type: "paid"|"renewed"|"canceled"|"revoked"|"refunded"|"failed", orderId, paymentId, amountMinor, currency, tier, subscriptionId, periodEnd }.
   */
  async parseWebhook(request, rawBody, env) {
    const secret = env.MOCK_WEBHOOK_SECRET;
    if (!secret) return null;
    const got = request.headers.get("x-mock-signature");
    if (!timingSafeEqualStr(got, await hmacSha256Hex(secret, rawBody))) return null;
    const b = JSON.parse(rawBody);
    return {
      type: b.type,
      checkoutRef: b.orderId,
      providerRef: b.paymentId,
      amountMinor: b.amountMinor,
      currency: b.currency,
      tier: b.tier,
      subscriptionId: b.subscriptionId,
      subRef: b.subscriptionId,
      periodEnd: b.periodEnd,
    };
  },

  /** Отмена подписки при возврате. У mock отмена только записывается (env.MOCK_CANCELLED), чтобы это видели тесты. */
  async cancelSubscription(env, subRef) {
    env.MOCK_CANCELLED?.push(subRef);
  },
};
