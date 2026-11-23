// Реестр провайдеров оплаты. Платёжные системы владелец ещё выбирает: здесь только движок и переходник mock.
//
// Интерфейс переходника:
//   name                                      строка: mock | polar | lava | usdt (совпадает с web_orders.provider и с {provider} в пути вебхука)
//   configured(env) -> boolean                готов ли к работе (секреты заданы); не готов = метод даёт 503 method_unavailable
//   createCheckout(env, { order, tier, customer, returnUrl }) -> { url }   url строго https; ничего в D1 не пишет
//   parseWebhook(request, rawBody, env) -> event | null   проверка подписи ДО разбора; null = подпись неверна
//     event = { type, checkoutRef, providerRef, amountMinor, currency, tier, subscriptionId?, subRef?, periodEnd? }
//       type: paid (оплата заказа; у подписки с subscriptionId) | renewed (продление подписки: subRef, providerRef, amountMinor, currency, periodEnd)
//             | canceled (подписка отменена, доступ до конца периода) | revoked (доступ закончен) | refunded | failed
//   cancelSubscription(env, subRef)           необязательный: отмена подписки у провайдера при возврате (Polar)
//     providerRef — id платежа/события у провайдера (UNIQUE в web_orders: повтор доставки ничего не выдаёт)
//
// VETRO-TAIL[billing-provider]: новый провайдер = файл рядом + строка в PROVIDERS + строка в METHOD_PROVIDER, если метод новый.
// Не готовый провайдер просто возвращает configured() = false. Ключи и секреты вебхуков только через `wrangler secret put`.
import { isTest } from "../http.js";
import { mock } from "./mock.js";

const PROVIDERS = { mock };

/** Какой провайдер обслуживает метод, который прислал сайт. Включён он или нет, решает configured(). */
const METHOD_PROVIDER = { card: "polar", lava: "lava", usdt: "usdt" };

/** Провайдер для checkout. В тестовом режиме любой метод обслуживает mock; в боевом — только настроенный переходник, иначе null. */
export function providerForMethod(env, method) {
  if (isTest(env)) return mock;
  const p = PROVIDERS[METHOD_PROVIDER[method]];
  return p && p !== mock && p.configured(env) ? p : null;
}

/** Провайдер для вебхука /webhook/{name}. mock доступен только в тестовом режиме, остальные — только настроенные. */
export function providerByName(env, name) {
  const p = Object.hasOwn(PROVIDERS, name) ? PROVIDERS[name] : null;
  if (!p) return null;
  if (p === mock) return isTest(env) ? mock : null;
  return p.configured(env) ? p : null;
}
