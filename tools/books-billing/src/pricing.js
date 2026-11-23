// Цены продажи на сайте. Считает только сервер; expectedAmountUsd с сайта нужен лишь для сверки (расхождение = 400 price_mismatch).
//  - подписка Polar (method card): ежемесячно, цена = tiers.price_usd_minor (Basic $3, Pro $7, Ultra $12);
//  - предоплаченные пакеты lava.top и USDT Solana (method lava | usdt): 30 / 90 / 180 / 365 дней без автопродления, сетка ниже в долларах.
// Лимиты посуточные (в секунду, минуту, сутки UTC) и от срока не зависят: месячных счётчиков и пула квоты нет.

export const PACK_DAYS = [30, 90, 180, 365];

const PACK_PRICE_USD = {
  basic: { 30: 3, 90: 9, 180: 15, 365: 29 },
  pro: { 30: 7, 90: 20, 180: 40, 365: 75 },
  ultra: { 30: 12, 90: 35, 180: 69, 365: 129 },
};

/** Центы США за пакет или null, если такого тарифа или срока нет. */
export function packPriceMinor(tier, days) {
  const row = Object.hasOwn(PACK_PRICE_USD, tier) ? PACK_PRICE_USD[tier] : null;
  const usd = row && Object.hasOwn(row, days) ? row[days] : null;
  return usd === null ? null : usd * 100;
}

/** Способ оплаты → модель. card = подписка (всегда 30 дней), lava | usdt = пакет. */
export const BILLING_BY_METHOD = { card: "subscription", lava: "one_time", usdt: "one_time" };
