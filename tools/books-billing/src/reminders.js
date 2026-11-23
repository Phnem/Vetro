// Cron-задачи платёжного Worker, кроме закрытия просроченных заказов (issue.js):
//  1. напоминание за 3 дня до конца пакета (lava.top, USDT). Для подписки напоминаний нет: продление автоматическое;
//  2. очистка суточных счётчиков по ключам старше N дней (usage_daily), только если задана переменная USAGE_RETENTION_DAYS.
import { isTest } from "./http.js";
import { composeReminderEmail, hasMailTransport, sendKeyEmail } from "./mail.js";

const DAY = 86_400_000;
export const REMINDER_DAYS = 3;

const all = async (db, sql, ...args) => (await db.prepare(sql).bind(...args).all()).results ?? [];
const one = (db, sql, ...args) => db.prepare(sql).bind(...args).first();
const run = (db, sql, ...args) => db.prepare(sql).bind(...args).run();

/**
 * Ключи-пакеты, которые закончатся в ближайшие 3 дня. Одно письмо на значение expires_at: после продления срок другой, напомнит снова.
 * Без почтового транспорта и в тестовом режиме ничего не делает (отметку не ставит, чтобы напоминание не потерялось).
 * Если отправка не удалась, отметка снимается и следующий запуск cron повторит попытку.
 */
export async function sendExpiryReminders(env, now = Date.now(), deps = {}) {
  if (isTest(env) || !hasMailTransport(env)) return 0;
  const rows = await all(
    env.DB,
    "SELECT k.id AS key_id, k.key_prefix, k.expires_at, k.tier, MAX(o.id) AS order_id FROM api_keys k JOIN web_orders o ON o.key_id = k.id AND o.status = 'paid' " +
      "WHERE k.status = 'active' AND k.expires_at > ?1 AND k.expires_at <= ?2 AND o.billing = 'one_time' " +
      "AND NOT EXISTS (SELECT 1 FROM web_reminders r WHERE r.key_id = k.id AND r.expires_at = k.expires_at) " +
      "AND NOT EXISTS (SELECT 1 FROM web_subscriptions s JOIN web_orders so ON so.id = s.order_id WHERE so.key_id = k.id AND s.status = 'active') " +
      "GROUP BY k.id LIMIT 50",
    now,
    now + REMINDER_DAYS * DAY,
  );
  let sent = 0;
  for (const r of rows) {
    const claim = await run(env.DB, "INSERT OR IGNORE INTO web_reminders (key_id, expires_at, sent_at) VALUES (?1, ?2, ?3)", r.key_id, r.expires_at, now);
    if (Number(claim?.meta?.changes ?? 0) !== 1) continue;
    const [customer, contract, tier] = await Promise.all([
      one(env.DB, "SELECT c.* FROM web_customers c JOIN web_orders o ON o.customer_id = c.id WHERE o.id = ?1", r.order_id),
      one(env.DB, "SELECT lang, signer FROM contract_signatures WHERE order_id = ?1", r.order_id),
      one(env.DB, "SELECT title, name FROM tiers WHERE name = ?1", r.tier),
    ]);
    const mail = composeReminderEmail({
      lang: contract?.lang ?? customer?.language ?? "en",
      signer: contract?.signer ?? "",
      plan: tier?.title ?? r.tier,
      expiresAt: r.expires_at,
      prefix: r.key_prefix,
    });
    const status = customer ? await sendKeyEmail(env, { to: customer.email, ...mail }, { fetchImpl: deps.fetchImpl, sleep: deps.sleep }) : "failed";
    if (status === "sent") sent += 1;
    else await run(env.DB, "DELETE FROM web_reminders WHERE key_id = ?1 AND expires_at = ?2", r.key_id, r.expires_at);
  }
  return sent;
}

/** Суточные счётчики по ключам хранятся не дольше USAGE_RETENTION_DAYS (по политике до 60). Не задано или < 7 = ничего не удаляется. */
export async function purgeOldUsage(env, now = Date.now()) {
  const days = Number(env.USAGE_RETENTION_DAYS);
  if (!Number.isInteger(days) || days < 7) return 0;
  const cutoff = new Date(now - days * DAY).toISOString().slice(0, 10);
  const res = await run(env.DB, "DELETE FROM usage_daily WHERE day < ?1", cutoff);
  return Number(res?.meta?.changes ?? 0);
}
