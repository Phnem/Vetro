// Cloudflare Worker для Telegram-бота продажи ключей Vetro Books API (vetro-books-bot).

import { handleUpdate } from "./bot.js";
import { processOutbox } from "./outbox.js";
import { createTelegramClient } from "./telegram.js";

const te = new TextEncoder();

export function timingSafeEqualStr(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const ab = te.encode(a);
  const bb = te.encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
  return diff === 0;
}

export function verifyWebhookSecret(request, env) {
  const expected = env.TELEGRAM_WEBHOOK_SECRET;
  if (!expected) return false;
  const got = request.headers.get("x-telegram-bot-api-secret-token");
  return timingSafeEqualStr(got, expected);
}

export function createWorker(fetchImpl = fetch) {
  return {
    async fetch(request, env) {
      const url = new URL(request.url);

      if (request.method === "GET" && url.pathname === "/health") {
        return Response.json({ ok: true });
      }

      if (request.method !== "POST" || (url.pathname !== "/webhook" && url.pathname !== "/")) {
        return Response.json({ error: "not_found" }, { status: 404 });
      }

      if (!env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_WEBHOOK_SECRET || !env.DB) {
        return Response.json({ error: "misconfigured" }, { status: 500 });
      }

      if (!verifyWebhookSecret(request, env)) {
        return Response.json({ error: "unauthorized" }, { status: 401 });
      }

      let update;
      try {
        update = await request.json();
      } catch {
        return Response.json({ error: "invalid_json" }, { status: 400 });
      }

      const tg = createTelegramClient(env.TELEGRAM_BOT_TOKEN, fetchImpl);
      try {
        await handleUpdate(update, env, tg);
        return Response.json({ ok: true });
      } catch {
        // Не логируем тело обновления и секреты; возвращаем 500, чтобы Telegram повторил доставку (например, при сбое D1).
        return Response.json({ error: "internal_error" }, { status: 500 });
      }
    },

    async scheduled(_controller, env) {
      if (!env.TELEGRAM_BOT_TOKEN || !env.DB) return;
      const tg = createTelegramClient(env.TELEGRAM_BOT_TOKEN, fetchImpl);
      await processOutbox(env, tg);
    },
  };
}

export default createWorker();
