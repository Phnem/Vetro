// Очередь исходящих уведомлений из панели (bot_outbox): обработка по cron раз в минуту.

import { getPendingOutbox, markOutboxFailed, markOutboxSent } from "./db.js";

const BATCH_LIMIT = 20;

export function isBotBlockedError(err) {
  return err?.code === 403 || /bot was blocked/i.test(String(err?.message ?? ""));
}

export async function processOutbox(env, tg, now = Date.now()) {
  const rows = await getPendingOutbox(env.DB, BATCH_LIMIT);
  let sent = 0;
  let failed = 0;
  for (const row of rows) {
    try {
      await tg.sendMessage(row.chat_id, row.text, { parse_mode: "HTML" });
      await markOutboxSent(env.DB, row.id, now);
      sent++;
    } catch (err) {
      const permanent = isBotBlockedError(err);
      await markOutboxFailed(env.DB, row.id, err?.message || String(err), { permanent, now });
      failed++;
    }
  }
  return { total: rows.length, sent, failed };
}
